package studio.cluvex.aether.core.probe

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * One request over an already-open socket, with a hard ceiling on what it will
 * buffer. No HTTP client is doing the safety work for us here, which is the
 * entire reason this file is small and paranoid.
 *
 * KEEP-ALIVE FIX (this is the important one): the previous reader was "read
 * until EOF, up to 64 KB". A peer that ignores `Connection: close` never sends
 * EOF, so the read blocked until the socket timeout expired and the whole
 * attempt was then reported as a FAILED provider - after burning a full
 * timeout on a response we had already received completely. The reader now
 * stops as soon as Content-Length is satisfied, and a read timeout returns
 * what arrived instead of destroying it.
 *
 * CHUNKED FIX: a response with no Content-Length is not an error, it is what Go
 * sends whenever it cannot predict the body length - which is every larger moat
 * answer (see core/moat). Without de-chunking, the body handed to a parser
 * starts with a hex length and the payload looks malformed on a connection that
 * worked perfectly.
 */
internal object MiniHttp {

    private const val CRLF = "\r\n"
    private const val HEADER_END = "\r\n\r\n"
    private const val CHUNK_BYTES = 8 * 1024
    private const val USER_AGENT = "Aether/1.0"
    private const val UNKNOWN_STATUS = -1
    private val CONTENT_LENGTH = Regex("(?i)content-length:\\s*(\\d+)")
    private val CHUNKED = Regex("(?i)transfer-encoding:[^\\r\\n]*chunked")

    /** A bounded response. [body] is everything after the header block. */
    class Response(val status: Int, val body: String, val byteCount: Int) {
        val isSuccess: Boolean get() = status in 200..299

        /** Used verbatim in log lines, so keep it short and diagnostic. */
        override fun toString(): String = "status=$status, $byteCount bytes"
    }

    fun get(
        socket: Socket,
        host: String,
        path: String,
        limitBytes: Int = ProbeDefaults.MAX_HTTP_RESPONSE_BYTES,
    ): Response = exchange(socket, requestFor("GET", host, path), null, limitBytes)

    /**
     * One POST with a body.
     *
     * [contentType] is a parameter rather than a constant because moat's captcha
     * endpoints REJECT anything but `application/vnd.api+json` with their own
     * 415, while the circumvention endpoints want plain JSON.
     */
    fun post(
        socket: Socket,
        host: String,
        path: String,
        contentType: String,
        body: String,
        limitBytes: Int = ProbeDefaults.MAX_HTTP_RESPONSE_BYTES,
    ): Response {
        val payload = body.toByteArray(Charsets.UTF_8)
        val head = buildString {
            append(requestHead("POST", host, path))
            append("Content-Type: ").append(contentType).append(CRLF)
            append("Content-Length: ").append(payload.size).append(CRLF)
            append(CRLF)
        }
        return exchange(socket, head, payload, limitBytes)
    }

    // ------------------------------------------------------------- internals

    private fun exchange(
        socket: Socket,
        head: String,
        body: ByteArray?,
        limitBytes: Int,
    ): Response {
        socket.getOutputStream().apply {
            write(head.toByteArray(Charsets.US_ASCII))
            if (body != null) write(body)
            flush()
        }
        val raw = readBounded(socket.getInputStream(), limitBytes)
        val text = String(raw, Charsets.UTF_8)
        val headers = text.substringBefore(HEADER_END, "")
        val payload = text.substringAfter(HEADER_END, "")
        val decoded = if (CHUNKED.containsMatchIn(headers)) dechunk(payload) else payload
        return Response(statusOf(text), decoded, raw.size)
    }

    private fun requestFor(method: String, host: String, path: String): String =
        requestHead(method, host, path) + CRLF

    private fun requestHead(method: String, host: String, path: String): String = buildString {
        append(method).append(' ').append(path).append(" HTTP/1.1").append(CRLF)
        append("Host: ").append(host).append(CRLF)
        append("User-Agent: ").append(USER_AGENT).append(CRLF)
        append("Accept: */*").append(CRLF)
        append("Connection: close").append(CRLF)
    }

    /**
     * Reads at most [limit] bytes, stopping at the declared end of the body, at
     * EOF, or at a read timeout - whichever comes first. Anything the peer still
     * wants to send is left unread; the caller closes the socket right after,
     * which tears the connection down.
     */
    private fun readBounded(input: InputStream, limit: Int): ByteArray {
        val out = ByteArrayOutputStream(minOf(limit, CHUNK_BYTES))
        val chunk = ByteArray(CHUNK_BYTES)
        var total = -1
        while (out.size() < limit) {
            val want = minOf(chunk.size, limit - out.size())
            val read = try {
                input.read(chunk, 0, want)
            } catch (_: SocketTimeoutException) {
                break
            }
            if (read < 0) break
            out.write(chunk, 0, read)
            if (total < 0) total = declaredTotalBytes(out.toByteArray())
            if (total in 1..out.size()) break
        }
        return out.toByteArray()
    }

    /**
     * Total size (headers + body) once the header block AND a Content-Length are
     * both visible, or -1 while that is still unknown. The length is read from
     * the HEADER block only, so a body that happens to mention the header name
     * cannot shorten the read.
     */
    private fun declaredTotalBytes(buffered: ByteArray): Int {
        // Header bytes are ASCII by definition; a byte-preserving charset keeps
        // offsets equal to indices even when the body is not yet complete.
        val text = String(buffered, Charsets.ISO_8859_1)
        val headerEnd = text.indexOf(HEADER_END)
        if (headerEnd < 0) return -1
        val declared = CONTENT_LENGTH.find(text.substring(0, headerEnd))
            ?.groupValues?.get(1)?.toIntOrNull() ?: return -1
        return headerEnd + HEADER_END.length + declared
    }

    /**
     * RFC 9112 chunked bodies, forgivingly: a truncated final chunk returns what
     * was decoded rather than throwing, because a partial answer is still worth
     * showing the user and every caller here validates the payload anyway.
     */
    private fun dechunk(body: String): String {
        val out = StringBuilder(body.length)
        var index = 0
        while (index < body.length) {
            val lineEnd = body.indexOf(CRLF, index)
            if (lineEnd < 0) break
            // A chunk extension (";name=value") is legal and ignorable.
            val size = body.substring(index, lineEnd).substringBefore(';').trim()
                .toIntOrNull(16) ?: break
            if (size == 0) break
            val start = lineEnd + CRLF.length
            val end = minOf(start + size, body.length)
            out.append(body, start, end)
            index = end + CRLF.length
        }
        return out.toString()
    }

    /** `HTTP/1.1 200 OK` -> 200, or [UNKNOWN_STATUS] when there is no status line. */
    private fun statusOf(text: String): Int =
        text.substringBefore(CRLF, "").split(' ').getOrNull(1)?.toIntOrNull() ?: UNKNOWN_STATUS
}

package studio.cluvex.aether.core

/**
 * Thin facade over [TProxyService] (hev-socks5-tunnel's own JNI interface).
 *
 * hev MUST run inside the *same process* that owns the VpnService TUN file
 * descriptor (fds are per-process), and its event loop MUST run on a native
 * pthread — not a Java thread. TProxyStartService satisfies both: it returns
 * immediately after spawning hev's own native thread.
 *
 * See TProxyService.kt for the full root-cause history (custom-wrapper SIGSEGV
 * on ART-attached threads).
 *
 * RECONNECT RACE (fixed): the native loop used to run on a detached thread and
 * stop() only REQUESTED a quit, then cleared [running] straight away. A connect
 * right after a disconnect therefore saw "not running", called start, and the
 * bridge - whose own flag the dying thread had not cleared yet - answered
 * "already running" with success. The new session believed in a tunnel that was
 * really the previous one, still bound to the previous TUN fd. Now the bridge
 * joins the loop on stop (bounded), start refuses to run over a live loop, and
 * this facade checks the native truth on both sides.
 */
object HevTunnel {
    @Volatile
    private var running = false

    /** Serialises start against stop, so the two can never interleave. */
    private val lifecycleLock = Any()

    /** True if the native core is available to run. */
    fun isAvailable(): Boolean = TProxyService.available

    /**
     * Starts hev on its own native thread.
     *
     * If a previous loop is still alive (a stop that timed out, or a session
     * that never stopped it) it is stopped and REAPED first; if it refuses to
     * die, this throws instead of pretending a fresh tunnel is up.
     */
    fun start(configPath: String, tunFd: Int): Unit = synchronized(lifecycleLock) {
        if (!TProxyService.available) {
            val detail = TProxyService.loadFailure ?: "unknown loader error"
            DiagnosticsLog.e(
                "tunnel",
                "libaethertun.so (native tunnel bridge) not loaded — cannot start tunnel. $detail",
            )
            throw IllegalStateException("hev native library unavailable: $detail")
        }
        if (running || nativeLoopRunning(fallback = running)) {
            DiagnosticsLog.w("tunnel", "A previous hev loop is still up - stopping it before starting a new one")
            stopLocked()
            if (nativeLoopRunning(fallback = running)) {
                DiagnosticsLog.e("tunnel", "The previous hev loop did not exit; refusing to start a second one")
                throw IllegalStateException("previous hev-socks5-tunnel loop is still running")
            }
        }
        DiagnosticsLog.i("tunnel", "hev-socks5-tunnel starting on native thread (fd=$tunFd)")
        // Upstream's TProxyStartService now returns whether the tunnel really
        // started (bad YAML config, invalid fd, a live previous loop, …).
        // Surface a hard failure instead of pretending the tunnel is up and
        // "connecting" forever.
        val started = TProxyService.TProxyStartService(configPath, tunFd)
        if (!started) {
            DiagnosticsLog.e("tunnel", "hev-socks5-tunnel refused to start (config/fd rejected or old loop alive)")
            throw IllegalStateException("hev-socks5-tunnel failed to start")
        }
        running = true
    }

    /** True while the native loop is really running (not just "was started"). */
    fun isAlive(): Boolean = running && nativeLoopRunning(fallback = true)

    /**
     * Raw cumulative counters from hev's JNI, or null when the core isn't
     * running. The array is [tx_packets, tx_bytes, rx_packets, rx_bytes].
     * Prefer [traffic] over indexing this directly — it fixes the direction.
     */
    fun stats(): LongArray? {
        if (!TProxyService.available || !running) return null
        return runCatching { TProxyService.TProxyGetStats() }.getOrNull()
    }

    /** Cumulative download/upload byte + packet counters, direction-corrected. */
    data class Traffic(
        val downloadBytes: Long,
        val uploadBytes: Long,
        val downloadPackets: Long,
        val uploadPackets: Long,
    )

    /**
     * Direction-correct traffic totals since the core started, or null when the
     * core isn't running.
     *
     * DIRECTION FIX: hev-socks5-tunnel's TProxyGetStats() returns
     * [tx_packets, tx_bytes, rx_packets, rx_bytes] measured on the SOCKS side:
     *   - TX = bytes the core SENT to the proxy  = device → internet = UPLOAD
     *   - RX = bytes the core RECEIVED from proxy = internet → device = DOWNLOAD
     * The previous UI mapped TX→download / RX→upload, so the meters were swapped
     * (a heavy downloader saw their big number under “upload” and vice-versa).
     * The single source of truth for the mapping now lives here.
     */
    fun traffic(): Traffic? {
        val s = stats() ?: return null
        if (s.size < 4) return null
        val uploadPackets = s[0].coerceAtLeast(0L)
        val uploadBytes = s[1].coerceAtLeast(0L)
        val downloadPackets = s[2].coerceAtLeast(0L)
        val downloadBytes = s[3].coerceAtLeast(0L)
        return Traffic(
            downloadBytes = downloadBytes,
            uploadBytes = uploadBytes,
            downloadPackets = downloadPackets,
            uploadPackets = uploadPackets,
        )
    }

    /**
     * Stops hev and returns once the native loop has exited and been joined
     * (bounded inside the bridge). Safe to call repeatedly.
     */
    fun stop(): Unit = synchronized(lifecycleLock) { stopLocked() }

    private fun stopLocked() {
        if (!TProxyService.available) return
        if (!running && !nativeLoopRunning(fallback = false)) return
        try {
            TProxyService.TProxyStopService()
            // Only clear the flag when the native loop really went away:
            // isAlive()/stats() must keep reporting the truth if it did not,
            // and the next start() must know it has a leftover to deal with.
            if (nativeLoopRunning(fallback = false)) {
                DiagnosticsLog.w("tunnel", "hev-socks5-tunnel did not exit in time after the stop request")
            } else {
                DiagnosticsLog.i("tunnel", "hev-socks5-tunnel stopped")
                running = false
            }
        } catch (t: Throwable) {
            DiagnosticsLog.w("tunnel", "TProxyStopService failed: ${t.message}")
        }
    }

    /**
     * The bridge's own view of the loop. A bridge older than r4 has no
     * TProxyIsRunning; [fallback] is what to assume then (UnsatisfiedLinkError
     * is an Error, hence Throwable).
     */
    private fun nativeLoopRunning(fallback: Boolean): Boolean {
        if (!TProxyService.available) return false
        return try {
            TProxyService.TProxyIsRunning()
        } catch (_: Throwable) {
            fallback
        }
    }
}

package studio.cluvex.aether.core

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Xray hop is only as good as two pieces of text: what is read out of the
 * user's paste, and the config that chains it. Both are pinned here on the JVM.
 */
class XrayConfigTest {

    private fun obj(value: Any?): Map<*, *> = assertIs<Map<*, *>>(value)

    private fun list(value: Any?): List<*> = assertIs<List<*>>(value)

    private val reality = "vless://0f6e1c2a-1111-4222-8333-944455556666@203.0.113.10:443" +
        "?encryption=none&flow=xtls-rprx-vision&security=reality&sni=www.example.com&fp=chrome" +
        "&pbk=SbVKOEMjK0sIlbwg4akyBg5mL5KZwwB-ed4eEE7YnRc&sid=6ba85179e30d4fc2&type=tcp#My%20Server"

    @Test
    fun aVlessRealityLinkBecomesAnOutbound() {
        val out = assertNotNull(XrayLink.parse(reality))
        assertEquals("vless", out.protocol)
        assertEquals("203.0.113.10", out.address)
        assertEquals(443, out.port)
        assertEquals("reality", out.security)
        assertEquals("tcp", out.network)
        assertEquals("My Server", out.remark)
        val stream = obj(out.outbound["streamSettings"])
        val settings = obj(stream["realitySettings"])
        assertEquals("www.example.com", settings["serverName"])
        assertEquals("SbVKOEMjK0sIlbwg4akyBg5mL5KZwwB-ed4eEE7YnRc", settings["publicKey"])
        assertEquals("6ba85179e30d4fc2", settings["shortId"])
        val vnext = obj(list(obj(out.outbound["settings"])["vnext"])[0])
        val user = obj(list(vnext["users"])[0])
        assertEquals("xtls-rprx-vision", user["flow"])
        assertEquals("VLESS \u00B7 reality \u00B7 tcp \u00B7 203.0.113.10:443", out.summary)
        assertFalse("0f6e1c2a" in out.summary, "the summary never carries the UUID")
    }

    @Test
    fun theHandshakeIsDialledThroughTheHopUnderneath() {
        val out = assertNotNull(XrayLink.parse(reality))
        val config = obj(XrayJson.parse(XrayConfig.build(out, socksPort = 1849, upstreamPort = 1819)))
        val outbounds = list(config["outbounds"])
        val proxy = obj(outbounds[0])
        assertEquals(XrayConfig.PROXY_TAG, proxy["tag"], "the user's outbound is the default route")
        val sockopt = obj(obj(proxy["streamSettings"])["sockopt"])
        assertEquals(XrayConfig.UPSTREAM_TAG, sockopt["dialerProxy"])
        val upstream = outbounds.map { obj(it) }.single { it["tag"] == XrayConfig.UPSTREAM_TAG }
        assertEquals("socks", upstream["protocol"])
        val server = obj(list(obj(upstream["settings"])["servers"])[0])
        assertEquals("127.0.0.1", server["address"])
        assertEquals(1819L, server["port"])
        val inbound = obj(list(config["inbounds"])[0])
        assertEquals(1849L, inbound["port"])
        assertEquals("127.0.0.1", inbound["listen"])
        assertEquals(true, obj(inbound["settings"])["udp"])
    }

    @Test
    fun withNoHopUnderneathXrayDialsItsServerDirectly() {
        val out = assertNotNull(XrayLink.parse(reality))
        val text = XrayConfig.build(out, socksPort = 1849, upstreamPort = null)
        assertFalse(XrayConfig.UPSTREAM_TAG in text)
        assertFalse("dialerProxy" in text)
    }

    @Test
    fun theDevicesDnsIsAnsweredInsideXrayOverTcp() {
        val out = assertNotNull(XrayLink.parse(reality))
        val config = obj(XrayJson.parse(XrayConfig.build(out, 1849, 1891)))
        val rule = obj(list(obj(config["routing"])["rules"])[0])
        assertEquals(XrayConfig.DNS_TAG, rule["outboundTag"])
        assertEquals("udp", rule["network"])
        assertEquals("53", rule["port"])
        assertTrue(list(obj(config["dns"])["servers"]).all { (it as String).startsWith("tcp://") })
    }

    @Test
    fun vmessBase64JsonIsRead() {
        val json = "{\"v\":\"2\",\"ps\":\"vm\",\"add\":\"vm.example.com\",\"port\":\"8443\"," +
            "\"id\":\"b831381d-6324-4d53-ad4f-8cda48b30811\",\"aid\":\"0\",\"scy\":\"auto\",\"net\":\"ws\"," +
            "\"type\":\"none\",\"host\":\"cdn.example.com\",\"path\":\"/ray\",\"tls\":\"tls\",\"sni\":\"\"}"
        val link = "vmess://" + Base64.getEncoder().encodeToString(json.toByteArray())
        val out = assertNotNull(XrayLink.parse(link))
        assertEquals("vmess", out.protocol)
        assertEquals("vm.example.com", out.address)
        assertEquals(8443, out.port)
        assertEquals("ws", out.network)
        assertEquals("tls", out.security)
        assertEquals("vm", out.remark)
        val stream = obj(out.outbound["streamSettings"])
        assertEquals("/ray", obj(stream["wsSettings"])["path"])
        assertEquals("cdn.example.com", obj(stream["wsSettings"])["host"])
        // The TLS server name falls back to the Host header, like every other client.
        assertEquals("cdn.example.com", obj(stream["tlsSettings"])["serverName"])
    }

    @Test
    fun trojanDefaultsToTlsAndKeepsAnEncodedPassword() {
        val out = assertNotNull(XrayLink.parse("trojan://p%40ss+word@tr.example.com:443?sni=tr.example.com#t"))
        assertEquals("tls", out.security)
        val server = obj(list(obj(out.outbound["settings"])["servers"])[0])
        assertEquals("p@ss+word", server["password"])
    }

    @Test
    fun shadowsocksInEverySpelling() {
        val credentials = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("aes-256-gcm:secret".toByteArray())
        val sip002 = "ss://$credentials@ss.example.com:8388"
        val first = assertNotNull(XrayLink.parse("$sip002#home"))
        assertEquals("shadowsocks", first.protocol)
        assertEquals(8388, first.port)
        assertEquals("home", first.remark)

        val plain = assertNotNull(
            XrayLink.parse("ss://2022-blake3-aes-128-gcm:YWJjZGVmZ2hpamtsbW5vcA%3D%3D@198.51.100.7:443"),
        )
        val server = obj(list(obj(plain.outbound["settings"])["servers"])[0])
        assertEquals("2022-blake3-aes-128-gcm", server["method"])
        assertEquals("YWJjZGVmZ2hpamtsbW5vcA==", server["password"])

        val legacy = "ss://" + Base64.getEncoder()
            .encodeToString("chacha20-ietf-poly1305:pw@192.0.2.9:1234".toByteArray())
        assertEquals(1234, XrayLink.parse(legacy)?.port)

        // A SIP003 plugin is a separate program Xray cannot run.
        assertNull(XrayLink.parse("$sip002/?plugin=obfs-local%3Bobfs%3Dhttp#home"))
    }

    @Test
    fun aSubscriptionBodyUsesItsFirstLink() {
        val body = Base64.getEncoder().encodeToString("$reality\ntrojan://pw@other.example.com:443".toByteArray())
        assertEquals("203.0.113.10", XrayLink.parse(body)?.address)
    }

    @Test
    fun aJsonConfigKeepsItsFirstProxyOutboundButNotItsChaining() {
        val json = """
            {"outbounds":[{"tag":"direct","protocol":"freedom"},
              {"tag":"mine","protocol":"vless",
               "settings":{"vnext":[{"address":"j.example.com","port":443,"users":[{"id":"x","encryption":"none"}]}]},
               "streamSettings":{"network":"ws","security":"tls","sockopt":{"dialerProxy":"elsewhere","tcpFastOpen":true}}}]}
        """.trimIndent()
        val out = assertNotNull(XrayLink.parse(json))
        assertEquals("j.example.com", out.address)
        assertEquals(443, out.port)
        assertEquals("ws", out.network)
        assertEquals("mine", out.remark)
        assertNull(out.outbound["tag"])
        val config = obj(XrayJson.parse(XrayConfig.build(out, 1849, 1891)))
        val proxy = obj(list(config["outbounds"])[0])
        val sockopt = obj(obj(proxy["streamSettings"])["sockopt"])
        assertEquals(XrayConfig.UPSTREAM_TAG, sockopt["dialerProxy"], "the paste never decides the chaining")
        assertEquals(true, sockopt["tcpFastOpen"], "the user's own socket options survive")
    }

    @Test
    fun anythingElseIsRefused() {
        listOf(
            "",
            "   ",
            "hello",
            "http://example.com",
            "vless://@host.example:443",
            "vless://id@host.example",
            "vless://id@host.example:99999",
            "vless://id@host.example:443?security=reality&sni=a.example",
            "{\"outbounds\":[{\"protocol\":\"freedom\"}]}",
            "{not json",
            // Truncated documents: the reader peeks past the last character.
            "{\"protocol\":\"vless\"",
            "{\"outbounds\":[",
            "[1,2",
            "{",
        ).forEach { assertNull(XrayLink.parse(it), "'$it' must not parse") }
    }

    @Test
    fun truncatedJsonIsAParseErrorNotACrash() {
        listOf("{", "[", "{\"a\":1", "[1,", "{\"a\"").forEach { text ->
            assertNull(XrayJson.parseOrNull(text), "'$text' must be refused")
        }
    }

    @Test
    fun theJsonWriterEscapesAndRoundTrips() {
        val value = linkedMapOf<String, Any?>(
            "s" to "a\"b\\c\n\u0001",
            "n" to 12L,
            "d" to 1.5,
            "b" to false,
            "z" to null,
            "l" to listOf(1L, "x"),
        )
        assertEquals<Any?>(value, XrayJson.parse(XrayJson.write(value)))
    }
}

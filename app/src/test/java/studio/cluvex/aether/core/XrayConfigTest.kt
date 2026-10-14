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
 * user's paste, and the config that wraps it. Both are pinned here on the JVM.
 */
class XrayConfigTest {

    private fun obj(value: Any?): Map<*, *> = assertIs<Map<*, *>>(value)

    private fun list(value: Any?): List<*> = assertIs<List<*>>(value)

    private val reality = "vless://0f6e1c2a-1111-4222-8333-944455556666@203.0.113.10:443" +
        "?encryption=none&flow=xtls-rprx-vision&security=reality&sni=www.example.com&fp=chrome" +
        "&pbk=SbVKOEMjK0sIlbwg4akyBg5mL5KZwwB-ed4eEE7YnRc&sid=6ba85179e30d4fc2&type=tcp#My%20Server"

    private val pin = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

    @Test
    fun aVlessRealityLinkBecomesAnOutbound() {
        val out = assertNotNull(XrayLink.parse(reality))
        assertEquals("vless", out.protocol)
        assertEquals("203.0.113.10", out.address)
        assertEquals(443, out.port)
        assertEquals("reality", out.security)
        assertEquals("tcp", out.network)
        assertEquals("My Server", out.remark)
        assertFalse(out.relayed)
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
    fun theHandshakeIsDialledThroughAHopUnderneathWhenThereIsOne() {
        // No current mode puts a hop under Xray, but the code path is kept.
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
        val config = obj(XrayJson.parse(XrayConfig.build(out, 1849, null)))
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
        assertFalse(out.relayed)
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
    fun hysteria2LinksAreRead() {
        val out = assertNotNull(
            XrayLink.parse("hy2://p%40ss@hy.example.com:8443/?sni=real.example.com&pinSHA256=AB:CD#hy"),
        )
        assertEquals("hysteria", out.protocol)
        assertEquals("hy.example.com", out.address)
        assertEquals(8443, out.port)
        assertEquals("hysteria", out.network)
        assertEquals("tls", out.security)
        assertEquals("hy", out.remark)
        val stream = obj(out.outbound["streamSettings"])
        assertEquals("p@ss", obj(stream["hysteriaSettings"])["auth"])
        val tls = obj(stream["tlsSettings"])
        assertEquals("real.example.com", tls["serverName"])
        assertEquals(listOf("h3"), tls["alpn"])
        assertEquals("abcd", tls["pinnedPeerCertSha256"])
        assertFalse("insecure" in XrayConfig.build(out, 1849, null).lowercase())

        assertNotNull(XrayLink.parse("hysteria2://pw@hy.example.com:443"))
        // Salamander obfuscation and port hopping are not carried.
        assertNull(XrayLink.parse("hy2://pw@hy.example.com:443?obfs=salamander&obfs-password=x"))
        assertNull(XrayLink.parse("hy2://pw@hy.example.com:443,20000-30000"))
    }

    @Test
    fun wireguardLinksAreRead() {
        val link = "wireguard://cHJpdmF0ZQ%3D%3D@162.159.192.1:2408?publickey=cHVi%2Bkey%3D" +
            "&address=172.16.0.2%2F32,2606:4700:110::2%2F128&reserved=1,2,3&mtu=1280#warp"
        val out = assertNotNull(XrayLink.parse(link))
        assertEquals("wireguard", out.protocol)
        assertEquals("162.159.192.1", out.address)
        assertEquals(2408, out.port)
        assertEquals("warp", out.remark)
        val settings = obj(out.outbound["settings"])
        assertEquals("cHJpdmF0ZQ==", settings["secretKey"])
        assertEquals(listOf("172.16.0.2/32", "2606:4700:110::2/128"), settings["address"])
        assertEquals(listOf(1, 2, 3), settings["reserved"])
        assertEquals<Any?>(1280, settings["mtu"])
        val peer = obj(list(settings["peers"])[0])
        assertEquals("cHVi+key=", peer["publicKey"])
        assertEquals("162.159.192.1:2408", peer["endpoint"])

        // A raw '/' in the key does not break the link.
        val raw = assertNotNull(XrayLink.parse("wg://ab/cd+ef=@[2606:4700::1]:51820?publickey=x"))
        assertEquals("ab/cd+ef=", obj(raw.outbound["settings"])["secretKey"])
        assertEquals("[2606:4700::1]:51820", obj(list(obj(raw.outbound["settings"])["peers"])[0])["endpoint"])

        assertNull(XrayLink.parse("wireguard://key@1.2.3.4:51820"), "no peer public key")
        assertNull(XrayLink.parse("wireguard://key@1.2.3.4:51820?publickey=x&reserved=1,2"))
    }

    @Test
    fun socksLinksAreRead() {
        val b64 = assertNotNull(XrayLink.parse("socks://dXNlcjpwYXNz@s.example.com:1080#s"))
        assertEquals("socks", b64.protocol)
        assertEquals("s", b64.remark)
        val server = obj(list(obj(b64.outbound["settings"])["servers"])[0])
        val user = obj(list(server["users"])[0])
        assertEquals("user", user["user"])
        assertEquals("pass", user["pass"])

        val plain = assertNotNull(XrayLink.parse("socks5://u:p%40@198.51.100.1:1080"))
        val plainUser = obj(list(obj(list(obj(plain.outbound["settings"])["servers"])[0])["users"])[0])
        assertEquals("p@", plainUser["pass"])

        val open = assertNotNull(XrayLink.parse("socks://s.example.com:1080/"))
        assertNull(obj(list(obj(open.outbound["settings"])["servers"])[0])["users"])
    }

    @Test
    fun aSubscriptionBodyUsesItsFirstLink() {
        val body = Base64.getEncoder().encodeToString("$reality\ntrojan://pw@other.example.com:443".toByteArray())
        assertEquals("203.0.113.10", XrayLink.parse(body)?.address)
    }

    @Test
    fun theFirstEntryThatCanRunIsUsedNotMerelyTheFirstLine() {
        val unusable = "vless://id@host.example:443?security=reality&sni=a.example"
        assertEquals("203.0.113.10", XrayLink.parse("$unusable\n$reality")?.address)
        val body = Base64.getEncoder()
            .encodeToString("https://example.com\n$unusable\n$reality".toByteArray())
        assertEquals("203.0.113.10", XrayLink.parse(body)?.address)

        val json = """
            {"outbounds":[
              {"protocol":"vless","settings":{"vnext":[{"address":"old.example.com","port":443,
                "users":[{"id":"x","encryption":"none"}]}]},"streamSettings":{"network":"h2","security":"tls"}},
              {"protocol":"trojan","settings":{"servers":[{"address":"new.example.com","port":443,"password":"pw"}]},
                "streamSettings":{"network":"tcp","security":"tls"}}]}
        """.trimIndent()
        assertEquals("new.example.com", XrayLink.parse(json)?.address)
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
        val direct = obj(list(obj(XrayJson.parse(XrayConfig.build(out, 1849, null)))["outbounds"])[0])
        val directSockopt = obj(obj(direct["streamSettings"])["sockopt"])
        assertNull(directSockopt["dialerProxy"], "the paste never decides the chaining")
        assertEquals(true, directSockopt["tcpFastOpen"], "the user's own socket options survive")
        val config = obj(XrayJson.parse(XrayConfig.build(out, 1849, 1891)))
        val proxy = obj(list(config["outbounds"])[0])
        val sockopt = obj(obj(proxy["streamSettings"])["sockopt"])
        assertEquals(XrayConfig.UPSTREAM_TAG, sockopt["dialerProxy"])
    }

    @Test
    fun jsonWithCommentsIsRead() {
        val json = """
            // my server
            {
              /* the outbound */
              "protocol": "vless", // inline
              "settings": {"vnext": [{"address": "c.example.com", "port": 443,
                "users": [{"id": "x", "encryption": "none"}]}]},
              "streamSettings": {"network": "ws", "security": "tls", "wsSettings": {"path": "/a//b"}}
            }
        """.trimIndent()
        val out = assertNotNull(XrayLink.parse(json))
        assertEquals("c.example.com", out.address)
        assertEquals("/a//b", obj(obj(out.outbound["streamSettings"])["wsSettings"])["path"], "'//' inside a string is not a comment")
        assertNull(XrayJson.parseOrNull("{\"a\":1 /* never closed"))
    }

    // ------------------------------------------- what Xray v26.9.30 refuses

    @Test
    fun allowInsecureIsNeverWrittenAndPinsReplaceIt() {
        val link = "trojan://pw@self.example.com:443?sni=self.example.com&allowInsecure=1" +
            "&pcs=$pin&vcn=origin.example.com#self-signed"
        val out = assertNotNull(XrayLink.parse(link))
        val tls = obj(obj(out.outbound["streamSettings"])["tlsSettings"])
        assertNull(tls["allowInsecure"], "allowInsecure is a removed feature: Xray refuses the whole config")
        assertEquals(pin, tls["pinnedPeerCertSha256"])
        assertEquals("origin.example.com", tls["verifyPeerCertByName"])
        assertFalse("allowInsecure" in XrayConfig.build(out, 1849, null))

        val vmessJson = "{\"add\":\"vm.example.com\",\"port\":443,\"id\":\"b831381d-6324-4d53-ad4f-8cda48b30811\"," +
            "\"net\":\"ws\",\"tls\":\"tls\",\"allowInsecure\":\"1\"}"
        val vmess = assertNotNull(XrayLink.parse("vmess://" + Base64.getEncoder().encodeToString(vmessJson.toByteArray())))
        assertFalse("allowInsecure" in XrayConfig.build(vmess, 1849, null))
    }

    @Test
    fun aPastedJsonOutboundLosesItsAllowInsecure() {
        val json = """
            {"protocol":"trojan","settings":{"servers":[{"address":"t.example.com","port":443,"password":"pw"}]},
             "streamSettings":{"network":"tcp","security":"tls",
               "tlsSettings":{"serverName":"t.example.com","allowInsecure":true,"fingerprint":"chrome"}}}
        """.trimIndent()
        val out = assertNotNull(XrayLink.parse(json))
        val text = XrayConfig.build(out, 1849, null)
        assertFalse("allowInsecure" in text)
        val tls = obj(obj(obj(list(obj(XrayJson.parse(text))["outbounds"])[0])["streamSettings"])["tlsSettings"])
        assertEquals("chrome", tls["fingerprint"], "the rest of the TLS settings survive")
    }

    @Test
    fun realityAndEchExtrasAreCarried() {
        val out = assertNotNull(XrayLink.parse(reality.replace("&type=tcp#", "&type=tcp&pqv=QUJD#")))
        assertEquals("QUJD", obj(obj(out.outbound["streamSettings"])["realitySettings"])["mldsa65Verify"])
        val ech = assertNotNull(XrayLink.parse("trojan://pw@e.example.com:443?sni=e.example.com&ech=AEX%2B"))
        assertEquals("AEX+", obj(obj(ech.outbound["streamSettings"])["tlsSettings"])["echConfigList"])
    }

    @Test
    fun transportsTheCoreRemovedAreRefused() {
        listOf(
            "vless://id@h.example.com:443?security=tls&type=h2&sni=h.example.com",
            "vless://id@h.example.com:443?security=tls&type=http&sni=h.example.com",
            "trojan://pw@h.example.com:443?type=quic",
            "{\"protocol\":\"vless\",\"settings\":{\"vnext\":[{\"address\":\"h.example.com\",\"port\":443," +
                "\"users\":[{\"id\":\"x\",\"encryption\":\"none\"}]}]}," +
                "\"streamSettings\":{\"network\":\"h2\",\"security\":\"tls\"}}",
        ).forEach { assertNull(XrayLink.parse(it), "'$it' uses a transport Xray no longer has") }
        val h2 = "{\"add\":\"vm.example.com\",\"port\":443,\"id\":\"b831381d-6324-4d53-ad4f-8cda48b30811\"," +
            "\"net\":\"h2\",\"tls\":\"tls\"}"
        assertNull(XrayLink.parse("vmess://" + Base64.getEncoder().encodeToString(h2.toByteArray())))
        // tcp with an HTTP *header* is not the removed HTTP/2 transport.
        assertNotNull(XrayLink.parse("vmess://id@h.example.com:80?type=tcp&headerType=http&host=h.example.com"))
    }

    @Test
    fun plaintextVlessAndTrojanToAPublicServerRideTheRelay() {
        listOf(
            "vless://id@203.0.113.5:80?security=none&type=ws",
            "vless://id@v.example.com:80",
            "trojan://pw@203.0.113.5:80?security=none",
            "{\"protocol\":\"vless\",\"settings\":{\"address\":\"v.example.com\",\"port\":80,\"id\":\"x\"," +
                "\"encryption\":\"none\"},\"streamSettings\":{\"network\":\"ws\"}}",
        ).forEach { assertTrue(assertNotNull(XrayLink.parse(it), it).relayed, it) }
        listOf(
            "vless://id@10.0.0.2:80?security=none&type=ws",
            "vless://id@[fd00::2]:80",
            "trojan://pw@192.168.1.4:80?security=none",
            // VLESS Encryption is its own transport security.
            "vless://id@203.0.113.5:80?encryption=mlkem768x25519plus.native.0rtt.abc",
            // VMess and Shadowsocks are not covered by the rule.
            "vmess://id@203.0.113.5:80?type=ws",
        ).forEach { assertFalse(assertNotNull(XrayLink.parse(it), it).relayed, it) }
    }

    @Test
    fun theRelayForwardsUnchangedToTheRealServer() {
        val out = assertNotNull(XrayLink.parse("vless://id@203.0.113.5:8080?security=none&type=ws&path=%2Fws"))
        val config = obj(XrayJson.parse(XrayConfig.build(out, socksPort = 1849, upstreamPort = null, relayPort = 1850)))
        val outbounds = list(config["outbounds"]).map { obj(it) }
        val proxy = outbounds.single { it["tag"] == XrayConfig.PROXY_TAG }
        val vnext = list(obj(proxy["settings"])["vnext"])
        assertEquals(1, vnext.size)
        assertEquals("127.0.0.1", obj(vnext[0])["address"], "Xray only dials a plaintext VLESS to a private address")
        assertEquals(1850L, obj(vnext[0])["port"])
        assertEquals("/ws", obj(obj(proxy["streamSettings"])["wsSettings"])["path"], "the bytes on the wire are what the link asked for")

        val relay = list(config["inbounds"]).map { obj(it) }.single { it["tag"] == XrayConfig.RELAY_TAG }
        assertEquals("dokodemo-door", relay["protocol"])
        assertEquals("127.0.0.1", relay["listen"])
        assertEquals(1850L, relay["port"])
        val target = obj(relay["settings"])
        assertEquals("203.0.113.5", target["address"])
        assertEquals(8080L, target["port"])
        assertEquals("tcp", target["network"])

        assertEquals("freedom", outbounds.single { it["tag"] == XrayConfig.RELAY_OUT_TAG }["protocol"])
        val rule = list(obj(config["routing"])["rules"]).map { obj(it) }
            .single { it["outboundTag"] == XrayConfig.RELAY_OUT_TAG }
        assertEquals(listOf(XrayConfig.RELAY_TAG), rule["inboundTag"])
    }

    @Test
    fun aConfigThatNeedsNoRelayBindsNoRelay() {
        val text = XrayConfig.build(assertNotNull(XrayLink.parse(reality)), 1849, null)
        assertFalse(XrayConfig.RELAY_TAG in text)
        assertFalse("dokodemo-door" in text)
        assertFalse("1850" in text)
    }

    @Test
    fun withAHopUnderneathTheRelayIsWhatDialsThroughIt() {
        val out = assertNotNull(XrayLink.parse("trojan://pw@203.0.113.5:80?security=none"))
        val outbounds = list(obj(XrayJson.parse(XrayConfig.build(out, 1849, 1819)))["outbounds"]).map { obj(it) }
        val proxy = outbounds.single { it["tag"] == XrayConfig.PROXY_TAG }
        assertNull((proxy["streamSettings"] as? Map<*, *>)?.get("sockopt"), "the proxy only dials loopback")
        val direct = outbounds.single { it["tag"] == XrayConfig.RELAY_OUT_TAG }
        assertEquals(XrayConfig.UPSTREAM_TAG, obj(obj(direct["streamSettings"])["sockopt"])["dialerProxy"])
    }

    @Test
    fun privateAddressesAreTheNarrowSet() {
        listOf("10.1.2.3", "127.0.0.1", "172.16.0.1", "172.31.255.1", "192.168.0.1", "169.254.1.1",
            "localhost", "box.localhost", "::1", "[::1]", "fd12::1", "fc00::1", "fe80::1").forEach {
            assertTrue(XrayLink.isPrivateAddress(it), it)
        }
        listOf("8.8.8.8", "172.32.0.1", "100.64.0.1", "203.0.113.5", "example.com", "10.0.0", "1.2.3.256",
            "2001:db8::1", "").forEach {
            assertFalse(XrayLink.isPrivateAddress(it), it)
        }
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
            "// only a comment",
        ).forEach { assertNull(XrayLink.parse(it), "'$it' must not parse") }
    }

    @Test
    fun truncatedJsonIsAParseErrorNotACrash() {
        listOf("{", "[", "{\"a\":1", "[1,", "{\"a\"", "{/*", "/").forEach { text ->
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

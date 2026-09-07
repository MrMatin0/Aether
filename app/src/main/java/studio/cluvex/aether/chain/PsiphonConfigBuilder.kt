package studio.cluvex.aether.chain

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import studio.cluvex.aether.core.TunnelConfig
import java.io.File

/**
 * Generates psiphon-tunnel-core configuration.
 *
 * Defaults use standard public network keys & embedded server discovery
 * matching standard Android distributions so manual user input is optional.
 */
object PsiphonConfigBuilder {

    private const val DEFAULT_REMOTE_PUB_KEY =
        "MIICIDANBgkqhkiG9w0BAQEFAAOCAg0AMIICCAKCAgEAt7Ls+/39r+T6zNW7GiVpJfzq/xvL9SBH5rIFnk0RXYEYavax3WS6HOD35eTAqn8AniOwiH+DOkvgSKF2caqk/y1dfq47Pdymtwzp9ikpB1C5OfAysXzBiwVJlCdajBKvBZDerV1cMvRzCKvKwRmvDmHgphQQ7WfXIGbRbmmk6opMBh3roE42KcotLFtqp0RRwLtcBRNtCdsrVsjiI1Lqz/lH+T61sGjSjQ3CHMuZYSQJZo/KrvzgQXpkaCTdbObxHqb6/+i1qaVOfEsvjoiyzTxJADvSytVtcTjijhPEV6XskJVHE1Zgl+7rATr/pDQkw6DPCNBS1+Y6fy7GstZALQXwEDN/qhQI9kWkHijT8ns+i1vGg00Mk/6J75arLhqcodWsdeG/M/moWgqQAnlZAGVtJI1OgeF5fsPpXu4kctOfuZlGjVZXQNW34aOzm8r8S0eVZitPlbhcPiR4gT/aSMz/wd8lZlzZYsje/Jr8u/YtlwjjreZrGRmG8KMOzukV3lLmMppXFMvl4bxv6YFEmIuTsOhbLTwFgh7KYNjodLj/LsqRVfwz31PgWQFTEPICV7GCvgVlPRxnofqKSjgTWI4mxDhBpVcATvaoBl1L/6WLbFvBsoAUBItWwctO2xalKxF5szhGm8lccoc5MZr8kfE0uxMgsxz4er68iCID+rsCAQM="

    private const val DEFAULT_SERVER_ENTRY_PUB_KEY =
        "sHuUVTWaRyh5pZwy4UguSgkwmBe0EHtJJkoF5WrxmvA="

    private const val DEFAULT_PROPAGATION_CHANNEL = "92AACC5BABE0944C"
    private const val DEFAULT_SPONSOR_ID = "1BC527D3D09985CF"

    fun build(
        context: Context,
        rawJsonOrEmpty: String,
        upstreamSocksPort: Int?,
        localSocksPort: Int = TunnelConfig.PSIPHON_SOCKS_PORT,
        egressRegion: String = ""
    ): String {
        val root = if (rawJsonOrEmpty.isBlank()) {
            JSONObject()
        } else {
            runCatching { JSONObject(rawJsonOrEmpty) }.getOrElse { JSONObject() }
        }

        // Apply fallback defaults if keys are missing
        if (!root.has("PropagationChannelId")) {
            root.put("PropagationChannelId", DEFAULT_PROPAGATION_CHANNEL)
        }
        if (!root.has("SponsorId")) {
            root.put("SponsorId", DEFAULT_SPONSOR_ID)
        }
        if (!root.has("RemoteServerListSignaturePublicKey")) {
            root.put("RemoteServerListSignaturePublicKey", DEFAULT_REMOTE_PUB_KEY)
        }
        if (!root.has("ServerEntrySignaturePublicKey")) {
            root.put("ServerEntrySignaturePublicKey", DEFAULT_SERVER_ENTRY_PUB_KEY)
        }

        if (!root.has("RemoteServerListURLs")) {
            val list = JSONArray().apply {
                put(JSONObject().apply {
                    put("OnlyAfterAttempts", 0)
                    put("SkipVerify", false)
                    put("URL", "aHR0cHM6Ly9zMy5hbWF6b25hd3MuY29tL3BzaXBob24vd2ViL21qcjQtcDIzci1wdXdsL3NlcnZlcl9saXN0X2NvbXByZXNzZWQ=")
                })
                put(JSONObject().apply {
                    put("OnlyAfterAttempts", 2)
                    put("SkipVerify", true)
                    put("URL", "aHR0cHM6Ly93d3cubGF0aW5vZmlybWRkaG9zdHMuY29tL3dlYi9tanI0LXAyM3ItcHV3bC9zZXJ2ZXJfbGlzdF9jb21wcmVzc2Vk")
                })
            }
            root.put("RemoteServerListURLs", list)
        }

        if (!root.has("ObfuscatedServerListRootURLs")) {
            val list = JSONArray().apply {
                put(JSONObject().apply {
                    put("OnlyAfterAttempts", 0)
                    put("SkipVerify", false)
                    put("URL", "aHR0cHM6Ly9zMy5hbWF6b25hd3MuY29tL3BzaXBob24vd2ViL21qcjQtcDIzci1wdXdsL29zbA==")
                })
                put(JSONObject().apply {
                    put("OnlyAfterAttempts", 2)
                    put("SkipVerify", true)
                    put("URL", "aHR0cHM6Ly93d3cubGF0aW5vZmlybWRkaG9zdHMuY29tL3dlYi9tanI0LXAyM3ItcHV3bC9vc2w=")
                })
            }
            root.put("ObfuscatedServerListRootURLs", list)
        }

        // Port & Directory binding
        val dataDir = File(context.filesDir, "psiphon_core_data").apply { mkdirs() }
        root.put("DataRootDirectory", dataDir.absolutePath)
        root.put("LocalSocksProxyPort", localSocksPort)
        root.put("DisableLocalHTTPProxy", true)
        root.put("EmitDiagnosticNotices", true)
        root.put("EmitBytesTransferred", true)
        root.put("UseIndistinguishableTLS", true)

        if (egressRegion.isNotBlank()) {
            root.put("EgressRegion", egressRegion.trim().uppercase())
        }

        // Check if server_entries.txt exists in assets and load if root doesn't have TargetServerEntry
        if (!root.has("TargetServerEntry")) {
            val assetEntries = readAssetServerEntries(context)
            if (assetEntries.isNotBlank()) {
                root.put("TargetServerEntry", assetEntries)
            }
        }

        // Upstream chaining
        if (upstreamSocksPort != null && upstreamSocksPort > 0) {
            root.put("UpstreamProxyUrl", "socks5://127.0.0.1:$upstreamSocksPort")
            root.put("UpstreamProxyAllowAllServerEntrySources", true)
        } else {
            root.remove("UpstreamProxyUrl")
        }

        return root.toString()
    }

    private fun readAssetServerEntries(context: Context): String {
        return runCatching {
            context.assets.open("server_entries.txt").bufferedReader().use { it.readText().trim() }
        }.getOrDefault("")
    }
}

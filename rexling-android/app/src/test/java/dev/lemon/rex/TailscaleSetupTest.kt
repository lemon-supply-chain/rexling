package dev.lemon.rex

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TailscaleSetupTest {
    @Test
    fun loginWaitsForTheBrowserLinkInsteadOfReturningSilently() = runBlocking {
        var reads = 0
        val link = "https://login.tailscale.com/a/test"
        assertEquals(
            link,
            awaitTailscaleAuth {
                reads++
                JSONObject()
                    .put(
                        "tailscale",
                        JSONObject().put("BackendState", "NeedsLogin").apply {
                            if (reads == 2) put("AuthURL", link)
                        },
                    )
            },
        )
        assertEquals(2, reads)
    }

    @Test
    fun loginReportsNativeErrorsAndRecognizesAnExistingLogin() = runBlocking {
        assertEquals(
            "",
            awaitTailscaleAuth {
                JSONObject(
                    "{\"tailscale\":{\"BackendState\":\"Running\",\"AuthURL\":\"https://login.tailscale.com/old\"}}",
                )
            },
        )
        assertTrue(
            runCatching { awaitTailscaleAuth { JSONObject().put("error", "Offline") } }
                .exceptionOrNull()
                ?.message == "Offline",
        )
    }

    @Test
    fun activePeersAreGroupedWithReadableRoutesAndStableNames() {
        val status =
            JSONObject(
                """{"Peer":{"a":{"Active":true,"HostName":"shipyard-rex","CurAddr":"10.0.0.2:1"},"b":{"Active":true,"HostName":"mac","PeerRelay":"relay"},"c":{"Active":false,"HostName":"idle"},"d":{"Active":true,"DNSName":"ubuntu.ts.net."}}}""",
            )
        assertEquals(
            listOf(
                TailscalePeerStatus("mac", "Peer relay"),
                TailscalePeerStatus("shipyard-rex", "Direct"),
                TailscalePeerStatus("ubuntu.ts.net", "Tailscale relay"),
            ),
            tailscalePeers(status),
        )
    }

    @Test
    fun migrationPreservesEachExistingRouteWithoutAGlobalDefault() {
        val before = JSONObject("""{"https://phone":"phone","ssh://me@host":"default"}""")
        val migrated =
            migratedServerRoutes(
                before,
                listOf("https://phone", "ssh://me@host", "https://legacy"),
                true,
            )
        assertEquals("phone", migrated.getString("https://phone"))
        assertEquals("builtin", migrated.getString("ssh://me@host"))
        assertEquals("builtin", migrated.getString("https://legacy"))
        assertEquals(
            "phone",
            migratedServerRoutes(JSONObject(), listOf("https://old"), false)
                .getString("https://old"),
        )
    }

    @Test
    fun networkLabelsAndMigrationDefaultsAreReadable() {
        assertEquals("Sign-in required", tailscaleStatusLabel("NeedsLogin"))
        assertEquals("Not set up", tailscaleStatusLabel("Off"))
        assertEquals("Not connected", tailscaleStatusLabel(""))
        assertEquals("Device approval required", tailscaleStatusLabel("NeedsMachineAuth"))
        assertEquals(ServerNetworkRoute.PHONE, ServerNetworkRoute.decode(null))
        assertEquals(ServerNetworkRoute.PHONE, ServerNetworkRoute.decode("future"))
        assertEquals(ServerNetworkRoute.BUILTIN, ServerNetworkRoute.decode("builtin"))
    }

    @Test
    fun statusLabelResReturnsCorrectResourceForStates() {
        assertEquals(R.string.tailscale_running, tailscaleStatusLabelRes("Running"))
        assertEquals(R.string.tailscale_needs_login, tailscaleStatusLabelRes("NeedsLogin"))
        assertEquals(
            R.string.tailscale_needs_machine_auth,
            tailscaleStatusLabelRes("NeedsMachineAuth"),
        )
        assertEquals(R.string.tailscale_off, tailscaleStatusLabelRes("Off"))
        assertEquals(R.string.tailscale_stopped, tailscaleStatusLabelRes("Stopped"))
        assertEquals(R.string.tailscale_starting, tailscaleStatusLabelRes("Starting"))
        assertEquals(R.string.tailscale_not_connected, tailscaleStatusLabelRes("unknown"))
    }
}

package dev.lemon.rex

import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WorkspaceConnectionTest {
    @get:Rule val ui = createEmptyComposeRule()

    @Test
    fun lastUsedServerReconnectsWithProgressInsteadOfTheServerForm() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(context.packageName.endsWith(".renderbench"))
        val preferences = context.getSharedPreferences("rex", 0)
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            server.start(InetAddress.getByName("127.0.0.1"), 0)
            val host =
                ServerProfile.create("Slow Rex", "http://127.0.0.1:${server.port}", "test-server")
            val other = ServerProfile.create("Other Rex", "http://127.0.0.1:1", "other-server")
            preferences
                .edit()
                .clear()
                .putString("servers", SavedServers.encode(listOf(other, host)))
                .putString("active_server", host.id)
                .putStringSet("connected_servers", setOf(host.id))
                .commit()
            try {
                ActivityScenario.launch(MainActivity::class.java).use {
                    val progress = hasText("Connecting…") or hasText("Reconnecting · input paused")
                    ui.waitUntil(10_000) {
                        ui.onAllNodes(progress).fetchSemanticsNodes().isNotEmpty()
                    }
                    ui.onNode(progress).assertIsDisplayed()
                    ui.onNode(
                            SemanticsMatcher.expectValue(
                                SemanticsProperties.ProgressBarRangeInfo,
                                ProgressBarRangeInfo.Indeterminate,
                            ),
                        )
                        .assertIsDisplayed()
                    ui.onNodeWithText(appString(R.string.connect_over_tailscale))
                        .assertDoesNotExist()
                    ui.onNodeWithText(appString(R.string.rex_server_url)).assertDoesNotExist()
                    assertNotNull(
                        "Saved connection should start automatically",
                        server.takeRequest(5, TimeUnit.SECONDS),
                    )
                }
                preferences.edit().remove("connected_servers").commit()
                ActivityScenario.launch(MainActivity::class.java).use {
                    ui.onNodeWithText(appString(R.string.rex_server_url)).assertIsDisplayed()
                }
            } finally {
                preferences.edit().clear().commit()
            }
        }
    }
}

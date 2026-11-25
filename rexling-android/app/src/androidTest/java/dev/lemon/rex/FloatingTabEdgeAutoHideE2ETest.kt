package dev.lemon.rex

import android.content.Context
import android.content.res.Configuration
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end verification of [MainActivity] floating side rail edge auto-hide behavior, including
 * dragging to the edge and auto-hiding both before opening [TabStackFan] and after closing it.
 */
@RunWith(AndroidJUnit4::class)
class FloatingTabEdgeAutoHideE2ETest {
    @get:Rule val ui = createEmptyComposeRule()

    private class WorkspaceServer : AutoCloseable {
        private val sockets = CopyOnWriteArrayList<WebSocket>()

        private fun viewJson(): JSONObject =
            JSONObject()
                .put(
                    "windows",
                    JSONArray()
                        .put(
                            JSONObject()
                                .put("window_id", "w1")
                                .put("label", "Dev")
                                .put(
                                    "layers",
                                    JSONArray()
                                        .put(
                                            JSONObject()
                                                .put("kind", "tiled")
                                                .put(
                                                    "blocks",
                                                    JSONArray()
                                                        .put(
                                                            JSONObject()
                                                                .put("block_id", "b0")
                                                                .put("label", "codex")
                                                                .put(
                                                                    "creator_name",
                                                                    "com.superlogical.terminal",
                                                                ),
                                                        )
                                                        .put(
                                                            JSONObject()
                                                                .put("block_id", "b1")
                                                                .put("label", "claude")
                                                                .put(
                                                                    "creator_name",
                                                                    "com.superlogical.terminal",
                                                                ),
                                                        )
                                                        .put(
                                                            JSONObject()
                                                                .put("block_id", "b2")
                                                                .put("label", "fish")
                                                                .put(
                                                                    "creator_name",
                                                                    "com.superlogical.terminal",
                                                                ),
                                                        )
                                                        .put(
                                                            JSONObject()
                                                                .put("block_id", "b3")
                                                                .put("label", "nvim")
                                                                .put(
                                                                    "creator_name",
                                                                    "com.superlogical.terminal",
                                                                ),
                                                        ),
                                                ),
                                        ),
                                ),
                        ),
                )

        private val server =
            MockWebServer().apply {
                dispatcher =
                    object : Dispatcher() {
                        override fun dispatch(request: RecordedRequest): MockResponse {
                            val protocol =
                                request.getHeader("Sec-WebSocket-Protocol") ?: "rex.control.v1"
                            return MockResponse()
                                .setHeader("Sec-WebSocket-Protocol", protocol)
                                .withWebSocketUpgrade(
                                    object : WebSocketListener() {
                                        override fun onOpen(
                                            webSocket: WebSocket,
                                            response: Response,
                                        ) {
                                            sockets += webSocket
                                        }

                                        override fun onMessage(
                                            webSocket: WebSocket,
                                            bytes: ByteString,
                                        ) {
                                            val welcome = ByteArray(32)
                                            welcome[0] = 0x81.toByte()
                                            for (i in 1..16) welcome[i] = 1
                                            welcome[20] = 0x60.toByte()
                                            welcome[21] = 0xEA.toByte()
                                            welcome[24] = 1
                                            webSocket.send(welcome.toByteString())
                                        }

                                        override fun onMessage(webSocket: WebSocket, text: String) {
                                            val req = JSONObject(text)
                                            if (req.optString("type") == "hello") {
                                                webSocket.send(
                                                    """{"type":"welcome","protocol":"rex.control.v1","api_revision":8,"limits":{"max_message_bytes":1048576},"client":{"id":"e2e","resumed":false}}""",
                                                )
                                                return
                                            }
                                            val method = req.getString("method")
                                            val payload =
                                                when (method) {
                                                    "session.attach_all",
                                                    "session.list" ->
                                                        JSONObject(
                                                            """{"sessions":[{"session_id":"s1","label":"Work"}]}""",
                                                        )
                                                    "server.whoami" ->
                                                        JSONObject(
                                                            """{"principal":{"display":"Loopback"}}""",
                                                        )
                                                    "session.attach" ->
                                                        JSONObject().put("view", viewJson())
                                                    "session.view" -> viewJson()
                                                    "data.connect" ->
                                                        JSONObject()
                                                            .put("ticket", "AAAAAAAAAAAAAAAAAAAAAA")
                                                    else -> JSONObject()
                                                }
                                            webSocket.send(
                                                JSONObject()
                                                    .put("type", "response")
                                                    .put("id", req.getString("id"))
                                                    .put("payload", payload)
                                                    .toString(),
                                            )
                                        }

                                        override fun onClosed(
                                            webSocket: WebSocket,
                                            code: Int,
                                            reason: String,
                                        ) {
                                            sockets.remove(webSocket)
                                        }
                                    },
                                )
                        }
                    }
                start(InetAddress.getByName("127.0.0.1"), 0)
            }

        val endpoint: String
            get() = server.url("/").toString().trimEnd('/')

        override fun close() {
            sockets.forEach { runCatching { it.close(1000, "E2E done") } }
            server.shutdown()
        }
    }

    private fun currentWidthClass(context: Context): TabWidthClass {
        val widthDp = context.resources.configuration.screenWidthDp
        return TabWidthClass.of(
            if (widthDp != Configuration.SCREEN_WIDTH_DP_UNDEFINED) widthDp else 400,
        )
    }

    private inline fun withEdgeWorkspace(
        position: TabBarPosition,
        initialX: Float,
        block: () -> Unit,
    ) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("rex", Context.MODE_PRIVATE)
        val widthClass = currentWidthClass(context)
        val original = prefs.all.toMap()

        WorkspaceServer().use { server ->
            val profile = ServerProfile.create("E2E Rex", server.endpoint, "e2e-server")
            prefs
                .edit()
                .clear()
                .putString("servers", SavedServers.encode(listOf(profile)))
                .putString("active_server", profile.id)
                .putStringSet("connected_servers", setOf(profile.id))
                .putString("current_session:${server.endpoint}", "s1")
                .putBoolean("floating_tabs", true)
                .putString(widthClass.key, TabBarLayout(position, TabRailStyle.ICONS).encode())
                .putFloat("floating_tab_x", initialX)
                .putFloat("floating_tab_y", 0.7f)
                .commit()

            try {
                ActivityScenario.launch(MainActivity::class.java).use { block() }
            } finally {
                val editor = prefs.edit().clear()
                for ((key, value) in original) {
                    when (value) {
                        is Boolean -> editor.putBoolean(key, value)
                        is Int -> editor.putInt(key, value)
                        is Long -> editor.putLong(key, value)
                        is Float -> editor.putFloat(key, value)
                        is String -> editor.putString(key, value)
                        is Set<*> ->
                            editor.putStringSet(key, value.filterIsInstance<String>().toSet())
                    }
                }
                editor.commit()
            }
        }
    }

    private fun awaitHiddenAtEdgeAndReopenFan() {
        ui.waitUntil(8_000) {
            ui.onAllNodesWithTag("Floating tab expand").fetchSemanticsNodes().isNotEmpty()
        }
        ui.onNodeWithTag("Floating tab expand").assertIsDisplayed()
        ui.onNodeWithTag("Floating tab rail").assertDoesNotExist()

        ui.onNodeWithTag("Floating tab expand").performTouchInput { click() }
        ui.waitUntil(3_000) {
            ui.onAllNodesWithTag("Floating tab rail").fetchSemanticsNodes().isNotEmpty()
        }
        Thread.sleep(300)
        ui.waitForIdle()
        ui.onNode(isSelected() and hasAnyAncestor(hasTestTag("Floating tab rail")))
            .assertIsEnabled()
            .performTouchInput { click() }
        ui.waitUntil(3_000) {
            ui.onAllNodesWithTag("Tab stack fan").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun verifyEdgeRailAutoHides(position: TabBarPosition, edgeX: Float) {
        withEdgeWorkspace(position, edgeX) {
            ui.waitUntil(10_000) {
                ui.onAllNodesWithTag("Floating tab rail").fetchSemanticsNodes().isNotEmpty()
            }
            ui.onNodeWithTag("Floating tab rail").assertIsDisplayed()

            awaitHiddenAtEdgeAndReopenFan()
            ui.onNodeWithTag("Tab fan card b0").assertIsDisplayed()

            ui.onNodeWithTag("Tab stack fan scrim").performTouchInput {
                val dismissX = if (position == TabBarPosition.RIGHT) left + 24f else right - 24f
                click(Offset(dismissX, top + 24f))
            }
            ui.waitUntil(3_000) {
                ui.onAllNodesWithTag("Tab stack fan").fetchSemanticsNodes().isEmpty()
            }
            ui.waitUntil(8_000) {
                ui.onAllNodesWithTag("Floating tab expand").fetchSemanticsNodes().isNotEmpty()
            }
            ui.onNodeWithTag("Floating tab expand").assertIsDisplayed()
            ui.onNodeWithTag("Floating tab rail").assertDoesNotExist()
        }
    }

    @Test
    fun rightFloatingRailAutoHidesAtEdgeBeforeAndAfterClosingFan() {
        verifyEdgeRailAutoHides(TabBarPosition.RIGHT, 1.0f)
    }

    @Test
    fun leftFloatingRailAutoHidesAtEdgeBeforeAndAfterClosingFan() {
        verifyEdgeRailAutoHides(TabBarPosition.LEFT, 0.0f)
    }

    @Test
    fun draggingFloatingRailToRightEdgeAutoHidesBeforeAndAfterSelectingPaneFromFan() {
        withEdgeWorkspace(TabBarPosition.RIGHT, 0.5f) {
            ui.waitUntil(10_000) {
                ui.onAllNodesWithTag("Floating tab rail").fetchSemanticsNodes().isNotEmpty()
            }
            ui.onNodeWithTag("Floating tab handle").performTouchInput {
                down(center)
                advanceEventTime(700)
                moveBy(Offset(600f, 0f))
                up()
            }
            ui.waitForIdle()

            awaitHiddenAtEdgeAndReopenFan()
            ui.onNodeWithTag("Tab fan card b1").assertIsDisplayed()
            ui.onNodeWithTag("Tab fan card b1").performTouchInput { doubleClick() }
            ui.waitUntil(3_000) {
                ui.onAllNodesWithTag("Tab stack fan").fetchSemanticsNodes().isEmpty()
            }
            ui.waitUntil(8_000) {
                ui.onAllNodesWithTag("Floating tab expand").fetchSemanticsNodes().isNotEmpty()
            }
            ui.onNodeWithTag("Floating tab expand").assertIsDisplayed()
            ui.onNodeWithTag("Floating tab rail").assertDoesNotExist()
        }
    }
}

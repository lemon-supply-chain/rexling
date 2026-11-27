package dev.lemon.rex

import android.content.Context
import android.content.res.Configuration
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.click
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
import org.junit.Assert.assertTrue
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
                                            val nonceBytes =
                                                java.nio.ByteBuffer.allocate(8)
                                                    .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                                                    .putLong(11418691820941138289UL.toLong())
                                                    .array()
                                            nonceBytes.copyInto(welcome, 24)
                                            webSocket.send(welcome.toByteString())
                                            webSocket.send(SNAPSHOT_BYTES.toByteString())
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
                                                    "com.superlogical.terminal.process" ->
                                                        JSONObject(
                                                            """{"foreground":{"pid":1,"name":"codex"}}""",
                                                        )
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

        companion object {
            // Minimal valid Ghostty snapshot + APC fixture
            // (rexling-core/tests/fixtures/ghostty-remote.bin).
            private val SNAPSHOT_BYTES =
                android.util.Base64.decode(
                    "R0hPU1RTTlABAAEAmQMAAGU0AF9QABgAAAAAAAAAAAAAABcAAABPAAAAAAEAWQAAAAEBAQEAAAAACAAEIgBkAAAAAAQiAGQAAAAABCIAZAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAEAAAAAP//////////AAEBAQEBAQEBAR0fIcxmZrW9aPDGdIGivrKUu4q+t8XIxmZmZtVOU7nKSufFR3qm2sOX2HDAserq6gAAAAAAXwAAhwAArwAA1wAA/wBfAABfXwBfhwBfrwBf1wBf/wCHAACHXwCHhwCHrwCH1wCH/wCvAACvXwCvhwCvrwCv1wCv/wDXAADXXwDXhwDXrwDX1wDX/wD/AAD/XwD/hwD/rwD/1wD//18AAF8AX18Ah18Ar18A118A/19fAF9fX19fh19fr19f119f/1+HAF+HX1+Hh1+Hr1+H11+H/1+vAF+vX1+vh1+vr1+v11+v/1/XAF/XX1/Xh1/Xr1/X11/X/1//AF//X1//h1//r1//11///4cAAIcAX4cAh4cAr4cA14cA/4dfAIdfX4dfh4dfr4df14df/4eHAIeHX4eHh4eHr4eH14eH/4evAIevX4evh4evr4ev14ev/4fXAIfXX4fXh4fXr4fX14fX/4f/AIf/X4f/h4f/r4f/14f//68AAK8AX68Ah68Ar68A168A/69fAK9fX69fh69fr69f169f/6+HAK+HX6+Hh6+Hr6+H16+H/6+vAK+vX6+vh6+vr6+v16+v/6/XAK/XX6/Xh6/Xr6/X16/X/6//AK//X6//h6//r6//16///9cAANcAX9cAh9cAr9cA19cA/9dfANdfX9dfh9dfr9df19df/9eHANeHX9eHh9eHr9eH19eH/9evANevX9evh9evr9ev19ev/9fXANfXX9fXh9fXr9fX19fX/9f/ANf/X9f/h9f/r9f/19f///8AAP8AX/8Ah/8Ar/8A1/8A//9fAP9fX/9fh/9fr/9f1/9f//+HAP+HX/+Hh/+Hr/+H1/+H//+vAP+vX/+vh/+vr/+v1/+v///XAP/XX//Xh//Xr//X1//X////AP//X///h///r///1////wgICBISEhwcHCYmJjAwMDo6OkRERE5OTlhYWGJiYmxsbHZ2doCAgIqKipSUlJ6enqioqLKysry8vMbGxtDQ0Nra2uTk5O7u7gAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAACADYAAAD3L6XxAAABAAAAAAAAAAAAAAACAAEAAAAAAAAAAAAAAAAAAAAAAAAAAAAACAAAAAAAAAAAAAAAAAAAAwDHAAAA/u1kdFAAGAABAAAAgADAAAAgAAAACAAAAQACDMhaAAAAAAAAAAABAAAAMAkAHAEABAAAAABIAQAEAAAAABQBAAQAAAAAFAEABAAAAAA4AQAEAAAAAIAAAAAAAAAA7A4AAAAAAAAA5gcAAAQAAAAAAAAACAAAAA0AR0hPU1RUWV9SRUFEWQAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAHAAAAAAAngGPRBQAAAAAA5CDvCgQABgAAAKERil4AAAAAAAAGAAAAAAA+61M+G19yZXgxOzExNDE4NjkxODIwOTQxMTM4Mjg5O3sidHlwZSI6InJlc2l6ZSIsImNvbHMiOjgwLCJyb3dzIjoyNCwiY2VsbF93aWR0aF9weCI6OCwiY2VsbF9oZWlnaHRfcHgiOjE2LCJvd25lciI6ImNsaWVudDowMWExOTIzZS0yZmY2LTc3NjctODIyMi01MGUzNTQ0YWQ4NDAifRtcUkVQTFk6bGl2ZS3Ou/CfpoANChtfcmV4MTsxMTQxODY5MTgyMDk0MTEzODI4OTt7InR5cGUiOiJyZXNpemUiLCJjb2xzIjo5Mywicm93cyI6MzEsImNlbGxfd2lkdGhfcHgiOjgsImNlbGxfaGVpZ2h0X3B4IjoxNiwib3duZXIiOiJjbGllbnQ6MDFhMTkyM2UtMmZmNi03NzY3LTgyMjItNTBlMzU0NGFkODQwIn0bXDMxIDkzDQobX3JleDE7MTE0MTg2OTE4MjA5NDExMzgyODk7eyJ0eXBlIjoidGhlbWUiLCJmb3JlZ3JvdW5kIjoiI2VlZWVlZSIsImJhY2tncm91bmQiOiIjMTEyMjMzIiwicGFsZXR0ZSI6WyIyPSNhYmNkZWYiXX0bXFJFUExZOnRoZW1lLWFwcGxpZWQNChtbPzEwNDloG1tIQUxURVJOQVRFX1JFQURZG1s/MTA0OWxSRVBMWTphZnRlci1tYWluDQpSRVBMWTpkdXJpbmctZ2FwDQo=",
                    android.util.Base64.DEFAULT,
                )
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
        initialY: Float = 0.7f,
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
                .putFloat("floating_tab_y", initialY)
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

    private fun awaitHiddenAtEdgeAndReopenFan(barTag: String = "Floating tab rail"): Rect {
        ui.waitUntil(8_000) {
            ui.onAllNodesWithTag("Floating tab expand").fetchSemanticsNodes().isNotEmpty()
        }
        val expandBounds =
            ui.onNodeWithTag("Floating tab expand")
                .assertIsDisplayed()
                .fetchSemanticsNode()
                .boundsInRoot
        ui.onNodeWithTag(barTag).assertDoesNotExist()

        ui.onNodeWithTag("Floating tab expand").performTouchInput { click() }
        ui.waitUntil(3_000) { ui.onAllNodesWithTag(barTag).fetchSemanticsNodes().isNotEmpty() }
        ui.waitUntil(5_000) {
            ui.onAllNodes(isSelected() and hasAnyAncestor(hasTestTag(barTag)))
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        Thread.sleep(300)
        ui.waitForIdle()
        ui.onNode(isSelected() and hasAnyAncestor(hasTestTag(barTag)))
            .assertIsEnabled()
            .performTouchInput { click() }
        ui.waitUntil(3_000) {
            ui.onAllNodesWithTag("Tab stack fan").fetchSemanticsNodes().isNotEmpty()
        }
        return expandBounds
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
            ui.onNodeWithTag("Tab fan card b1").performTouchInput { click() }
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
    fun bottomFloatingBarAutoHidesAtBottomAndTopEdges() {
        withEdgeWorkspace(TabBarPosition.BOTTOM, initialX = 0.5f, initialY = 1.0f) {
            ui.waitUntil(10_000) {
                ui.onAllNodesWithTag("Floating tab bar").fetchSemanticsNodes().isNotEmpty()
            }
            ui.onNodeWithTag("Floating tab bar").assertIsDisplayed()

            val bottomExpand = awaitHiddenAtEdgeAndReopenFan("Floating tab bar")
            ui.onNodeWithTag("Tab fan card b0").assertIsDisplayed()

            ui.onNodeWithTag("Tab stack fan scrim").performTouchInput {
                click(Offset(left + 24f, top + 24f))
            }
            ui.waitUntil(3_000) {
                ui.onAllNodesWithTag("Tab stack fan").fetchSemanticsNodes().isEmpty()
            }
            ui.waitUntil(8_000) {
                ui.onAllNodesWithTag("Floating tab expand").fetchSemanticsNodes().isNotEmpty()
            }
            ui.onNodeWithTag("Floating tab bar").assertDoesNotExist()

            // Reopen and drag the bottom bar up to the top edge, then verify top-edge auto-hide.
            ui.onNodeWithTag("Floating tab expand").performTouchInput { click() }
            ui.waitUntil(3_000) {
                ui.onAllNodesWithTag("Floating tab bar").fetchSemanticsNodes().isNotEmpty()
            }
            ui.onNodeWithTag("Floating tab handle").performTouchInput {
                down(center)
                advanceEventTime(700)
                moveBy(Offset(0f, -3000f))
                up()
            }
            ui.waitForIdle()
            ui.waitUntil(8_000) {
                ui.onAllNodesWithTag("Floating tab expand").fetchSemanticsNodes().isNotEmpty()
            }
            val topExpand =
                ui.onNodeWithTag("Floating tab expand")
                    .assertIsDisplayed()
                    .fetchSemanticsNode()
                    .boundsInRoot
            ui.onNodeWithTag("Floating tab bar").assertDoesNotExist()
            assertTrue(topExpand.top < bottomExpand.top / 2f)
        }
    }
}

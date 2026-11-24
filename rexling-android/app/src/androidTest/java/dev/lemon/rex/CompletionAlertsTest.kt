package dev.lemon.rex

import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.os.PowerManager
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** A loopback RPC peer owns all fixture data; no real sessions or phones are driven. */
@RunWith(AndroidJUnit4::class)
class CompletionAlertsTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private fun until(message: String, condition: () -> Boolean) {
        val end = System.nanoTime() + 20_000_000_000L
        while (!condition()) {
            assertTrue(message, System.nanoTime() < end)
            Thread.sleep(40)
        }
    }

    private class Peer : AutoCloseable {
        private val state =
            AtomicReference(
                JSONObject()
                    .put("state", "done")
                    .put("msg", "Old completion")
                    .put("updated_at", "old"),
            )
        private val liveTitle = AtomicReference("")
        private val sockets =
            CopyOnWriteArrayList<Pair<WebSocket, java.util.concurrent.atomic.AtomicInteger>>()
        val calls = CopyOnWriteArrayList<String>()
        private val server =
            MockWebServer().apply {
                dispatcher =
                    object : Dispatcher() {
                        override fun dispatch(request: RecordedRequest) =
                            MockResponse()
                                .setHeader("Sec-WebSocket-Protocol", "rex.control.v1")
                                .withWebSocketUpgrade(
                                    object : WebSocketListener() {
                                        override fun onOpen(
                                            webSocket: WebSocket,
                                            response: okhttp3.Response,
                                        ) {
                                            sockets +=
                                                webSocket to
                                                    java.util.concurrent.atomic.AtomicInteger()
                                        }

                                        override fun onMessage(webSocket: WebSocket, text: String) {
                                            val request = JSONObject(text)
                                            if (request.optString("type") == "hello") {
                                                webSocket.send(
                                                    """{"type":"welcome","protocol":"rex.control.v1","api_revision":8,"limits":{"max_message_bytes":1048576},"client":{"id":"fixture","resumed":false}}""",
                                                )
                                                return
                                            }
                                            val method = request.getString("method")
                                            calls += method
                                            val payload =
                                                when (method) {
                                                    "session.attach_all",
                                                    "session.list" ->
                                                        JSONObject(
                                                            """{"sessions":[{"session_id":"s","label":"Test session"}]}""",
                                                        )
                                                    "server.whoami" ->
                                                        JSONObject(
                                                            """{"principal":{"display":"Loopback"}}""",
                                                        )
                                                    "session.view" ->
                                                        JSONObject(
                                                            """{"windows":[{"window_id":"w","label":"Test tab","layers":[{"blocks":[{"block_id":"b","label":"Test pane","creator_name":"com.superlogical.terminal"}]}]}]}""",
                                                        )
                                                    "com.superlogical.terminal.program_status" ->
                                                        JSONObject()
                                                            .put(
                                                                "records",
                                                                JSONArray()
                                                                    .put(
                                                                        JSONObject(
                                                                                state
                                                                                    .get()
                                                                                    .toString(),
                                                                            )
                                                                            .put("id", "")
                                                                            .put(
                                                                                "title",
                                                                                "Fixture task",
                                                                            )
                                                                            .put(
                                                                                "owner",
                                                                                JSONObject()
                                                                                    .put("pid", 1)
                                                                                    .put(
                                                                                        "name",
                                                                                        "codex",
                                                                                    ),
                                                                            ),
                                                                    ),
                                                            )
                                                    "com.superlogical.terminal.title" ->
                                                        JSONObject()
                                                            .put("title", liveTitle.get())
                                                            .put(
                                                                "owner",
                                                                JSONObject()
                                                                    .put("pid", 1)
                                                                    .put("name", "codex"),
                                                            )
                                                    "com.superlogical.terminal.process" ->
                                                        JSONObject(
                                                            """{"foreground":{"pid":1,"name":"codex"}}""",
                                                        )
                                                    else -> JSONObject()
                                                }
                                            webSocket.send(
                                                JSONObject()
                                                    .put("type", "response")
                                                    .put("id", request.getString("id"))
                                                    .put("payload", payload)
                                                    .toString(),
                                            )
                                        }

                                        override fun onClosed(
                                            webSocket: WebSocket,
                                            code: Int,
                                            reason: String,
                                        ) {
                                            sockets.removeAll { it.first === webSocket }
                                        }
                                    },
                                )
                    }
                start(java.net.InetAddress.getByName("127.0.0.1"), 0)
            }
        val endpoint
            get() = server.url("/").toString().trimEnd('/')

        fun report(status: String, version: String) {
            state.set(
                JSONObject().put("state", status).put("msg", version).put("updated_at", version),
            )
            changed("program_status_changed", JSONObject())
        }

        fun title(value: String) {
            liveTitle.set(value)
            changed(
                "title_changed",
                JSONObject()
                    .put("title", value)
                    .put("owner", JSONObject().put("pid", 1).put("name", "codex")),
            )
        }

        private fun changed(name: String, payload: JSONObject) {
            sockets.forEach { (socket, seq) ->
                socket.send(
                    JSONObject()
                        .put("type", "event")
                        .put("seq", seq.incrementAndGet())
                        .put(
                            "payload",
                            JSONObject()
                                .put("type", "session_event")
                                .put(
                                    "payload",
                                    JSONObject()
                                        .put("session_id", "s")
                                        .put(
                                            "event",
                                            JSONObject()
                                                .put("type", "block_event")
                                                .put(
                                                    "payload",
                                                    JSONObject()
                                                        .put("block_id", "b")
                                                        .put(
                                                            "creator_name",
                                                            "com.superlogical.terminal",
                                                        )
                                                        .put("name", name)
                                                        .put("payload", payload),
                                                ),
                                        ),
                                ),
                        )
                        .toString(),
                )
            }
        }

        override fun close() {
            sockets.forEach { runCatching { it.first.close(1000, "Fixture finished") } }
            server.shutdown()
        }
    }

    @Test
    fun newCompletionsNotifyInBackgroundAndScreenOffWithoutReplayingHistory() {
        val prefs = BackgroundAlerts.preferences(context)
        val original = prefs.all.toMap()
        val manager = context.getSystemService(NotificationManager::class.java)
        var scenario: ActivityScenario<MainActivity>? = null
        Peer().use { peer ->
            fun ledger() = prefs.getString("attention_ledger", "").orEmpty()
            fun alerts() =
                manager.activeNotifications.filter {
                    it.id == BackgroundMonitorService.ATTENTION_ID
                }
            try {
                context.stopService(Intent(context, BackgroundMonitorService::class.java))
                Thread.sleep(250)
                prefs.edit().clear().commit()
                instrumentation.uiAutomation
                    .executeShellCommand(
                        "pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS",
                    )
                    .close()
                scenario = ActivityScenario.launch(MainActivity::class.java)
                val profile = ServerProfile.create("Loopback", peer.endpoint, "completion-fixture")
                prefs
                    .edit()
                    .putString("servers", SavedServers.encode(listOf(profile)))
                    .putStringSet("connected_servers", setOf(profile.id))
                    .putBoolean(BackgroundAlerts.ENABLED, true)
                    .commit()
                scenario!!.onActivity { BackgroundAlerts.reconcile(it) }
                until("Historical completion not baselined") { ledger().contains("done:") }
                assertTrue(alerts().isEmpty())
                peer.report("working", "foreground work")
                until("Foreground work missing") { ledger().contains("state:working") }
                peer.report("done", "foreground result")
                until("Foreground result missing") { ledger().contains("done:") }
                assertTrue(alerts().isEmpty())
                scenario!!.moveToState(Lifecycle.State.CREATED)
                peer.report("working", "background work")
                until("Background work missing") { ledger().contains("state:working") }
                peer.report("done", "background result")
                until("Finished notification missing") { alerts().size == 1 }
                val first = alerts().single()
                assertTrue(
                    first.notification.extras
                        .getString(Notification.EXTRA_TITLE)!!
                        .startsWith(appString(R.string.filter_finished)),
                )
                assertEquals(Notification.VISIBILITY_PRIVATE, first.notification.visibility)
                assertEquals(
                    appString(R.string.notification_task_finished),
                    first.notification.publicVersion.extras.getString(Notification.EXTRA_TITLE),
                )
                peer.report("idle", "report cleared")
                until("Idle report missing") { ledger().contains("state:idle") }
                assertEquals(
                    "Completion must remain readable after its report clears",
                    first.postTime,
                    alerts().single().postTime,
                )
                scenario!!.moveToState(Lifecycle.State.RESUMED)
                until("Foreground must clear alerts") { alerts().isEmpty() }
                instrumentation.uiAutomation
                    .executeShellCommand("input keyevent KEYCODE_SLEEP")
                    .use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() }
                until("Screen did not sleep") {
                    !context.getSystemService(PowerManager::class.java).isInteractive
                }
                peer.report("working", "locked work")
                until("Locked work missing") { ledger().contains("state:working") }
                peer.report("done", "locked result")
                until("Screen-off completion notification missing") { alerts().size == 1 }
                val locked = alerts().single()
                peer.report("done", "locked result")
                Thread.sleep(1500)
                assertEquals(
                    "Unchanged completion reposted",
                    locked.postTime,
                    alerts().single().postTime,
                )
                instrumentation.uiAutomation
                    .executeShellCommand("input keyevent KEYCODE_WAKEUP")
                    .close()
                scenario!!.moveToState(Lifecycle.State.RESUMED)
                until("Clear old completion") { alerts().isEmpty() }
                peer.report("working", "codex question")
                until("Codex work missing") { ledger().contains("state:working") }
                scenario!!.moveToState(Lifecycle.State.CREATED)
                peer.title("[!] Action Required | Fixture task")
                Thread.sleep(1500)
                assertTrue("Experimental fallback must default off", alerts().isEmpty())
                prefs.edit().putBoolean(CodexAttentionPolicy.ENABLED, true).commit()
                until("Codex title attention did not alert") { alerts().size == 1 }
                val question = alerts().single()
                assertTrue(
                    question.notification.extras
                        .getString(Notification.EXTRA_TITLE)!!
                        .startsWith(appString(R.string.filter_attention)),
                )
                peer.title("[ . ] Action Required | Fixture task")
                Thread.sleep(1200)
                assertEquals(
                    "Animated attention title must not repost",
                    question.postTime,
                    alerts().single().postTime,
                )
                peer.title("⠋ Fixture task")
                until("Resuming Codex must clear its alert") { alerts().isEmpty() }
                peer.title("[!] Action Required | Fixture task")
                until("New Codex question must alert again") { alerts().size == 1 }
                assertTrue(alerts().single().postTime > question.postTime)
                prefs.edit().putBoolean(CodexAttentionPolicy.ENABLED, false).commit()
                until("Disabling experiment must restore Working") { alerts().isEmpty() }
                assertTrue(
                    "Monitor must never claim, resize, or type",
                    peer.calls.none {
                        it.contains("write") ||
                            it.contains("resize") ||
                            it.contains("take") ||
                            it == "session.attach"
                    },
                )
            } finally {
                instrumentation.uiAutomation
                    .executeShellCommand("input keyevent KEYCODE_WAKEUP")
                    .close()
                scenario?.close()
                context.stopService(Intent(context, BackgroundMonitorService::class.java))
                Thread.sleep(300)
                val editor = prefs.edit().clear()
                original.forEach { (key, value) ->
                    when (value) {
                        is String -> editor.putString(key, value)
                        is Boolean -> editor.putBoolean(key, value)
                        is Int -> editor.putInt(key, value)
                        is Long -> editor.putLong(key, value)
                        is Float -> editor.putFloat(key, value)
                        is Set<*> ->
                            editor.putStringSet(key, value.filterIsInstance<String>().toSet())
                    }
                }
                editor.commit()
            }
        }
    }
}

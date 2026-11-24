package dev.lemon.rex

import android.app.NotificationManager
import android.content.Intent
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackgroundAlertsTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private fun until(message: String, condition: () -> Boolean) {
        val end = System.nanoTime() + 25_000_000_000L
        while (!condition()) {
            assertTrue(message, System.nanoTime() < end)
            Thread.sleep(50)
        }
    }

    @Test
    fun notificationTargetsAcceptOnlyPrivateOneUseTokens() {
        val name = "alert-target-test-${System.nanoTime()}"
        val prefs = context.getSharedPreferences(name, 0)
        try {
            val store = AlertDestinations(prefs)
            val target =
                AlertDestination(
                    "server",
                    "https://fixture.example",
                    "session",
                    "block",
                    "key",
                    "version",
                )
            assertNull(store.consume(android.net.Uri.parse("rexlink-alert://open/untrusted")))
            val uri = store.issue(target)
            assertEquals(target, store.consume(uri))
            assertNull(store.consume(uri))
            assertNull(store.consume(android.net.Uri.parse("https://open/$uri")))
        } finally {
            context.deleteSharedPreferences(name)
        }
    }

    @Test
    fun backgroundRequestsDeduplicateResolveAndOpenExactPaneWhileStopEndsMonitoring() {
        val args = InstrumentationRegistry.getArguments()
        val endpoint = args.getString("monitor_server")
        val session = args.getString("monitor_session")
        val block = args.getString("monitor_block")
        assumeTrue(
            "Requires an explicitly owned disposable notification fixture",
            endpoint != null && session != null && block != null,
        )
        val prefs = BackgroundAlerts.preferences(context)
        val original = prefs.all.toMap()
        val manager = context.getSystemService(NotificationManager::class.java)
        var scenario: ActivityScenario<MainActivity>? = null
        var writer = 0L
        fun state() = JSONObject(NativeBridge.poll(writer, 0).ifBlank { "{}" })
        fun command(type: String, vararg fields: Pair<String, Any>) {
            val value = JSONObject().put("type", type)
            fields.forEach { value.put(it.first, it.second) }
            NativeBridge.command(writer, value.toString())
        }
        fun report(): JSONObject? {
            val array = state().optJSONArray("activity") ?: return null
            return (0 until array.length())
                .map { array.getJSONObject(it) }
                .firstOrNull {
                    it.optString("session") == session &&
                        it.optString("block") == block &&
                        it.optString("id").isEmpty()
                }
        }
        fun send(text: String) {
            command("take")
            until("Writer did not control owned fixture") { state().optBoolean("controlled") }
            command("input", "text" to "$text\n")
        }
        fun alerts() =
            manager.activeNotifications.filter { it.id == BackgroundMonitorService.ATTENTION_ID }
        try {
            context.stopService(Intent(context, BackgroundMonitorService::class.java))
            instrumentation.uiAutomation
                .executeShellCommand(
                    "pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS",
                )
                .close()
            val profile = ServerProfile.create("Monitor fixture", endpoint!!, "monitor-fixture")
            prefs
                .edit()
                .clear()
                .putString("servers", SavedServers.encode(listOf(profile)))
                .putString("active_server", profile.id)
                .putStringSet("connected_servers", setOf(profile.id))
                .putBoolean(BackgroundAlerts.ENABLED, true)
                .putString("current_session:$endpoint", session)
                .commit()
            scenario = ActivityScenario.launch(MainActivity::class.java)
            until("Ongoing monitor missing") {
                manager.activeNotifications.any { it.id == BackgroundMonitorService.ONGOING_ID }
            }
            lateinit var originalActivity: MainActivity
            scenario.onActivity { originalActivity = it }
            manager.activeNotifications
                .single { it.id == BackgroundMonitorService.ONGOING_ID }
                .notification
                .contentIntent
                .send()
            Thread.sleep(300)
            instrumentation.waitForIdleSync()
            scenario.onActivity {
                assertSame(
                    "Ongoing notification must reuse the existing workspace",
                    originalActivity,
                    it,
                )
            }
            writer = NativeBridge.create()
            command("connect", "server" to endpoint)
            until("Writer connection") { state().optString("phase") == "connected" }
            command("session", "id" to session!!, "block" to block!!)
            until("Writer selected fixture") {
                state().optString("selectedBlock") == block && !state().isNull("screen")
            }
            command("activity", "open" to true)
            // Foreground requests are consumed, not postponed until the next background transition.
            send("block1")
            until("Foreground report") { report()?.optString("state") == "blocked" }
            until("Foreground ledger") {
                prefs
                    .getString("attention_ledger", "")!!
                    .contains(report()!!.getString("attentionVersion"))
            }
            assertTrue(alerts().isEmpty())
            scenario.moveToState(Lifecycle.State.CREATED)
            send("work")
            until("Working report") { report()?.optString("state") == "working" }
            Thread.sleep(1400)
            send("block2")
            until("Background alert missing") { alerts().size == 1 }
            val first = alerts().single()
            assertEquals(android.app.Notification.VISIBILITY_PRIVATE, first.notification.visibility)
            assertNotNull(first.notification.publicVersion)
            assertTrue(
                state().optBoolean("controlled"),
            ) // Service did not claim or resize the fixture.
            send("title")
            Thread.sleep(1800)
            assertEquals(
                "Animated titles must not repost",
                first.postTime,
                alerts().single().postTime,
            )
            send("work")
            until("Resolved request was not removed") { alerts().isEmpty() }
            instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_SLEEP").use {
                android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes()
            }
            until("Screen did not turn off") {
                !context.getSystemService(android.os.PowerManager::class.java).isInteractive
            }
            send("block3")
            until("Screen-off request missing") { alerts().size == 1 }
            instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_WAKEUP").use {
                android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes()
            }
            alerts().single().notification.contentIntent.send()
            until("Notification did not open the exact session") {
                BackgroundAlerts.foreground.value &&
                    prefs.getString("current_session:$endpoint", "") == session
            }
            until("Foreground did not clear attention alerts") { alerts().isEmpty() }
            scenario.moveToState(Lifecycle.State.RESUMED)
            scenario.moveToState(Lifecycle.State.CREATED)
            Thread.sleep(1500)
            assertTrue("Same report must not alert again", alerts().isEmpty())
            val ongoing =
                manager.activeNotifications.single { it.id == BackgroundMonitorService.ONGOING_ID }
            ongoing.notification.actions.single().actionIntent.send()
            until("Stop did not disable monitoring") {
                !prefs.getBoolean(BackgroundAlerts.ENABLED, true) &&
                    manager.activeNotifications.none {
                        it.id == BackgroundMonitorService.ONGOING_ID
                    }
            }
        } finally {
            instrumentation.uiAutomation
                .executeShellCommand("input keyevent KEYCODE_WAKEUP")
                .close()
            runCatching { scenario?.close() }
            if (writer != 0L) NativeBridge.destroy(writer)
            context.stopService(Intent(context, BackgroundMonitorService::class.java))
            Thread.sleep(400)
            val editor = prefs.edit().clear()
            original.forEach { (key, value) ->
                when (value) {
                    is String -> editor.putString(key, value)
                    is Boolean -> editor.putBoolean(key, value)
                    is Int -> editor.putInt(key, value)
                    is Long -> editor.putLong(key, value)
                    is Float -> editor.putFloat(key, value)
                    is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
                }
            }
            editor.commit()
        }
    }
}

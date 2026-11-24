package dev.lemon.rex

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class ForwardCoordinatorTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private class Commands(context: Context) : ContextWrapper(context) {
        val sent = mutableListOf<Intent>()

        override fun startForegroundService(intent: Intent): ComponentName {
            sent += intent
            return ComponentName(packageName, "FakeForwardService")
        }

        override fun startService(intent: Intent): ComponentName {
            sent += intent
            return ComponentName(packageName, "FakeForwardService")
        }
    }

    @Test
    fun collisionsRespectThePreferenceAndNeverStopAnUnrelatedServer() {
        instrumentation.runOnMainSync {
            val context = Commands(instrumentation.targetContext)
            val original = PortForwards.items.toList()
            val preference = PortForwards.policy
            val server = ServerProfile.create("Second host", "https://second.example", "second")
            fun reset(policy: PortConflictPolicy) {
                context.sent.clear()
                PortForwards.items.clear()
                PortForwards.cancelConflict()
                PortForwards.dismissLinkNotice()
                PortForwards.items +=
                    PortForward(
                        "first",
                        "First host",
                        "127.0.0.1",
                        3000,
                        "active",
                        "127.0.0.1:3000",
                        serverId = "first",
                        endpoint = "https://first.example",
                        session = "s1",
                        localPort = 3000,
                    )
                PortForwards.items +=
                    PortForward(
                        "unrelated",
                        "Third host",
                        "127.0.0.1",
                        4000,
                        "active",
                        "127.0.0.1:4000",
                        serverId = "third",
                        endpoint = "https://third.example",
                        session = "s3",
                        localPort = 4000,
                    )
                PortForwards.changePolicy(policy)
            }
            try {
                reset(PortConflictPolicy.ASK)
                val id = PortForwards.start(context, server, "s2", "127.0.0.1", 3000, 3000)
                assertEquals("first", PortForwards.conflict!!.owner)
                assertTrue(context.sent.isEmpty())
                PortForwards.resolve(context, true)
                assertEquals(
                    listOf(PortForwardService.CLOSE, PortForwardService.ADD),
                    context.sent.map { it.action },
                )
                assertEquals("first", context.sent.first().getStringExtra("id"))
                assertEquals(id, context.sent.last().getStringExtra("id"))
                assertTrue(PortForwards.items.single { it.id == "unrelated" }.running)
                assertEquals(
                    "https://second.example",
                    PortForwards.items.single { it.id == id }.endpoint,
                )
                reset(PortConflictPolicy.AUTO)
                val automatic = PortForwards.start(context, server, "s2", "127.0.0.1", 3000, 3000)
                assertEquals(0, PortForwards.items.single { it.id == automatic }.localPort)
                assertTrue(PortForwards.items.single { it.id == "first" }.running)
                assertEquals(listOf(PortForwardService.ADD), context.sent.map { it.action })
                val count = context.sent.size
                PortForwards.openLink(
                    context,
                    server,
                    "s2",
                    LoopbackLink.parse("http://localhost:3000/path?q=1")!!,
                )
                assertEquals("Repeated taps reuse the live destination", count, context.sent.size)
                assertEquals(1, PortForwards.items.count { it.endpoint == server.endpoint })
                reset(PortConflictPolicy.REPLACE)
                PortForwards.start(context, server, "s2", "127.0.0.1", 3000, 3000)
                assertEquals(
                    listOf(PortForwardService.CLOSE, PortForwardService.ADD),
                    context.sent.map { it.action },
                )
                // If another app owns a port, replacement must ask instead of stopping anything.
                reset(PortConflictPolicy.REPLACE)
                val external = PortForwards.start(context, server, "s2", "127.0.0.1", 5000, 5000)
                context.sent.clear()
                PortForwards.portBusy(context, external)
                assertNull(PortForwards.conflict!!.owner)
                assertTrue(context.sent.isEmpty())
                PortForwards.resolve(context, false)
                assertEquals(0, PortForwards.items.single { it.id == external }.localPort)
            } finally {
                PortForwards.cancelConflict()
                PortForwards.dismissLinkNotice()
                PortForwards.items.clear()
                PortForwards.items.addAll(original)
                PortForwards.changePolicy(preference)
                PortForwards.persist()
            }
        }
    }

    @Test
    fun restoredForwardsAreStoppedAndKeepTheirServerAndPhonePort() {
        instrumentation.runOnMainSync {
            val context = instrumentation.targetContext
            val original = PortForwards.items.toList()
            try {
                PortForwards.items.clear()
                PortForwards.items +=
                    PortForward(
                        "saved",
                        "Host",
                        "127.0.0.1",
                        5173,
                        "active",
                        "127.0.0.1:5173",
                        serverId = "host",
                        endpoint = "https://host.example",
                        session = "session",
                        localPort = 5173,
                    )
                PortForwards.persist()
                PortForwards.items.clear()
                PortForwards.initialize(context)
                val restored = PortForwards.items.single()
                assertEquals("stopped", restored.phase)
                assertEquals("host", restored.serverId)
                assertEquals(5173, restored.localPort)
                assertEquals("", restored.address)
            } finally {
                PortForwards.items.clear()
                PortForwards.items.addAll(original)
                PortForwards.persist()
            }
        }
    }
}

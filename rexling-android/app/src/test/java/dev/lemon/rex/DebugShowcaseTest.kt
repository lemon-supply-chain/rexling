package dev.lemon.rex

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugShowcaseTest {
    @Test
    fun showcaseSeedsRunningCountdownFloatingAndActivityStates() {
        assertEquals(DebugShowcase.ENDPOINT, DebugShowcase.Profile.endpoint)
        val states = mutableListOf<AppState>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val session =
            DebugShowcaseSession(
                scope = scope,
                endpoint = DebugShowcase.ENDPOINT,
                tickIntervalMs = 60_000L,
                tickerDispatcher = Dispatchers.Default,
                onState = { states += it },
            )
        try {
            session.start(active = true)
            val initial = states.last()
            assertEquals("connected", initial.phase)
            assertTrue(initial.controlled)
            assertEquals(DebugShowcase.ENDPOINT, initial.endpoint)
            assertEquals("Showcase", initial.identity)
            assertEquals("Showcase mode", initial.notice)
            assertEquals("demo-workspace", initial.selectedSession)
            assertEquals("p-running", initial.selectedBlock)
            assertEquals(2, initial.sessions.size)
            assertEquals(4, initial.windows.size)
            assertTrue("p-running" in initial.workingBlocks)
            assertTrue("p-build" in initial.workingBlocks)
            assertEquals(TerminalProgress("set", 100), initial.terminalProgress["p-countdown"])
            assertEquals(TerminalProgress("set", 70), initial.terminalProgress["p-loop-dial"])
            assertEquals(
                TerminalProgress("indeterminate", null),
                initial.terminalProgress["p-indeterminate"],
            )
            assertEquals(TerminalProgress("pause", 64), initial.terminalProgress["p-paused-sync"])
            assertEquals(TerminalProgress("error", 42), initial.terminalProgress["p-error"])

            val agentsTab = initial.windows.first { it.id == "w-agents" }
            assertTrue(
                agentsTab.layout.any { it.id == "p-floating" && it.floating && !it.minimized },
            )
            assertEquals(2, agentsTab.splits.size)

            val activityStates = initial.activity.map { it.state }.toSet()
            assertTrue(ActivityState.WORKING in activityStates)
            assertTrue(ActivityState.BLOCKED in activityStates)
            assertTrue(ActivityState.DONE in activityStates)
            assertTrue(ActivityState.ERROR in activityStates)
            assertTrue(initial.attentionCount > 0)
            assertNotNull(initial.screen)
        } finally {
            session.close()
            scope.cancel()
        }
    }

    @Test
    fun tickLoopsCountdownContinuouslyAndUpdatesRunningScreen() {
        val states = mutableListOf<AppState>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val session =
            DebugShowcaseSession(
                scope = scope,
                endpoint = DebugShowcase.ENDPOINT,
                tickIntervalMs = 60_000L,
                tickerDispatcher = Dispatchers.Default,
                onState = { states += it },
            )
        try {
            session.start(active = true)
            val firstRunningScreen = states.last().screen
            val percentages = mutableListOf(states.last().terminalProgress["p-countdown"]?.percent)

            repeat(51) {
                session.tick()
                val progress = states.last().terminalProgress["p-countdown"]
                assertNotNull(progress)
                assertEquals("set", progress!!.state)
                percentages += progress.percent
            }

            assertEquals(100, percentages.first())
            assertTrue(0 in percentages)
            assertEquals(100, percentages.last())
            assertNotEquals(firstRunningScreen, states.last().screen)
        } finally {
            session.close()
            scope.cancel()
        }
    }

    @Test
    fun interactiveCommandsUpdateNavigationLayoutInputAndLifecycle() {
        val states = mutableListOf<AppState>()
        val previewFrames = mutableListOf<Map<String, TerminalScreen>>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val session =
            DebugShowcaseSession(
                scope = scope,
                endpoint = DebugShowcase.ENDPOINT,
                tickIntervalMs = 60_000L,
                tickerDispatcher = Dispatchers.Default,
                onState = { states += it },
                onPaneFrames = { previewFrames += it },
            )
        try {
            session.start(active = true)

            assertTrue(
                session.command(
                    "block",
                    JSONObject().put("id", "p-shell").put("navigationId", 7L),
                ),
            )
            val shellState = states.last()
            assertEquals("p-shell", shellState.selectedBlock)
            assertEquals(7L, shellState.navigationId)
            assertEquals(0L, shellState.screen!!.scroll.below)

            assertTrue(
                session.command(
                    "scroll",
                    JSONObject().put("block", "p-shell").put("lines", -10),
                ),
            )
            assertTrue(states.last().screen!!.scroll.below > 0L)

            assertTrue(
                session.command(
                    "input",
                    JSONObject().put("block", "p-shell").put("text", "status\r"),
                ),
            )
            val afterInput = states.last().screen!!
            assertEquals(0L, afterInput.scroll.below)
            assertTrue(afterInput.plainText().contains("phase=connected"))

            assertTrue(
                session.command(
                    "peek",
                    JSONObject().put("ids", JSONArray(listOf("p-countdown", "p-running"))),
                ),
            )
            assertTrue(previewFrames.last().containsKey("p-countdown"))
            assertTrue(previewFrames.last().containsKey("p-running"))

            assertTrue(session.command("new_tab", JSONObject().put("session", "demo-workspace")))
            val createdTabId = states.last().windows.last().id
            val createdPaneId = states.last().selectedBlock

            assertTrue(
                session.command(
                    "move_tab",
                    JSONObject()
                        .put("session", "demo-workspace")
                        .put("id", createdTabId)
                        .put("before", "w-agents")
                        .put("requestId", 4L),
                ),
            )
            assertEquals(createdTabId, states.last().windows.first().id)
            assertEquals(4L, states.last().tabMoveId)

            assertTrue(
                session.command(
                    "new_pane",
                    JSONObject()
                        .put("session", "demo-workspace")
                        .put("anchor", createdPaneId)
                        .put("direction", "horizontal"),
                ),
            )
            val secondPaneId = states.last().selectedBlock
            val updatedTab = states.last().windows.first { it.id == createdTabId }
            assertEquals(listOf(createdPaneId, secondPaneId), updatedTab.blocks.map { it.id })

            assertTrue(
                session.command(
                    "move_pane",
                    JSONObject()
                        .put("session", "demo-workspace")
                        .put("window", createdTabId)
                        .put("id", secondPaneId)
                        .put("before", createdPaneId)
                        .put("requestId", 9L),
                ),
            )
            assertEquals(
                listOf(secondPaneId, createdPaneId),
                states.last().windows.first { it.id == createdTabId }.blocks.map { it.id },
            )
            assertEquals(9L, states.last().paneMoveId)

            assertTrue(
                session.command(
                    "resize_split",
                    JSONObject()
                        .put("session", "demo-workspace")
                        .put("direction", "horizontal")
                        .put("before", createdPaneId)
                        .put("after", secondPaneId)
                        .put("ratio", 0.35),
                ),
            )
            val splitRatio =
                states.last().windows.first { it.id == createdTabId }.splits.first().ratio
            assertEquals(0.35f, splitRatio, 0.01f)

            assertTrue(
                session.command(
                    "zoom",
                    JSONObject()
                        .put("session", "demo-workspace")
                        .put("block", secondPaneId)
                        .put("zoom", true),
                ),
            )
            assertEquals(
                secondPaneId,
                states.last().windows.first { it.id == createdTabId }.zoomed,
            )

            assertTrue(
                session.command(
                    "minimize_floating",
                    JSONObject().put("session", "demo-workspace").put("id", "p-floating"),
                ),
            )
            assertTrue(
                states
                    .last()
                    .windows
                    .first { it.id == "w-agents" }
                    .layout
                    .first { it.id == "p-floating" }
                    .minimized,
            )

            assertTrue(
                session.command(
                    "resize_floating",
                    JSONObject()
                        .put("session", "demo-workspace")
                        .put("window", "w-agents")
                        .put("id", "p-floating")
                        .put("bounds", JSONArray(listOf(0.1, 0.1, 0.6, 0.5))),
                ),
            )
            val resizedFloating =
                states
                    .last()
                    .windows
                    .first { it.id == "w-agents" }
                    .layout
                    .first { it.id == "p-floating" }
            assertEquals(0.1f, resizedFloating.x, 0.001f)
            assertEquals(0.6f, resizedFloating.width, 0.001f)

            assertTrue(session.command("pane_catalog", JSONObject().put("open", true)))
            assertTrue(states.last().paneCatalog!!.items.isNotEmpty())

            assertTrue(
                session.command(
                    "session",
                    JSONObject().put("id", "demo-lab").put("navigationId", 11L),
                ),
            )
            assertEquals("demo-lab", states.last().selectedSession)
            assertEquals(11L, states.last().navigationId)

            assertTrue(
                session.command(
                    "close_pane",
                    JSONObject().put("session", "demo-workspace").put("id", secondPaneId),
                ),
            )
            assertTrue(
                states.last().sessions.any { it.id == "demo-workspace" },
            )

            assertTrue(session.command("pause", JSONObject()))
            assertEquals("paused", states.last().phase)
            assertFalse(states.last().controlled)

            assertTrue(session.command("resume", JSONObject()))
            assertEquals("connected", states.last().phase)
            assertTrue(states.last().controlled)

            assertTrue(session.command("disconnect", JSONObject()))
            assertEquals("disconnected", states.last().phase)
            assertNull(states.last().screen)
            assertTrue(states.last().sessions.isEmpty())
        } finally {
            session.close()
            scope.cancel()
        }
    }
}

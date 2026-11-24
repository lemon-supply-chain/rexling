package dev.lemon.rex

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StartupTabsTest {
    @get:Rule val ui = createComposeRule()
    private val codex = ProgramIdentity.resolve(listOf("codex"))

    private fun state(program: ProgramIdentity? = null, title: String? = null) =
        AppState(
            phase = "connected",
            selectedSession = "s",
            selectedBlock = "p",
            windows = listOf(WindowItem("w", "window-123abc", listOf(BlockItem("p", "shell")))),
            programs = program?.let { mapOf("p" to it) }.orEmpty(),
            titles = title?.let { mapOf("p" to it) }.orEmpty(),
        )

    @Test
    fun initialTabsWaitForContentWithoutMovingActionsOrFlickeringDuringRefresh() {
        val cache = WorkspacePresentation()
        val chrome = mutableStateOf(cache.present(state()))
        val ready = mutableStateOf(false)
        val session = mutableStateOf("s")
        ui.setContent {
            RexTheme(false, ThemeCatalog.defaults[1]) {
                Box(Modifier.width(360.dp)) {
                    TerminalTabs(
                        chrome.value.windows,
                        "w",
                        true,
                        {},
                        panePrograms = { w ->
                            w.blocks.map { chrome.value.programs.getValue(it.id) }
                        },
                        selectedPane = { 0 },
                        paneTitle = { w, p -> chrome.value.titles[w.blocks[p].id].orEmpty() },
                        reorderKey = session.value,
                        contentReady = ready.value,
                        actions = { TerminalActions(true, true, {}, {}) },
                    )
                }
            }
        }
        ui.onNodeWithContentDescription("Tab Loading…, Loading…").assertDoesNotExist()
        ui.onNodeWithContentDescription("Tabs").assertDoesNotExist()
        val actions = ui.onNodeWithContentDescription("Activity").fetchSemanticsNode().boundsInRoot
        ui.onAllNodes(hasContentDescription("shell", substring = true)).assertCountEquals(0)
        ui.onAllNodes(hasText("window-123abc", substring = true)).assertCountEquals(0)
        ui.runOnIdle {
            val fresh = state(codex, "Investigate protocol")
            cache.observe(fresh)
            chrome.value = cache.present(fresh)
            ready.value = true
        }
        ui.onNodeWithContentDescription("Tab Investigate protocol, Codex").assertIsDisplayed()
        ui.onAllNodes(hasText("Loading…")).assertCountEquals(0)
        assertEquals(
            actions,
            ui.onNodeWithContentDescription("Activity").fetchSemanticsNode().boundsInRoot,
        )
        ui.runOnIdle { ready.value = false }
        ui.onNodeWithContentDescription("Tab Investigate protocol, Codex").assertIsDisplayed()
        ui.runOnIdle { session.value = "another-session" }
        ui.onNodeWithContentDescription("Tabs").assertDoesNotExist()
        assertEquals(
            actions,
            ui.onNodeWithContentDescription("Activity").fetchSemanticsNode().boundsInRoot,
        )
    }

    @Test
    fun savedMetadataSurvivesRecreationIsEndpointScopedAndFlushesLatestTitle() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("startup-tabs-test", 0)
        prefs.edit().clear().commit()
        try {
            val first = SavedWorkspacePresentation(prefs, "server-one")
            first.chrome(state(codex, "First task"))
            assertEquals(
                codex,
                SavedWorkspacePresentation(prefs, "server-one").chrome(state()).programs["p"],
            )
            assertEquals(
                ProgramIdentity.Loading,
                SavedWorkspacePresentation(prefs, "server-two").chrome(state()).programs["p"],
            )
            first.chrome(state(codex, "Final task ·"))
            first.flush()
            val chrome = SavedWorkspacePresentation(prefs, "server-one").chrome(state())
            assertEquals("Final task ·", chrome.titles["p"])
            assertEquals(setOf("p"), chrome.cachedPrograms)
        } finally {
            prefs.edit().clear().commit()
        }
    }
}

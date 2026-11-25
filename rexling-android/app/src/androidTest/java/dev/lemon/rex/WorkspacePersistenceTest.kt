package dev.lemon.rex

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WorkspacePersistenceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val tabs =
        listOf(
            WindowItem("editor", "Editor", listOf(BlockItem("a", "Shell"), BlockItem("b", "Vim"))),
            WindowItem("logs", "Logs", listOf(BlockItem("c", "Logs"))),
        )

    private fun state(session: String = "s", block: String = "a") =
        AppState(
            phase = "connected",
            sessions = listOf(SessionItem("s", "Session"), SessionItem("other", "Other")),
            selectedSession = session,
            selectedBlock = block,
            windows = tabs,
        )

    @Test
    fun recreationRestoresLastTabAndPaneIndependentlyForEachServer() {
        val name = "workspace-history-${System.nanoTime()}"
        val prefs = context.getSharedPreferences(name, 0)
        try {
            val mac = SavedWorkspace(prefs, "https://mac")
            mac.remember("s", tabs, "b")
            mac.remember("s", tabs, "c")
            val linux = SavedWorkspace(prefs, "https://linux")
            linux.remember("s", tabs, "a")
            assertTrue(prefs.edit().commit())
            assertTrue(
                File(context.applicationInfo.dataDir, "shared_prefs/$name.xml")
                    .readText()
                    .contains("workspace_navigation:"),
            )
            val restored = SavedWorkspace(prefs, "https://mac")
            assertEquals(WorkspaceTarget("s", "c"), restored.target(state()))
            assertEquals("b", restored.navigation.pane("s", tabs[0]))
            assertNull(SavedWorkspace(prefs, "https://linux").target(state()))
            assertEquals("c", restored.navigation.pane("s", tabs))
        } finally {
            context.deleteSharedPreferences(name)
        }
    }

    @Test
    fun pendingNavigationIsDurableAndServerDefaultCannotOverwriteItDuringPlanning() {
        val name = "workspace-pending-${System.nanoTime()}"
        val prefs = context.getSharedPreferences(name, 0)
        try {
            val saved = SavedWorkspace(prefs, "https://mac")
            saved.remember("s", tabs, "a")
            saved.request("other", "b")
            val raw = prefs.getString("workspace_navigation:https://mac", null)
            val recreated = SavedWorkspace(prefs, "https://mac")
            assertEquals(WorkspaceTarget("other"), recreated.target(state()))
            assertEquals(WorkspaceTarget("other", "b"), recreated.target(state(session = "other")))
            assertEquals(raw, prefs.getString("workspace_navigation:https://mac", null))
            assertEquals("other", recreated.session)
        } finally {
            context.deleteSharedPreferences(name)
        }
    }

    @Test
    fun missingSavedPaneFallsBackAndTheFallbackIsSavedForNextTime() {
        val name = "workspace-closed-${System.nanoTime()}"
        val prefs = context.getSharedPreferences(name, 0)
        try {
            SavedWorkspace(prefs, "https://mac").remember("s", tabs, "b")
            val recreated = SavedWorkspace(prefs, "https://mac")
            val current = state(block = "").copy(windows = listOf(tabs[1]))
            assertEquals(WorkspaceTarget("s", "c"), recreated.target(current))
            recreated.remember("s", current.windows, "c")
            assertNull(
                SavedWorkspace(prefs, "https://mac").target(current.copy(selectedBlock = "c")),
            )
        } finally {
            context.deleteSharedPreferences(name)
        }
    }
}

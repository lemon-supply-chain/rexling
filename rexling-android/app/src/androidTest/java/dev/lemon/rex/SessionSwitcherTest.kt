package dev.lemon.rex

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SessionSwitcherTest {
    @get:Rule val ui = createComposeRule()
    private val mac = ServerProfile.create("Shulhi MBP", "https://fixture.example", "mac")
    private val linux = ServerProfile.create("Ubuntu", "https://linux.example", "linux")
    private val targets =
        listOf(
            SessionTarget(
                mac,
                SessionItem("s", "Rexling development"),
                SessionDetail("this device", 2, 4),
            ),
            SessionTarget(
                mac,
                SessionItem("other", "ShipPDF"),
                SessionDetail("attached on macOS", 2, 3),
            ),
            SessionTarget(
                linux,
                SessionItem("s", "Ubuntu server"),
                SessionDetail("this device", 1, 1),
            ),
        )

    private fun capture(name: String) {
        ui.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        Thread.sleep(300)
        File(instrumentation.targetContext.cacheDir, name).outputStream().use {
            instrumentation.uiAutomation
                .takeScreenshot()
                .compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    /** The unified switcher opened on its Sessions page, as the header's session strip opens it. */
    @androidx.compose.runtime.Composable
    private fun SessionsDrawer(
        targets: List<SessionTarget>,
        current: String,
        last: String,
        onRename: (SessionTarget, String) -> Boolean,
        onSelect: (String) -> Unit,
        onClose: () -> Unit,
        onNew: () -> Unit,
        onCloseSession: (SessionTarget) -> Unit = {},
        allPanes: (@androidx.compose.runtime.Composable (() -> Unit) -> Unit)? = null,
    ) {
        TabPaneSwitcher(
            emptyList(),
            "",
            "",
            emptyMap(),
            emptyMap(),
            emptyMap(),
            { 0 },
            true,
            true,
            true,
            {},
            {},
            {},
            { _, _ -> },
            {},
            {},
            onClose,
            sessions =
                SwitcherSessions(targets.size, "") {
                    SessionsPage(
                        targets,
                        current,
                        last,
                        true,
                        "",
                        "",
                        onCloseSession,
                        onRename,
                        onSelect,
                        onNew,
                        allPanes,
                    )
                },
            openSessions = true,
        )
    }

    @Test
    fun compactDrawerFiltersAcrossServersAndNavigatesWithoutDuplicateStrip() {
        var selected = ""
        var closed = 0
        ui.setContent {
            RexTheme(true, ThemeCatalog.defaults[0]) {
                SessionsDrawer(
                    targets,
                    targets[0].key,
                    targets[1].key,
                    { _, _ -> true },
                    { selected = it },
                    { closed++ },
                    {},
                )
            }
        }
        ui.onNodeWithContentDescription("Session strip").assertDoesNotExist()
        ui.onNodeWithText("Rexling development").assertIsDisplayed()
        ui.onNodeWithText("Ubuntu server").assertIsDisplayed()
        capture("sessions-compact-dark.png")
        val before = ui.onNode(hasSetTextAction()).fetchSemanticsNode().boundsInRoot
        ui.onNode(hasSetTextAction()).performTextInput("Ubuntu")
        ui.onNodeWithText("Rexling development").assertDoesNotExist()
        assertEquals(
            before.width,
            ui.onNode(hasSetTextAction()).fetchSemanticsNode().boundsInRoot.width,
        )
        ui.onNodeWithText("Ubuntu server").performClick()
        ui.runOnIdle {
            assertEquals(targets[2].key, selected)
            assertEquals(0, closed)
        }
    }

    @Test
    fun newSessionAndSessionActionsRemainSeparateFromSelection() {
        var created = 0
        var selected = 0
        var renamed = ""
        ui.setContent {
            RexTheme(false, ThemeCatalog.defaults[1]) {
                SessionsDrawer(
                    targets,
                    targets[0].key,
                    targets[1].key,
                    { _, name ->
                        renamed = name
                        true
                    },
                    { selected++ },
                    {},
                    { created++ },
                )
            }
        }
        capture("sessions-compact-light.png")
        ui.onNodeWithText("New session").performClick()
        ui.onNodeWithText("ShipPDF").swipeForSwitcherActions()
        ui.onNodeWithContentDescription("Rename session").performClick()
        ui.onNode(hasSetTextAction() and hasText("ShipPDF")).performTextReplacement("New project")
        ui.onNodeWithText("Save").performClick()
        ui.runOnIdle {
            assertEquals(1, created)
            assertEquals(0, selected)
            assertEquals("New project", renamed)
        }
    }

    @Test
    fun emptyDrawerStillOffersNewSession() {
        var created = 0
        ui.setContent {
            RexTheme(false, ThemeCatalog.defaults[1]) {
                SessionsDrawer(emptyList(), "", "", { _, _ -> false }, {}, {}, { created++ })
            }
        }
        ui.onNodeWithText("No sessions yet").assertIsDisplayed()
        ui.onNodeWithText("New session").assertIsEnabled().performClick()
        ui.runOnIdle { assertEquals(1, created) }
    }

    @Test
    fun closingFromASwipeRequiresConfirmation() {
        val closed = mutableListOf<String>()
        ui.setContent {
            RexTheme(false, ThemeCatalog.defaults[1]) {
                SessionsDrawer(
                    targets,
                    targets[0].key,
                    "",
                    { _, _ -> false },
                    {},
                    {},
                    {},
                    onCloseSession = { closed += it.key },
                )
            }
        }
        fun requestClose() {
            ui.onNodeWithText("ShipPDF").swipeForSwitcherActions()
            ui.onNodeWithContentDescription("Close session").performTouchInput { click() }
            ui.onNodeWithText("Close session?").assertIsDisplayed()
        }
        requestClose()
        ui.runOnIdle { assertTrue(closed.isEmpty()) }
        ui.onNodeWithText("Cancel").performClick()
        ui.runOnIdle { assertTrue(closed.isEmpty()) }
        requestClose()
        ui.onNodeWithText("Close session").performClick()
        ui.runOnIdle { assertEquals(listOf(targets[1].key), closed) }
    }

    @Test
    fun allPanesInSessionsSearchesRemoteDirectoriesAndKeepsTheExactServerTarget() {
        val codex = ProgramIdentity.resolve(listOf("codex"))
        val local =
            PaneCatalogTarget(
                mac,
                targets[0].session,
                PaneCatalogEntry("s", "pane", "Terminal", codex, "Local task", "~/Dev/rexling"),
                ActivityState.WORKING,
            )
        val remote =
            PaneCatalogTarget(
                linux,
                targets[2].session,
                PaneCatalogEntry(
                    "s",
                    "pane",
                    "Terminal",
                    codex,
                    "Deployment task",
                    "~/infra/builds",
                ),
                null,
            )
        val remoteState =
            AppState(
                phase = "connected",
                sessions = listOf(remote.session),
                selectedSession = remote.session.id,
                windows =
                    listOf(WindowItem("w", "Terminal", listOf(BlockItem("pane", "Terminal")))),
                paneCatalog =
                    PaneCatalogSnapshot(listOf(remote.pane), partial = false, error = false),
            )
        val sources =
            listOf(
                PaneCatalogSource(mac, listOf(local), false, false),
                PaneCatalog.collect(linux, remoteState),
            )
        var opened: PaneCatalogTarget? = null
        ui.setContent {
            RexTheme(true, ThemeCatalog.defaults[0]) {
                SessionsDrawer(
                    targets,
                    targets[0].key,
                    "",
                    { _, _ -> false },
                    {},
                    {},
                    {},
                    allPanes = { back ->
                        AllPanesPage(sources, mac.id, "s", "pane", back, {}) {
                            opened = it
                            true
                        }
                    },
                )
            }
        }
        ui.onNodeWithText("All panes").performClick()
        ui.onNodeWithText("Shulhi MBP").assertIsDisplayed()
        ui.onNodeWithText("Local task").assertIsDisplayed()
        ui.onNode(hasSetTextAction()).performTextReplacement("infra/builds")
        ui.onNodeWithText("Local task").assertDoesNotExist()
        ui.onNodeWithText("Ubuntu server").assertIsDisplayed()
        ui.onNodeWithText("Deployment task").performClick()
        ui.runOnIdle {
            assertEquals(remote, opened)
            assertNotEquals(local.key, remote.key)
        }
        ui.onNodeWithContentDescription("Back to sessions").performClick()
        ui.onNodeWithText("All panes").assertIsDisplayed()
    }
}

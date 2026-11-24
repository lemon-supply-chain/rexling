package dev.lemon.rex

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TerminalEditActionsTest {
    @get:Rule val ui = createComposeRule()
    private val window =
        mutableStateOf(
            WindowItem(
                "w",
                "Project",
                listOf(BlockItem("a", "shell"), BlockItem("b", "shell"), BlockItem("f", "shell")),
                listOf(
                    PanePlacement("a", 0f, 0f, .5f, 1f),
                    PanePlacement("b", .5f, 0f, .5f, 1f),
                    PanePlacement("f", .1f, .1f, .7f, .7f, floating = true),
                ),
            ),
        )
    private val calls = mutableListOf<String>()

    private fun show(page: Int) {
        ui.setContent {
            RexTheme(false, ThemeCatalog.defaults[1]) {
                TabPaneSwitcher(
                    listOf(window.value),
                    "w",
                    "a",
                    mapOf(
                        "a" to ProgramIdentity.resolve(listOf("fish")),
                        "b" to ProgramIdentity.resolve(listOf("claude")),
                        "f" to ProgramIdentity.resolve(listOf("codex")),
                    ),
                    mapOf("b" to "Agent task", "f" to "Floating task"),
                    emptyMap(),
                    { 0 },
                    true,
                    true,
                    true,
                    { calls += "open-tab" },
                    { calls += "open-pane" },
                    {},
                    { _, _ -> },
                    {},
                    {},
                    {},
                    initialPage = page,
                    onRenameTab = { id, label ->
                        calls += "rename-tab:$id:$label"
                        true
                    },
                    onRenamePane = { tab, id, label ->
                        calls += "rename-pane:$tab:$id:$label"
                        true
                    },
                    onFloatPane = { tab, id ->
                        calls += "float:$tab:$id"
                        window.value =
                            window.value.copy(
                                layout =
                                    window.value.layout.map {
                                        if (it.id == id) it.copy(floating = true) else it
                                    },
                            )
                        true
                    },
                    onDockPane = { tab, id, anchor, split ->
                        calls += "dock:$tab:$id:$anchor:${split.name}"
                        true
                    },
                )
            }
        }
    }

    @Test
    fun tabAndPaneRenameActionsNeverOpenTheTerminal() {
        show(0)
        ui.onNodeWithContentDescription(appString(R.string.a11y_tab_title, "Project"))
            .swipeForSwitcherActions()
        ui.onNodeWithContentDescription(appString(R.string.label_rename_tab)).performClick()
        ui.onNode(hasSetTextAction()).performTextReplacement("Release")
        ui.onNodeWithText(appString(R.string.save)).performClick()
        ui.onNodeWithText(appString(R.string.section_panes)).performClick()
        ui.onNodeWithContentDescription(appString(R.string.a11y_pane_title, "fish"))
            .swipeForSwitcherActions()
        ui.onNodeWithContentDescription(appString(R.string.label_rename_pane)).performClick()
        ui.onNode(hasSetTextAction()).performTextReplacement("Console")
        ui.onNodeWithText(appString(R.string.save)).performClick()
        ui.runOnIdle {
            assertEquals(listOf("rename-tab:w:Release", "rename-pane:w:a:Console"), calls)
        }
    }

    @Test
    fun dockingChoosesTheTargetAndSideWhileFloatingKeepsOneTiledPane() {
        show(1)
        ui.onNodeWithContentDescription(appString(R.string.a11y_pane_title, "Floating task"))
            .swipeForSwitcherActions()
        ui.onNodeWithContentDescription(appString(R.string.label_dock_pane)).performClick()
        ui.onNodeWithContentDescription(appString(R.string.close_dock_pane)).assertIsDisplayed()
        ui.onNodeWithContentDescription("Close tab and pane switcher").assertDoesNotExist()
        ui.onNodeWithContentDescription(appString(R.string.close_dock_pane)).performClick()
        ui.onNodeWithContentDescription(appString(R.string.a11y_pane_title, "Floating task"))
            .swipeForSwitcherActions()
        ui.onNodeWithContentDescription(appString(R.string.label_dock_pane)).performClick()
        val map =
            ui.onNodeWithContentDescription(appString(R.string.a11y_pane_layout))
                .fetchSemanticsNode()
                .boundsInRoot
        val drawer = ui.onNodeWithTag("Frosted overlay").fetchSemanticsNode().boundsInRoot
        assertEquals(4f / 3f, map.width / map.height, .01f)
        assertEquals(drawer.center.x, map.center.x, 1f)
        assertTrue(map.width < drawer.width)
        ui.onNodeWithContentDescription(
                appString(R.string.a11y_layout_pane_format, "Floating task"),
            )
            .assertDoesNotExist()
        ui.onNodeWithText(appString(R.string.dock_right)).assertIsDisplayed().performClick()
        ui.onNodeWithContentDescription(appString(R.string.close_dock_pane)).assertDoesNotExist()
        ui.onNodeWithContentDescription(appString(R.string.a11y_pane_title, "Floating task"))
            .swipeForSwitcherActions()
        ui.onNodeWithContentDescription(appString(R.string.label_dock_pane)).performClick()
        ui.onNodeWithContentDescription("Layout pane Agent task").performClick().assertIsSelected()
        ui.onNodeWithText(appString(R.string.dock_below)).assertIsDisplayed().performClick()
        ui.onNodeWithContentDescription(appString(R.string.a11y_pane_title, "Agent task"))
            .swipeForSwitcherActions()
        ui.onNodeWithContentDescription(appString(R.string.label_float_pane)).performClick()
        ui.onNodeWithContentDescription(appString(R.string.a11y_pane_title, "fish"))
            .swipeForSwitcherActions()
        ui.onNodeWithContentDescription(appString(R.string.label_float_pane)).assertIsNotEnabled()
        ui.runOnIdle {
            assertEquals(listOf("dock:w:f:a:RIGHT", "dock:w:f:b:BELOW", "float:w:b"), calls)
        }
    }

    @Test
    fun rejectedRenameKeepsTheInputAndSupportsResetToAutomatic() {
        val target = TerminalRenameTarget("w", "a", false, "My pane")
        var accept = false
        var closed = 0
        var value = ""
        ui.setContent {
            RexTheme(true, ThemeCatalog.defaults[0]) {
                TerminalRenameDialog(
                    target,
                    true,
                    {
                        value = it
                        accept
                    },
                ) {
                    closed++
                }
            }
        }
        ui.onNodeWithText(appString(R.string.save)).performClick()
        ui.onNode(hasSetTextAction()).assertTextContains("My pane")
        ui.runOnIdle {
            assertEquals(0, closed)
            accept = true
        }
        ui.onNode(hasSetTextAction()).performTextClearance()
        ui.onNodeWithText(appString(R.string.save)).performClick()
        ui.runOnIdle {
            assertEquals("", value)
            assertEquals(1, closed)
        }
    }
}

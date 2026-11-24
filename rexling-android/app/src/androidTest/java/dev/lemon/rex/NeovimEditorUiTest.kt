package dev.lemon.rex

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class NeovimEditorUiTest {
    @get:Rule val ui = createComposeRule()

    @Test
    fun groupsCanBeCreatedFilledRenamedAndUngroupedWithoutLosingCommands() {
        var saved: List<NeovimShortcut>? = null
        ui.setContent {
            RexTheme(false, ThemeCatalog.defaults[1]) {
                NeovimShortcutsSheet(
                    listOf(NeovimShortcut(":w", ":w")),
                    { text ->
                        runCatching { saved = NeovimShortcuts.parse(text) }
                            .exceptionOrNull()
                            ?.message
                    },
                    {},
                )
            }
        }
        capture("list")
        ui.onNodeWithText(appString(R.string.add_group)).performClick()
        ui.onNodeWithText(appString(R.string.group_name)).performTextInput("Git")
        ui.onNodeWithText(appString(R.string.done)).performClick()
        ui.onNodeWithContentDescription(appString(R.string.a11y_open_neovim_group, "Git"))
            .performClick()
        ui.onNodeWithText(appString(R.string.add_shortcut)).performClick()
        ui.onNodeWithText(appString(R.string.neovim_command)).performTextInput("Git")
        ui.onNodeWithText(appString(R.string.session_name_optional)).performTextInput("Status")
        capture("command")
        ui.onNodeWithText(appString(R.string.done)).performScrollTo().performClick()
        ui.onNodeWithContentDescription(appString(R.string.a11y_edit_neovim_shortcut, "Status"))
            .assertExists()
        ui.onNodeWithContentDescription(appString(R.string.back_to_neovim_shortcuts)).performClick()
        ui.onNodeWithContentDescription(appString(R.string.a11y_rename_neovim_group, "Git"))
            .performClick()
        ui.onNodeWithText(appString(R.string.group_name)).performTextReplacement("Tools")
        ui.onNodeWithText(appString(R.string.done)).performClick()
        capture("groups")
        ui.onNodeWithContentDescription(appString(R.string.a11y_ungroup_neovim_group, "Tools"))
            .performClick()
        ui.onNodeWithContentDescription(appString(R.string.a11y_edit_neovim_shortcut, "Status"))
            .assertExists()
        ui.onNodeWithText(appString(R.string.save)).performClick()
        ui.runOnIdle {
            assertEquals(
                listOf(NeovimShortcut(":w", ":w"), NeovimShortcut("Status", ":Git")),
                saved,
            )
        }
    }

    @Test
    fun emptyGroupCannotBeSilentlyLostOnSave() {
        var saves = 0
        var closed = false
        ui.setContent {
            RexTheme(false, ThemeCatalog.defaults[1]) {
                NeovimShortcutsSheet(
                    NeovimShortcuts.defaults,
                    {
                        saves++
                        null
                    },
                    { closed = true },
                )
            }
        }
        ui.onNodeWithText(appString(R.string.add_group)).performClick()
        ui.onNodeWithText(appString(R.string.group_name)).performTextInput("Empty")
        ui.onNodeWithText(appString(R.string.done)).performClick()
        ui.onNodeWithText(appString(R.string.save)).performClick()
        ui.onNodeWithText("Add a shortcut to Empty, or remove the empty group.").assertExists()
        ui.runOnIdle {
            assertEquals(0, saves)
            assertFalse(closed)
        }
    }

    @Test
    fun rootDragMovesWholeGroupAndEditingCanMoveAShortcutToAnotherGroup() {
        val initial =
            listOf(
                NeovimShortcut(":w", ":w"),
                NeovimShortcut("Quit", ":q", "Files"),
                NeovimShortcut("Build", ":make", "Tools"),
                NeovimShortcut(":wa", ":wa"),
            )
        var saved: List<NeovimShortcut>? = null
        ui.setContent {
            RexTheme(false, ThemeCatalog.defaults[1]) {
                NeovimShortcutsSheet(
                    initial,
                    { text ->
                        runCatching { saved = NeovimShortcuts.parse(text) }
                            .exceptionOrNull()
                            ?.message
                    },
                    {},
                )
            }
        }
        val from =
            ui.onNodeWithContentDescription(appString(R.string.a11y_open_neovim_group, "Files"))
                .fetchSemanticsNode()
                .boundsInRoot
        val to =
            ui.onNodeWithContentDescription(appString(R.string.a11y_open_neovim_group, "Tools"))
                .fetchSemanticsNode()
                .boundsInRoot
        ui.onNodeWithContentDescription(appString(R.string.a11y_open_neovim_group, "Files"))
            .performTouchInput {
                down(center)
                advanceEventTime(700)
                moveTo(
                    to.center - from.topLeft + androidx.compose.ui.geometry.Offset(0f, 8f),
                    delayMillis = 300,
                )
                up()
            }
        ui.onNodeWithContentDescription(appString(R.string.a11y_edit_neovim_shortcut, ":w"))
            .performClick()
        ui.onNodeWithText(appString(R.string.group_format, appString(R.string.ungrouped)))
            .performClick()
        ui.onNodeWithText("Files", substring = false).performClick()
        ui.onNodeWithText(appString(R.string.done)).performScrollTo().performClick()
        ui.onNodeWithText(appString(R.string.save)).performClick()
        ui.runOnIdle {
            assertEquals(
                listOf(
                    NeovimShortcut("Build", ":make", "Tools"),
                    NeovimShortcut("Quit", ":q", "Files"),
                    NeovimShortcut(":w", ":w", "Files"),
                    NeovimShortcut(":wa", ":wa"),
                ),
                saved,
            )
        }
    }

    @Test
    fun commandContainingAssignmentIsNeverSilentlyRewritten() {
        var saved: List<NeovimShortcut>? = null
        ui.setContent {
            RexTheme(false, ThemeCatalog.defaults[1]) {
                NeovimShortcutsSheet(
                    emptyList(),
                    { text ->
                        runCatching { saved = NeovimShortcuts.parse(text) }
                            .exceptionOrNull()
                            ?.message
                    },
                    {},
                )
            }
        }
        ui.onNodeWithText(appString(R.string.add_shortcut)).performClick()
        ui.onNodeWithText(appString(R.string.neovim_command)).performTextInput("lua x = y")
        ui.onNodeWithText(appString(R.string.done)).performScrollTo().performClick()
        ui.onNodeWithText(appString(R.string.neovim_name_without_equals)).assertExists()
        ui.onNodeWithText(appString(R.string.session_name_optional)).performTextInput("Lua")
        ui.onNodeWithText(appString(R.string.done)).performScrollTo().performClick()
        ui.onNodeWithContentDescription(appString(R.string.a11y_edit_neovim_shortcut, "Lua"))
            .assertExists()
        // Android's IME/window transition runs outside Compose's test clock.
        ui.waitForIdle()
        Thread.sleep(350)
        ui.onNodeWithText(appString(R.string.save)).performClick()
        ui.runOnIdle { assertEquals(listOf(NeovimShortcut("Lua", ":lua x = y")), saved) }
    }

    @Test
    fun ungroupedIsExpandedAndCollapsingItDoesNotCreateAGroupOrChangeMenuOrder() {
        val initial =
            listOf(
                NeovimShortcut(":w", ":w"),
                NeovimShortcut("Quit", ":q", "Files"),
                NeovimShortcut(":wa", ":wa"),
            )
        var saved: List<NeovimShortcut>? = null
        ui.setContent {
            RexTheme(false, ThemeCatalog.defaults[1]) {
                NeovimShortcutsSheet(
                    initial,
                    { text ->
                        runCatching { saved = NeovimShortcuts.parse(text) }
                            .exceptionOrNull()
                            ?.message
                    },
                    {},
                )
            }
        }
        ui.onNodeWithContentDescription(appString(R.string.a11y_edit_neovim_shortcut, ":w"))
            .assertIsDisplayed()
        ui.onNodeWithContentDescription(appString(R.string.a11y_open_neovim_group, "Ungrouped"))
            .assertDoesNotExist()
        ui.onNodeWithContentDescription(appString(R.string.a11y_ungrouped_shortcuts)).performClick()
        ui.onNodeWithContentDescription(appString(R.string.a11y_edit_neovim_shortcut, ":w"))
            .assertDoesNotExist()
        ui.onNodeWithContentDescription(appString(R.string.a11y_open_neovim_group, "Files"))
            .assertIsDisplayed()
        ui.onNodeWithText(appString(R.string.save)).performClick()
        ui.runOnIdle { assertEquals(initial, saved) }
    }

    private fun capture(name: String) {
        ui.waitForIdle()
        Thread.sleep(250)
        val instrumentation =
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        java.io
            .File(instrumentation.targetContext.cacheDir, "nvim-editor-$name.png")
            .outputStream()
            .use {
                instrumentation.uiAutomation
                    .takeScreenshot()
                    .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
    }
}

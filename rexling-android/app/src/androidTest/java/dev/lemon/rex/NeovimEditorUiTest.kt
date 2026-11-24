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
        ui.onNodeWithText("Add group").performClick()
        ui.onNodeWithText("Group name").performTextInput("Git")
        ui.onNodeWithText("Done").performClick()
        ui.onNodeWithContentDescription("Open Neovim group Git").performClick()
        ui.onNodeWithText("Add shortcut").performClick()
        ui.onNodeWithText("Neovim command").performTextInput("Git")
        ui.onNodeWithText("Name (optional)").performTextInput("Status")
        capture("command")
        ui.onNodeWithText("Done").performScrollTo().performClick()
        ui.onNodeWithContentDescription("Edit Neovim shortcut Status").assertExists()
        ui.onNodeWithContentDescription("Back to Neovim shortcuts").performClick()
        ui.onNodeWithContentDescription("Rename Neovim group Git").performClick()
        ui.onNodeWithText("Group name").performTextReplacement("Tools")
        ui.onNodeWithText("Done").performClick()
        capture("groups")
        ui.onNodeWithContentDescription("Ungroup Neovim group Tools").performClick()
        ui.onNodeWithContentDescription("Edit Neovim shortcut Status").assertExists()
        ui.onNodeWithText("Save").performClick()
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
        ui.onNodeWithText("Add group").performClick()
        ui.onNodeWithText("Group name").performTextInput("Empty")
        ui.onNodeWithText("Done").performClick()
        ui.onNodeWithText("Save").performClick()
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
            ui.onNodeWithContentDescription("Open Neovim group Files")
                .fetchSemanticsNode()
                .boundsInRoot
        val to =
            ui.onNodeWithContentDescription("Open Neovim group Tools")
                .fetchSemanticsNode()
                .boundsInRoot
        ui.onNodeWithContentDescription("Open Neovim group Files").performTouchInput {
            down(center)
            advanceEventTime(700)
            moveTo(
                to.center - from.topLeft + androidx.compose.ui.geometry.Offset(0f, 8f),
                delayMillis = 300,
            )
            up()
        }
        ui.onNodeWithContentDescription("Edit Neovim shortcut :w").performClick()
        ui.onNodeWithText("Group: Ungrouped").performClick()
        ui.onNodeWithText("Files", substring = false).performClick()
        ui.onNodeWithText("Done").performScrollTo().performClick()
        ui.onNodeWithText("Save").performClick()
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
        ui.onNodeWithText("Add shortcut").performClick()
        ui.onNodeWithText("Neovim command").performTextInput("lua x = y")
        ui.onNodeWithText("Done").performScrollTo().performClick()
        ui.onNodeWithText("Give this shortcut a name without ' = ' to preserve its keys.")
            .assertExists()
        ui.onNodeWithText("Name (optional)").performTextInput("Lua")
        ui.onNodeWithText("Done").performScrollTo().performClick()
        ui.onNodeWithContentDescription("Edit Neovim shortcut Lua").assertExists()
        // Android's IME/window transition runs outside Compose's test clock.
        ui.waitForIdle()
        Thread.sleep(350)
        ui.onNodeWithText("Save").performClick()
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
        ui.onNodeWithContentDescription("Edit Neovim shortcut :w").assertIsDisplayed()
        ui.onNodeWithContentDescription("Open Neovim group Ungrouped").assertDoesNotExist()
        ui.onNodeWithContentDescription("Ungrouped shortcuts").performClick()
        ui.onNodeWithContentDescription("Edit Neovim shortcut :w").assertDoesNotExist()
        ui.onNodeWithContentDescription("Open Neovim group Files").assertIsDisplayed()
        ui.onNodeWithText("Save").performClick()
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

package dev.lemon.rex

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class CustomControlShortcutsTest {
    @get:Rule val ui = createComposeRule()

    @Test
    fun customSequenceSendsOnceAndChangingPanesClosesThePopup() {
        val target = mutableStateOf("pane-a")
        val sent = mutableListOf<String>()
        val items = ControlShortcutConfig.parse("Workspace = Space + L\nL")
        ui.setContent {
            MaterialTheme {
                Box(Modifier.size(340.dp, 360.dp)) {
                    ControlDockKey(
                        true,
                        false,
                        true,
                        Modifier.align(Alignment.BottomCenter).size(36.dp),
                        {},
                        { sent += ControlShortcutConfig.decode(it) },
                        shortcuts = items,
                        inputKey = target.value,
                    )
                }
            }
        }
        open()
        ui.onNodeWithContentDescription(appString(R.string.a11y_control_shortcut, "Workspace"))
            .performClick()
        ui.runOnIdle { assertEquals(listOf("\u0000l"), sent) }
        open()
        ui.runOnIdle { target.value = "pane-b" }
        ui.onNodeWithContentDescription(appString(R.string.a11y_control_shortcuts))
            .assertDoesNotExist()
        ui.runOnIdle { assertEquals(1, sent.size) }
    }

    @Test
    fun moreThanTwelveShortcutsHavePagesAndUseTheRightEntry() {
        val sent = mutableListOf<String>()
        val items =
            ControlShortcutConfig.parse(
                (0..13).joinToString("\n") { "Key $it = ${('a'.code+it).toChar()}" },
            )
        ui.setContent {
            MaterialTheme {
                Box(Modifier.size(340.dp, 400.dp)) {
                    ControlDockKey(
                        true,
                        false,
                        true,
                        Modifier.align(Alignment.BottomCenter).size(36.dp),
                        {},
                        { sent += it },
                        shortcuts = items,
                    )
                }
            }
        }
        open()
        ui.onNodeWithContentDescription(appString(R.string.a11y_next_ctrl_page)).performClick()
        ui.onNodeWithText("2/2").assertIsDisplayed()
        ui.onNodeWithContentDescription(appString(R.string.a11y_control_shortcut, "Key 13"))
            .performClick()
        ui.runOnIdle { assertEquals(listOf("n"), sent) }
    }

    @Test
    fun recorderAddsNamedSpacePrefixAndSavesTheList() {
        var saved: List<ControlShortcut>? = null
        var closed = false
        ui.setContent {
            RexTheme(false, ThemeCatalog.defaults[1]) {
                ControlShortcutsSheet(
                    ControlShortcutConfig.defaults,
                    { text ->
                        runCatching { saved = ControlShortcutConfig.parse(text) }
                            .exceptionOrNull()
                            ?.message
                    },
                    { closed = true },
                )
            }
        }
        capture("list")
        ui.onNodeWithText(appString(R.string.add_shortcut)).performClick()
        ui.onNodeWithText("Space", substring = false).performClick()
        ui.onNodeWithText(appString(R.string.press_keys)).performTextInput("l")
        ui.onNodeWithText("Ctrl+Space").assertExists()
        capture("recorder")
        ui.onNodeWithText(appString(R.string.session_name_optional)).performTextInput("Workspace")
        ui.onNodeWithText(appString(R.string.done)).performScrollTo().performClick()
        ui.onNodeWithContentDescription(appString(R.string.a11y_ctrl_shortcut_list))
            .performScrollToNode(
                hasContentDescription(appString(R.string.a11y_edit_ctrl_shortcut, "Workspace")),
            )
        ui.onNodeWithContentDescription(appString(R.string.a11y_edit_ctrl_shortcut, "Workspace"))
            .assertIsDisplayed()
        ui.onNodeWithText(appString(R.string.save)).performClick()
        ui.runOnIdle {
            assertTrue(closed)
            assertEquals("\u0000l", ControlShortcutConfig.decode(saved!!.last().keys))
        }
    }

    @Test
    fun recorderUndoClearCancelAndDragReorderKeepTheRightEntries() {
        var saved: List<ControlShortcut>? = null
        ui.setContent {
            RexTheme(false, ThemeCatalog.defaults[1]) {
                ControlShortcutsSheet(
                    ControlShortcutConfig.defaults.take(3),
                    { text ->
                        runCatching { saved = ControlShortcutConfig.parse(text) }
                            .exceptionOrNull()
                            ?.message
                    },
                    {},
                )
            }
        }
        ui.onNodeWithContentDescription(appString(R.string.a11y_edit_ctrl_shortcut, "^C"))
            .performClick()
        ui.onNodeWithText(appString(R.string.clear)).performClick()
        ui.onNodeWithText(appString(R.string.press_keys)).performTextInput("ab")
        ui.onNodeWithText("Ctrl+A").assertExists()
        ui.onNodeWithText("B", substring = false).assertExists()
        ui.onNodeWithText(appString(R.string.undo)).performClick()
        ui.onNodeWithText("B", substring = false).assertDoesNotExist()
        ui.onNodeWithText(appString(R.string.press_keys)).performImeAction()
        ui.onAllNodesWithText("Enter", substring = false).assertCountEquals(2)
        ui.onNodeWithText(appString(R.string.cancel)).performScrollTo().performClick()
        val from =
            ui.onNodeWithContentDescription(appString(R.string.a11y_edit_ctrl_shortcut, "^C"))
                .fetchSemanticsNode()
                .boundsInRoot
        val to =
            ui.onNodeWithContentDescription(appString(R.string.a11y_edit_ctrl_shortcut, "^Z"))
                .fetchSemanticsNode()
                .boundsInRoot
        ui.onNodeWithContentDescription(appString(R.string.a11y_edit_ctrl_shortcut, "^C"))
            .performTouchInput {
                down(center)
                advanceEventTime(700)
                moveTo(to.center - from.topLeft, delayMillis = 300)
                up()
            }
        ui.onNodeWithContentDescription(appString(R.string.a11y_remove_ctrl_shortcut, "^Z"))
            .performClick()
        ui.onNodeWithText(appString(R.string.save)).performClick()
        ui.runOnIdle { assertEquals(listOf("d", "c"), saved!!.map { it.keys }) }
    }

    private fun capture(name: String) {
        ui.waitForIdle()
        Thread.sleep(250)
        val instrumentation =
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        java.io
            .File(instrumentation.targetContext.cacheDir, "ctrl-shortcuts-$name.png")
            .outputStream()
            .use {
                val shot = instrumentation.uiAutomation.takeScreenshot()
                org.junit.Assume.assumeTrue(
                    "Screenshot unavailable; keep the screen on",
                    shot != null,
                )
                shot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
    }

    private fun open() {
        ui.onNodeWithContentDescription(appString(R.string.a11y_control)).performSemanticsAction(
            SemanticsActions.OnLongClick,
        ) {
            it()
        }
    }
}

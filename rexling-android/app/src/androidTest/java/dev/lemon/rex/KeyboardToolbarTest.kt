package dev.lemon.rex

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class KeyboardToolbarTest {
    @get:Rule val ui = createComposeRule()

    @Test
    fun dragSortsRemovesAddsAndReplacesAtTheLimit() {
        var saved: List<ToolbarKey>? = null
        ui.setContent {
            RexTheme(false, ThemeCatalog.defaults[1]) {
                KeyboardToolbarSheet(ToolbarLayout.defaults, { saved = it }, {})
            }
        }
        capture()
        drag("Toolbar slot 1: Escape", "Toolbar slot 3: Alt")
        ui.onNodeWithContentDescription("Toolbar slot 3: Escape").assertExists()
        drag("Toolbar slot 3: Escape", "Available toolbar buttons")
        ui.onNodeWithText("Your toolbar · 6/7").assertIsDisplayed()
        ui.onNodeWithContentDescription("Available button: Escape").assertExists()
        drag("Available button: Paste", "Toolbar slot 1: Ctrl")
        ui.onNodeWithContentDescription("Toolbar slot 1: Paste").assertExists()
        ui.onNodeWithText("Your toolbar · 7/7").assertIsDisplayed()
        drag("Available button: Backspace", "Toolbar slot 7: Enter")
        ui.onNodeWithContentDescription("Toolbar slot 7: Backspace").assertExists()
        ui.onNodeWithContentDescription("Available button: Enter").assertExists()
        ui.onNodeWithText("Save").performClick()
        ui.runOnIdle {
            assertEquals(7, saved!!.size)
            assertEquals(ToolbarKey.PASTE, saved!!.first())
            assertEquals(ToolbarKey.BACKSPACE, saved!!.last())
            assertFalse(ToolbarKey.ESCAPE in saved!!)
            assertFalse(ToolbarKey.ENTER in saved!!)
        }
    }

    @Test
    fun tappingAndAccessibilityCanEditWithoutDraggingAndCancelKeepsTheOrder() {
        var saved: List<ToolbarKey>? = null
        ui.setContent {
            RexTheme(false, ThemeCatalog.defaults[1]) {
                KeyboardToolbarSheet(ToolbarLayout.defaults, { saved = it }, {})
            }
        }
        ui.onNodeWithText("Remove selected").performClick()
        ui.onNodeWithContentDescription("Available button: Paste").performClick()
        val actions =
            ui.onNodeWithContentDescription("Toolbar slot 7: Paste")
                .fetchSemanticsNode()
                .config[androidx.compose.ui.semantics.SemanticsActions.CustomActions]
        ui.runOnIdle { actions.first { it.label == "Move left" }.action() }
        ui.onNodeWithContentDescription("Toolbar slot 6: Paste").assertExists()
        ui.onNodeWithContentDescription("Toolbar slot 1: Ctrl").performTouchInput {
            down(center)
            advanceEventTime(700)
            moveBy(androidx.compose.ui.geometry.Offset(40f, 0f))
            cancel()
        }
        ui.onNodeWithContentDescription("Toolbar slot 1: Ctrl").assertExists()
        ui.onNodeWithText("Save").performClick()
        ui.runOnIdle {
            assertEquals(7, saved!!.size)
            assertEquals(ToolbarKey.PASTE, saved!![5])
        }
    }

    @Test
    fun paletteScrollingAndBoundaryFlingsNeverMoveOrDismissTheDrawer() {
        var closed = false
        ui.setContent {
            RexTheme(false, ThemeCatalog.defaults[1]) {
                KeyboardToolbarSheet(ToolbarLayout.defaults, {}, { closed = true })
            }
        }
        val initial = ui.onNodeWithText("Keyboard toolbar").fetchSemanticsNode().boundsInRoot.top
        val palette = ui.onNodeWithContentDescription("Available toolbar buttons")
        repeat(3) {
            palette.performTouchInput { swipeDown(durationMillis = 150) }
            ui.onNodeWithText("Keyboard toolbar").assertIsDisplayed()
            ui.runOnIdle { assertFalse(closed) }
            assertEquals(
                "Palette overscroll must not move its drawer",
                initial,
                ui.onNodeWithText("Keyboard toolbar").fetchSemanticsNode().boundsInRoot.top,
                1f,
            )
        }
        repeat(4) { palette.performTouchInput { swipeUp(durationMillis = 150) } }
        ui.onNodeWithContentDescription("Available button: F12").assertIsDisplayed()
        repeat(5) { palette.performTouchInput { swipeDown(durationMillis = 150) } }
        ui.onNodeWithContentDescription("Available button: Paste").assertIsDisplayed()
        ui.runOnIdle { assertFalse(closed) }
        assertEquals(
            initial,
            ui.onNodeWithText("Keyboard toolbar").fetchSemanticsNode().boundsInRoot.top,
            1f,
        )
        ui.onNodeWithContentDescription("Close keyboard toolbar").performClick()
        ui.runOnIdle { assertTrue(closed) }
    }

    @Test
    fun fixedPanelKeepsInsideTapsAndStillAllowsBackAndOutsideDismissal() {
        var dismissals = 0
        ui.setContent {
            RexTheme(false, ThemeCatalog.defaults[1]) {
                KeyboardToolbarSheet(ToolbarLayout.defaults, {}, { dismissals++ })
            }
        }
        ui.onNodeWithText("Your toolbar · 7/7").performClick()
        ui.runOnIdle { assertEquals(0, dismissals) }
        androidx.test.espresso.Espresso.pressBack()
        ui.runOnIdle { assertEquals(1, dismissals) }
        ui.onNodeWithText("Keyboard toolbar").performTouchInput {
            click(androidx.compose.ui.geometry.Offset(center.x, -100f))
        }
        ui.runOnIdle { assertEquals(2, dismissals) }
    }

    private fun drag(source: String, target: String) {
        val from = ui.onNodeWithContentDescription(source).fetchSemanticsNode().boundsInRoot
        val to = ui.onNodeWithContentDescription(target).fetchSemanticsNode().boundsInRoot
        ui.onNodeWithContentDescription(source).performTouchInput {
            down(center)
            advanceEventTime(700)
            moveTo(to.center - from.topLeft, delayMillis = 300)
            up()
        }
        ui.waitForIdle()
    }

    private fun capture() {
        ui.waitForIdle()
        Thread.sleep(300)
        val instrumentation =
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        java.io
            .File(instrumentation.targetContext.cacheDir, "keyboard-toolbar-editor.png")
            .outputStream()
            .use {
                instrumentation.uiAutomation
                    .takeScreenshot()
                    .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
    }

    @Test
    fun customKeysDispatchAndKeyboardStaysAvailableWithNoSlots() {
        val layout = mutableStateOf(listOf(ToolbarKey.PASTE, ToolbarKey.ENTER, ToolbarKey.CTRL_C))
        val sent = mutableListOf<String>()
        var pasted = 0
        var keyboard = 0
        ui.setContent {
            RexTheme(false, ThemeCatalog.defaults[1]) {
                KeyboardBar(
                    true,
                    true,
                    false,
                    false,
                    false,
                    true,
                    {},
                    {},
                    {},
                    {},
                    {},
                    { sent += it },
                    onControlShortcut = { sent += "ctrl-$it" },
                    onPaste = { pasted++ },
                    toolbarKeys = layout.value,
                    onKeyboard = { keyboard++ },
                )
            }
        }
        ui.onNodeWithContentDescription("Paste").performClick()
        ui.onNodeWithContentDescription("Enter").performClick()
        ui.onNodeWithContentDescription("Ctrl+C").performClick()
        ui.runOnIdle {
            assertEquals(1, pasted)
            assertEquals(listOf("enter", "ctrl-c"), sent)
            layout.value = emptyList()
        }
        ui.onNodeWithContentDescription("Hide keyboard").performClick()
        ui.onNodeWithContentDescription("Show extra keys").performClick()
        ui.onNodeWithContentDescription("Function key F1").assertIsDisplayed()
        ui.runOnIdle { assertEquals(1, keyboard) }
    }
}

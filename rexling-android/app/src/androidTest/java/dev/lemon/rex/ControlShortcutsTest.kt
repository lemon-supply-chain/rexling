package dev.lemon.rex

import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ControlShortcutsTest {
    @get:Rule val ui = createComposeRule()
    private var toggles = 0
    private val sent = mutableListOf<String>()

    private fun fixture(
        enabled: Boolean = true,
        gesture: ControlShortcutGesture = ControlShortcutGesture.HOLD,
        initiallyActive: Boolean = false,
        alignment: Alignment = Alignment.BottomCenter,
    ) {
        ui.setContent {
            var active by remember { mutableStateOf(initiallyActive) }
            MaterialTheme {
                Box(Modifier.size(320.dp, 320.dp).padding(top = 40.dp)) {
                    ControlDockKey(
                        enabled,
                        active,
                        true,
                        Modifier.align(alignment).size(36.dp),
                        {
                            toggles++
                            active = !active
                        },
                        { sent += it },
                        gesture,
                    )
                }
            }
        }
    }

    private fun bounds(description: String): Rect {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val info = automation.serviceInfo
        info.flags = info.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        automation.serviceInfo = info
        fun find(node: AccessibilityNodeInfo?): Rect? {
            if (node == null) return null
            if (node.contentDescription?.toString() == description) {
                val rect = Rect()
                node.getBoundsInScreen(rect)
                if (!rect.isEmpty) return rect
            }
            for (i in 0 until node.childCount) find(node.getChild(i))?.let {
                return it
            }
            return null
        }
        val deadline = SystemClock.uptimeMillis() + 4000
        while (SystemClock.uptimeMillis() < deadline) {
            for (window in automation.windows) find(window.root)?.let {
                return it
            }
            // A native popup may still need a frame to publish its accessibility tree.
            if (!ui.mainClock.autoAdvance) {
                ui.mainClock.advanceTimeByFrame()
                ui.waitForIdle()
            }
            SystemClock.sleep(20)
        }
        error("No visible shortcut node: $description")
    }

    private fun touch(action: Int, x: Float, y: Float, down: Long) {
        val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
        InstrumentationRegistry.getInstrumentation().sendPointerSync(event)
        event.recycle()
    }

    @Test
    fun tapStillTogglesCtrlWithoutOpeningThePopup() {
        fixture()
        ui.onNodeWithContentDescription(appString(R.string.a11y_control)).performTouchInput {
            click()
        }
        ui.onNodeWithContentDescription(appString(R.string.a11y_control)).assertIsSelected()
        ui.onNodeWithContentDescription(appString(R.string.a11y_control_shortcuts))
            .assertDoesNotExist()
        ui.runOnIdle {
            assertEquals(1, toggles)
            assertTrue(sent.isEmpty())
        }
    }

    @Test
    fun holdSlideAndReleaseSendsExactlyOneShortcut() {
        fixture()
        val start = bounds("Control")
        val down = SystemClock.uptimeMillis()
        ui.mainClock.autoAdvance = false
        touch(MotionEvent.ACTION_DOWN, start.exactCenterX(), start.exactCenterY(), down)
        ui.mainClock.advanceTimeBy(650)
        repeat(3) { ui.mainClock.advanceTimeByFrame() }
        ui.waitForIdle()
        ui.onNodeWithContentDescription(appString(R.string.a11y_control_shortcuts))
            .assertIsDisplayed()
        val target = bounds("Control W")
        touch(MotionEvent.ACTION_MOVE, target.exactCenterX(), target.exactCenterY(), down)
        ui.mainClock.advanceTimeByFrame()
        ui.onNodeWithContentDescription("Control W").assertIsSelected()
        touch(MotionEvent.ACTION_UP, target.exactCenterX(), target.exactCenterY(), down)
        ui.mainClock.advanceTimeByFrame()
        ui.onNodeWithContentDescription(appString(R.string.a11y_control_shortcuts))
            .assertDoesNotExist()
        ui.runOnIdle {
            assertEquals(listOf("w"), sent)
            assertEquals(0, toggles)
        }
    }

    @Test
    fun holdSlideAndReleaseCanReachTheNewControlJCell() {
        fixture()
        val start = bounds("Control")
        val down = SystemClock.uptimeMillis()
        ui.mainClock.autoAdvance = false
        touch(MotionEvent.ACTION_DOWN, start.exactCenterX(), start.exactCenterY(), down)
        ui.mainClock.advanceTimeBy(650)
        repeat(3) { ui.mainClock.advanceTimeByFrame() }
        ui.waitForIdle()
        val target = bounds("Control J")
        touch(MotionEvent.ACTION_MOVE, target.exactCenterX(), target.exactCenterY(), down)
        ui.mainClock.advanceTimeByFrame()
        ui.onNodeWithContentDescription("Control J").assertIsSelected()
        touch(MotionEvent.ACTION_UP, target.exactCenterX(), target.exactCenterY(), down)
        ui.mainClock.advanceTimeByFrame()
        ui.onNodeWithContentDescription(appString(R.string.a11y_control_shortcuts))
            .assertDoesNotExist()
        ui.runOnIdle {
            assertEquals(listOf("j"), sent)
            assertEquals(0, toggles)
        }
    }

    @Test
    fun popupBelowAnEdgeTriggerStillSelectsTheCorrectRow() {
        fixture(alignment = Alignment.TopStart)
        val start = bounds("Control")
        val down = SystemClock.uptimeMillis()
        ui.mainClock.autoAdvance = false
        touch(MotionEvent.ACTION_DOWN, start.exactCenterX(), start.exactCenterY(), down)
        ui.mainClock.advanceTimeBy(650)
        repeat(3) { ui.mainClock.advanceTimeByFrame() }
        ui.waitForIdle()
        assertTrue(bounds("Control shortcuts").top > start.bottom)
        capture("below-edge")
        val target = bounds("Control W")
        touch(MotionEvent.ACTION_MOVE, target.exactCenterX(), target.exactCenterY(), down)
        ui.mainClock.advanceTimeByFrame()
        ui.onNodeWithContentDescription("Control W").assertIsSelected()
        touch(MotionEvent.ACTION_UP, target.exactCenterX(), target.exactCenterY(), down)
        ui.mainClock.advanceTimeByFrame()
        ui.runOnIdle {
            assertEquals(listOf("w"), sent)
            assertEquals(0, toggles)
        }
    }

    private fun capture(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.cacheDir, "control-bubble-$name.png").outputStream().use {
            ui.onNodeWithContentDescription(appString(R.string.a11y_control_shortcuts))
                .captureToImage()
                .asAndroidBitmap()
                .compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test
    fun releasingOutsideThePopupCancelsWithoutTogglingOrTyping() {
        fixture()
        val start = bounds("Control")
        val down = SystemClock.uptimeMillis()
        ui.mainClock.autoAdvance = false
        touch(MotionEvent.ACTION_DOWN, start.exactCenterX(), start.exactCenterY(), down)
        ui.mainClock.advanceTimeBy(650)
        repeat(3) { ui.mainClock.advanceTimeByFrame() }
        ui.waitForIdle()
        ui.onNodeWithContentDescription(appString(R.string.a11y_control_shortcuts))
            .assertIsDisplayed()
        touch(MotionEvent.ACTION_UP, start.exactCenterX(), start.exactCenterY(), down)
        ui.mainClock.advanceTimeByFrame()
        ui.onNodeWithContentDescription(appString(R.string.a11y_control_shortcuts))
            .assertDoesNotExist()
        ui.runOnIdle {
            assertTrue(sent.isEmpty())
            assertEquals(0, toggles)
        }
    }

    @Test
    fun holdSlideAndReleaseSendsControlRightBracketExactlyOnce() {
        fixture()
        val start = bounds("Control")
        val down = SystemClock.uptimeMillis()
        ui.mainClock.autoAdvance = false
        touch(MotionEvent.ACTION_DOWN, start.exactCenterX(), start.exactCenterY(), down)
        ui.mainClock.advanceTimeBy(650)
        repeat(3) { ui.mainClock.advanceTimeByFrame() }
        ui.waitForIdle()
        val target = bounds("Control ]")
        touch(MotionEvent.ACTION_MOVE, target.exactCenterX(), target.exactCenterY(), down)
        ui.mainClock.advanceTimeByFrame()
        ui.onNodeWithContentDescription("Control ]").assertIsSelected()
        touch(MotionEvent.ACTION_UP, target.exactCenterX(), target.exactCenterY(), down)
        ui.mainClock.advanceTimeByFrame()
        ui.onNodeWithContentDescription(appString(R.string.a11y_control_shortcuts))
            .assertDoesNotExist()
        ui.runOnIdle {
            assertEquals(listOf("]"), sent)
            assertEquals(29, TerminalInput.modified(sent.single(), true, false).single().code)
            assertEquals(0, toggles)
        }
    }

    @Test
    fun releasingInAnEmptyGridCellDoesNotSendAShortcut() {
        fixture()
        val start = bounds("Control")
        val down = SystemClock.uptimeMillis()
        ui.mainClock.autoAdvance = false
        touch(MotionEvent.ACTION_DOWN, start.exactCenterX(), start.exactCenterY(), down)
        ui.mainClock.advanceTimeBy(650)
        repeat(3) { ui.mainClock.advanceTimeByFrame() }
        ui.waitForIdle()
        val last = bounds("Control ]")
        val density =
            InstrumentationRegistry.getInstrumentation()
                .targetContext
                .resources
                .displayMetrics
                .density
        val x = last.exactCenterX() + last.width() + 4 * density
        touch(MotionEvent.ACTION_MOVE, x, last.exactCenterY(), down)
        ui.mainClock.advanceTimeByFrame()
        touch(MotionEvent.ACTION_UP, x, last.exactCenterY(), down)
        ui.mainClock.advanceTimeByFrame()
        ui.onNodeWithContentDescription(appString(R.string.a11y_control_shortcuts))
            .assertDoesNotExist()
        ui.runOnIdle {
            assertTrue(sent.isEmpty())
            assertEquals(0, toggles)
        }
    }

    @Test
    fun readOnlyCtrlCannotOpenOrSendShortcuts() {
        fixture(enabled = false)
        ui.onNodeWithContentDescription(appString(R.string.a11y_control))
            .assertIsNotEnabled()
            .performTouchInput { longClick() }
        ui.onNodeWithContentDescription(appString(R.string.a11y_control_shortcuts))
            .assertDoesNotExist()
        ui.runOnIdle {
            assertTrue(sent.isEmpty())
            assertEquals(0, toggles)
        }
    }

    @Test
    fun doubleTapOpensPersistentPopupWithoutLeavingCtrlLatchedAndCanSendAShortcut() {
        fixture(gesture = ControlShortcutGesture.DOUBLE_TAP)
        ui.onNodeWithContentDescription(appString(R.string.a11y_control)).performTouchInput {
            doubleClick()
        }
        ui.onNodeWithContentDescription(appString(R.string.a11y_control_shortcuts))
            .assertIsDisplayed()
        ui.onNodeWithContentDescription(appString(R.string.a11y_control)).assertIsNotSelected()
        capture("above")
        ui.onNodeWithContentDescription("Control J").performClick()
        ui.onNodeWithContentDescription(appString(R.string.a11y_control_shortcuts))
            .assertDoesNotExist()
        ui.runOnIdle { assertEquals(listOf("j"), sent) }
    }

    @Test
    fun doubleTapModeStillTogglesCtrlImmediatelyOnASingleTap() {
        fixture(gesture = ControlShortcutGesture.DOUBLE_TAP)
        ui.onNodeWithContentDescription(appString(R.string.a11y_control)).performTouchInput {
            click()
        }
        ui.onNodeWithContentDescription(appString(R.string.a11y_control)).assertIsSelected()
        ui.onNodeWithContentDescription(appString(R.string.a11y_control_shortcuts))
            .assertDoesNotExist()
        ui.runOnIdle { assertEquals(1, toggles) }
    }

    @Test
    fun doubleTapModeDoesNotOpenThePopupOnHold() {
        fixture(gesture = ControlShortcutGesture.DOUBLE_TAP)
        ui.onNodeWithContentDescription(appString(R.string.a11y_control)).performTouchInput {
            longClick()
        }
        ui.onNodeWithContentDescription(appString(R.string.a11y_control_shortcuts))
            .assertDoesNotExist()
        ui.runOnIdle { assertTrue(sent.isEmpty()) }
    }

    @Test
    fun doubleTapPreservesAnAlreadyActiveCtrlModifier() {
        fixture(gesture = ControlShortcutGesture.DOUBLE_TAP, initiallyActive = true)
        ui.onNodeWithContentDescription(appString(R.string.a11y_control)).performTouchInput {
            doubleClick()
        }
        ui.onNodeWithContentDescription(appString(R.string.a11y_control_shortcuts))
            .assertIsDisplayed()
        ui.onNodeWithContentDescription(appString(R.string.a11y_control)).assertIsSelected()
        ui.runOnIdle { assertTrue(sent.isEmpty()) }
    }

    private fun nativeTapControl() {
        val start = bounds("Control")
        val down = SystemClock.uptimeMillis()
        touch(MotionEvent.ACTION_DOWN, start.exactCenterX(), start.exactCenterY(), down)
        SystemClock.sleep(35)
        touch(MotionEvent.ACTION_UP, start.exactCenterX(), start.exactCenterY(), down)
        ui.waitForIdle()
    }

    private fun assertTriggerClosesAndClears(initiallyActive: Boolean) {
        fixture(gesture = ControlShortcutGesture.DOUBLE_TAP, initiallyActive = initiallyActive)
        ui.onNodeWithContentDescription(appString(R.string.a11y_control)).performTouchInput {
            doubleClick()
        }
        ui.onNodeWithContentDescription(appString(R.string.a11y_control_shortcuts))
            .assertIsDisplayed()
        nativeTapControl()
        ui.onNodeWithContentDescription(appString(R.string.a11y_control_shortcuts))
            .assertDoesNotExist()
        ui.onNodeWithContentDescription(appString(R.string.a11y_control)).assertIsNotSelected()
        ui.runOnIdle {
            assertEquals(if (initiallyActive) 3 else 2, toggles)
            assertTrue(sent.isEmpty())
        }
        nativeTapControl()
        ui.onNodeWithContentDescription(appString(R.string.a11y_control)).assertIsSelected()
        ui.onNodeWithContentDescription(appString(R.string.a11y_control_shortcuts))
            .assertDoesNotExist()
    }

    @Test
    fun nativeTriggerTapClosesPopupWithoutSelectingCtrl() {
        assertTriggerClosesAndClears(false)
    }

    @Test
    fun nativeTriggerTapClosesPopupAndClearsPreviouslySelectedCtrl() {
        assertTriggerClosesAndClears(true)
    }

    @Test
    fun nativeOutsideDismissalDoesNotConsumeTheNextCtrlTap() {
        fixture(gesture = ControlShortcutGesture.DOUBLE_TAP)
        ui.onNodeWithContentDescription(appString(R.string.a11y_control)).performTouchInput {
            doubleClick()
        }
        ui.onNodeWithContentDescription(appString(R.string.a11y_control_shortcuts))
            .assertIsDisplayed()
        val start = bounds("Control")
        val down = SystemClock.uptimeMillis()
        touch(MotionEvent.ACTION_DOWN, 1f, start.exactCenterY(), down)
        SystemClock.sleep(35)
        touch(MotionEvent.ACTION_UP, 1f, start.exactCenterY(), down)
        ui.waitForIdle()
        ui.onNodeWithContentDescription(appString(R.string.a11y_control_shortcuts))
            .assertDoesNotExist()
        ui.onNodeWithContentDescription(appString(R.string.a11y_control)).assertIsNotSelected()
        nativeTapControl()
        ui.onNodeWithContentDescription(appString(R.string.a11y_control)).assertIsSelected()
        ui.runOnIdle {
            assertEquals(3, toggles)
            assertTrue(sent.isEmpty())
        }
    }
}

package dev.lemon.rex

import android.graphics.Paint
import android.view.InputDevice
import android.view.MotionEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TerminalMouseTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private fun event(view: TerminalView, action: Int, x: Float, y: Float, time: Long) {
        val e = MotionEvent.obtain(0, time, action, x, y, 0)
        view.onTouchEvent(e)
        e.recycle()
    }

    private fun frame(flags: Int = 1) =
        TerminalScreen(
            40,
            12,
            null,
            null,
            -1,
            0,
            emptyList(),
            TerminalScroll(12, 0, 12),
            inputFlags = flags,
        )

    private fun view() =
        TerminalView(instrumentation.targetContext).apply {
            screen = frame()
            controlled = true
            layout(0, 0, 1600, 1000)
        }

    /** Some APIs leave focus on a detached view; those cannot assert clearFocus(). */
    private fun canClearFocus(view: TerminalView): Boolean {
        view.requestFocus()
        view.clearFocus()
        return !view.hasFocus()
    }

    @Test
    fun semanticInputTapFocusesShellInputButNotOutputOrHistory() {
        instrumentation.runOnMainSync {
            val metrics = TerminalFonts(instrumentation.targetContext).configure(Paint(), 10f)
            val view =
                view().apply {
                    screen =
                        frame(48)
                            .copy(
                                cursorX = 2,
                                cursorY = 3,
                                cells = listOf(TerminalCell(0, 3, "$ ", -1, 0, 1 shl 30, 1)),
                                lines =
                                    List(12) {
                                        if (it == 3)
                                            listOf(TerminalCell(0, 3, "$ ", -1, 0, 1 shl 30, 1))
                                        else emptyList()
                                    },
                            )
                }
            Assume.assumeTrue("Detached view cannot clear focus", canClearFocus(view))
            fun tap(row: Int, time: Long) {
                event(view, MotionEvent.ACTION_DOWN, 5f, metrics.cellHeight * (row + .5f), time)
                event(view, MotionEvent.ACTION_UP, 5f, metrics.cellHeight * (row + .5f), time + 30)
            }
            tap(2, 0)
            assertFalse(view.hasFocus())
            tap(3, 100)
            assertTrue(view.hasFocus())
            view.clearFocus()
            view.screen = view.screen!!.copy(scroll = TerminalScroll(100, 0, 12))
            tap(3, 200)
            assertFalse(view.hasFocus())
            view.screen = view.screen!!.copy(scroll = TerminalScroll(12, 0, 12), inputFlags = 32)
            tap(3, 300)
            assertFalse(view.hasFocus())
        }
    }

    @Test
    fun tapSendsOneClickAtTheRenderedCellAfterReleaseWithoutActivation() {
        instrumentation.runOnMainSync {
            val calls = mutableListOf<Pair<Int, Int>>()
            var activated = 0
            val metrics = TerminalFonts(instrumentation.targetContext).configure(Paint(), 10f)
            val density = instrumentation.targetContext.resources.displayMetrics.density
            val view =
                view().apply {
                    setTerminalPaddingDp(12, 8, 6)
                    onMouseClick = { col, row, _, _ -> calls += col to row }
                    onActivate = { activated++ }
                }
            val x = (12 * density).toInt() + metrics.cellWidth * 4.5f
            val y = (8 * density).toInt() + metrics.cellHeight * 3.5f
            event(view, MotionEvent.ACTION_DOWN, x, y, 0)
            assertTrue(calls.isEmpty())
            event(view, MotionEvent.ACTION_UP, x, y, 30)
            assertEquals(listOf(4 to 3), calls)
            assertEquals(0, activated)
            event(view, MotionEvent.ACTION_DOWN, 1f, 1f, 100)
            event(view, MotionEvent.ACTION_UP, 1f, 1f, 130)
            assertEquals(1, calls.size)
        }
    }

    @Test
    fun pointerClickIsDeliveredOnceAndKeepsTheSoftKeyboardClosed() {
        instrumentation.runOnMainSync {
            var clicks = 0
            var target = "a"
            val view =
                view().apply {
                    mouseTarget = { target }
                    onMouseClick = { _, _, _, _ -> clicks++ }
                }
            view.primaryPointerButton(true, 5f, 5f)
            assertEquals(0, clicks)
            view.primaryPointerButton(false, 5f, 5f)
            assertEquals(1, clicks)
            assertFalse(view.hasFocus())
            view.primaryPointerButton(true, 5f, 5f)
            target = "b"
            view.primaryPointerButton(false, 5f, 5f)
            assertEquals(1, clicks)
            view.primaryPointerButton(true, 5f, 5f)
            view.primaryPointerButton(false, 100f, 5f)
            assertEquals(1, clicks)
            fun mouseTouch(action: Int, time: Long) {
                val event = MotionEvent.obtain(100, time, action, 5f, 5f, 0)
                event.source = InputDevice.SOURCE_MOUSE
                try {
                    view.onTouchEvent(event)
                } finally {
                    event.recycle()
                }
            }
            mouseTouch(MotionEvent.ACTION_DOWN, 100)
            view.primaryPointerButton(true, 5f, 5f)
            view.primaryPointerButton(false, 5f, 5f)
            mouseTouch(MotionEvent.ACTION_UP, 130)
            assertEquals(2, clicks)
            assertFalse(view.hasFocus())
        }
    }

    @Test
    fun swipesCancelAndLongPressSelectsWithoutSendingMouseClicks() {
        instrumentation.runOnMainSync {
            var clicks = 0
            var wheels = 0
            var panes = 0
            val view =
                view().apply {
                    screen =
                        frame()
                            .copy(
                                cells = listOf(TerminalCell(0, 0, "x", -1, 0, 0, 1)),
                                lines =
                                    listOf(listOf(TerminalCell(0, 0, "x", -1, 0, 0, 1))) +
                                        List(11) { emptyList() },
                            )
                    onMouseClick = { _, _, _, _ -> clicks++ }
                    onWheel = { n, _, _, _, _ -> wheels += n }
                    onPaneDragEnd = { _, _ -> panes++ }
                }
            event(view, MotionEvent.ACTION_DOWN, 50f, 50f, 0)
            event(view, MotionEvent.ACTION_MOVE, 400f, 50f, 100)
            event(view, MotionEvent.ACTION_UP, 400f, 50f, 200)
            event(view, MotionEvent.ACTION_DOWN, 50f, 50f, 300)
            event(view, MotionEvent.ACTION_MOVE, 50f, 350f, 400)
            event(view, MotionEvent.ACTION_UP, 50f, 350f, 500)
            event(view, MotionEvent.ACTION_DOWN, 5f, 5f, 600)
            assertTrue(view.performLongClick())
            event(view, MotionEvent.ACTION_UP, 5f, 5f, 1200)
            assertEquals(0, clicks)
            assertEquals(1, panes)
            assertTrue(wheels < 0)
            assertNotNull(view.selectedText)
        }
    }

    @Test
    fun navigationReconnectResizeAndModeChangesCancelAPendingTap() {
        instrumentation.runOnMainSync {
            var clicks = 0
            var activated = 0
            var target = "pane-a:epoch-1"
            val view =
                view().apply {
                    mouseTarget = { target }
                    onMouseClick = { _, _, _, _ -> clicks++ }
                    onActivate = { activated++ }
                }
            event(view, MotionEvent.ACTION_DOWN, 5f, 5f, 0)
            target = "pane-a:epoch-2"
            event(view, MotionEvent.ACTION_UP, 5f, 5f, 30)
            event(view, MotionEvent.ACTION_DOWN, 5f, 5f, 100)
            view.screen = frame().copy(columns = 30)
            event(view, MotionEvent.ACTION_UP, 5f, 5f, 130)
            view.screen = frame()
            event(view, MotionEvent.ACTION_DOWN, 5f, 5f, 200)
            view.screen = frame(0)
            event(view, MotionEvent.ACTION_UP, 5f, 5f, 230)
            view.screen = frame()
            event(view, MotionEvent.ACTION_DOWN, 5f, 5f, 300)
            event(view, MotionEvent.ACTION_CANCEL, 5f, 5f, 310)
            event(view, MotionEvent.ACTION_UP, 5f, 5f, 330)
            event(view, MotionEvent.ACTION_DOWN, 5f, 5f, 400)
            view.controlled = false
            event(view, MotionEvent.ACTION_UP, 5f, 5f, 430)
            assertEquals(0, clicks)
            assertEquals(0, activated)
        }
    }

    @Test
    fun takingControlFromOutputDoesNotRequestKeyboard() {
        instrumentation.runOnMainSync {
            val requests = mutableListOf<Boolean>()
            var clicks = 0
            val metrics = TerminalFonts(instrumentation.targetContext).configure(Paint(), 10f)
            val view =
                view().apply {
                    controlled = false
                    onActivate = { requests += it }
                    onMouseClick = { _, _, _, _ -> clicks++ }
                }
            fun tap(row: Int, time: Long) {
                val y = metrics.cellHeight * (row + .5f)
                event(view, MotionEvent.ACTION_DOWN, 5f, y, time)
                event(view, MotionEvent.ACTION_UP, 5f, y, time + 30)
            }
            tap(0, 0)
            view.screen = frame(0).copy(cursorX = 2, cursorY = 3)
            tap(2, 100)
            tap(3, 200)
            assertEquals(listOf(false, false, true), requests)
            assertEquals(0, clicks)
        }
    }

    @Test
    fun unmarkedShellOpensKeyboardOnlyAtLiveInputRow() {
        instrumentation.runOnMainSync {
            val metrics = TerminalFonts(instrumentation.targetContext).configure(Paint(), 10f)
            val shell = frame(0).copy(cursorX = 2, cursorY = 3)
            val view = view().apply { screen = shell }
            Assume.assumeTrue("Detached view cannot clear focus", canClearFocus(view))
            fun tap(row: Int, time: Long, x: Float = 5f) {
                val y = metrics.cellHeight * (row + .5f)
                event(view, MotionEvent.ACTION_DOWN, x, y, time)
                event(view, MotionEvent.ACTION_UP, x, y, time + 30)
            }
            tap(2, 0)
            assertFalse(view.hasFocus())
            tap(3, 100, 1200f)
            assertFalse(view.hasFocus())
            tap(3, 200)
            assertTrue(view.hasFocus())
            view.clearFocus()
            view.screen = shell.copy(scroll = TerminalScroll(100, 0, 12))
            tap(3, 300)
            assertFalse(view.hasFocus())
            view.screen = shell.copy(inputFlags = 2)
            tap(3, 400)
            assertFalse(view.hasFocus())
        }
    }

    @Test
    fun webLinksKeepPriorityOverApplicationMouseClicks() {
        instrumentation.runOnMainSync {
            var opened = 0
            var clicks = 0
            val url = "https://example.com/"
            val cells = url.mapIndexed { i, c -> TerminalCell(i, 0, c.toString(), -1, 0, 0, 1) }
            val view =
                view().apply {
                    screen = TerminalScreen(40, 12, null, null, -1, 0, cells, inputFlags = 1)
                    onOpenLink = { opened++ }
                    onMouseClick = { _, _, _, _ -> clicks++ }
                }
            event(view, MotionEvent.ACTION_DOWN, 5f, 5f, 0)
            event(view, MotionEvent.ACTION_UP, 5f, 5f, 30)
            assertEquals(1, opened)
            assertEquals(0, clicks)
        }
    }

    @Test
    fun onlyAValidCursorRowTapOffersKeyboardWhileStillSendingTheMouseClick() {
        instrumentation.runOnMainSync {
            val calls = mutableListOf<String>()
            var target = "pane-a"
            val metrics = TerminalFonts(instrumentation.targetContext).configure(Paint(), 10f)
            val view =
                view().apply {
                    screen = frame().copy(cursorX = 2, cursorY = 3)
                    mouseTarget = { target }
                    onMouseClick = { _, row, _, _ -> calls += "mouse:$row" }
                    onCursorTap = { calls += "cursor" }
                }
            fun tap(row: Int, time: Long) {
                val x = metrics.cellWidth * 4.5f
                val y = metrics.cellHeight * (row + .5f)
                event(view, MotionEvent.ACTION_DOWN, x, y, time)
                event(view, MotionEvent.ACTION_UP, x, y, time + 30)
            }
            tap(2, 0)
            assertEquals(listOf("mouse:2"), calls)
            tap(3, 100)
            assertEquals(listOf("mouse:2", "mouse:3", "cursor"), calls)
            calls.clear()
            val y = metrics.cellHeight * 3.5f
            event(view, MotionEvent.ACTION_DOWN, 5f, y, 200)
            target = "pane-b"
            event(view, MotionEvent.ACTION_UP, 5f, y, 230)
            assertTrue(calls.isEmpty())
            view.screen = frame()
            tap(3, 300)
            assertEquals(listOf("mouse:3"), calls)
        }
    }

    @Test
    fun hiddenPiCursorCanOpenKeyboardWithoutChangingMouseClicksOrOtherEditors() {
        instrumentation.runOnMainSync {
            val metrics = TerminalFonts(instrumentation.targetContext).configure(Paint(), 10f)
            var cursorTaps = 0
            var clicks = 0
            val view =
                view().apply {
                    screen = frame(7).copy(cursorAnchorX = 2, cursorAnchorY = 8)
                    onCursorTap = { cursorTaps++ }
                    onMouseClick = { _, _, _, _ -> clicks++ }
                }
            fun tap(row: Int, time: Long) {
                val y = metrics.cellHeight * (row + .5f)
                event(view, MotionEvent.ACTION_DOWN, 5f, y, time)
                event(view, MotionEvent.ACTION_UP, 5f, y, time + 30)
            }
            assertNull(view.keyboardFocus)
            tap(8, 0)
            assertEquals(0, cursorTaps)
            view.allowHiddenCursorInput =
                ProgramProfile.resolve(ProgramIdentity.resolve(listOf("pi"))).hiddenCursorInput
            assertNotNull(view.keyboardFocus)
            tap(7, 100)
            assertEquals(0, cursorTaps)
            tap(8, 200)
            assertEquals(1, cursorTaps)
            assertEquals(3, clicks)
            view.screen = view.screen!!.copy(scroll = TerminalScroll(100, 0, 12))
            tap(8, 300)
            assertEquals(1, cursorTaps)
            assertNull(view.keyboardFocus)
            view.allowHiddenCursorInput = false
            view.screen = view.screen!!.copy(scroll = TerminalScroll(12, 0, 12))
            assertNull(view.keyboardFocus)
        }
    }

    @Test
    fun cornerControlsConsumeTheTapAndKeepSwipesAndScrolling() {
        instrumentation.runOnMainSync {
            var reveals = 0
            var clicks = 0
            var wheels = 0
            var swipes = 0
            val view =
                view().apply {
                    onPaneControlsTap = { x, y ->
                        if (x > width - 80 && y < 80) {
                            reveals++
                            true
                        } else false
                    }
                    onMouseClick = { _, _, _, _ -> clicks++ }
                    onWheel = { lines, _, _, _, _ -> wheels += lines }
                    onPaneDragEnd = { _, _ -> swipes++ }
                }
            val corner = view.width - 20f
            event(view, MotionEvent.ACTION_DOWN, corner, 20f, 0)
            event(view, MotionEvent.ACTION_UP, corner, 20f, 30)
            assertEquals(1, reveals)
            assertEquals(0, clicks)
            event(view, MotionEvent.ACTION_DOWN, 5f, 5f, 100)
            event(view, MotionEvent.ACTION_UP, 5f, 5f, 130)
            assertEquals(1, clicks)
            event(view, MotionEvent.ACTION_DOWN, corner, 20f, 200)
            event(view, MotionEvent.ACTION_MOVE, corner - 350f, 20f, 300)
            event(view, MotionEvent.ACTION_UP, corner - 350f, 20f, 400)
            event(view, MotionEvent.ACTION_DOWN, corner, 20f, 500)
            event(view, MotionEvent.ACTION_MOVE, corner, 320f, 600)
            event(view, MotionEvent.ACTION_UP, corner, 320f, 700)
            assertEquals(1, reveals)
            assertEquals(1, clicks)
            assertEquals(1, swipes)
            assertTrue(wheels < 0)
        }
    }
}

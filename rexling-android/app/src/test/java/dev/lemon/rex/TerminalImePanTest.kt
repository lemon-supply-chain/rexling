package dev.lemon.rex

import org.junit.Assert.*
import org.junit.Test

class TerminalImePanTest {
    @Test
    fun compactHeaderKeepsShortOutputBelowTheGlass() {
        val pan = TerminalImePanState()
        val prompt = TerminalKeyboardFocus(20f, 40f, 40f)
        assertEquals(-40, pan.position(null, 500, 200, 40))
        assertEquals(-40, pan.position(prompt, 500, 200, 40))
        // Multi-row jump to a bottom prompt brings its footer and status lines into view (884ab44).
        assertEquals(150, pan.position(TerminalKeyboardFocus(600f, 620f, 650f), 500, 200, 40))
        assertEquals(150, pan.position(null, 500, 200, 40))
        assertEquals(-40, pan.position(prompt, 500, 200, 40))
        assertEquals(0, pan.position(prompt, 740, 0))

        // A single-row arrow only advances to the cursor line rather than jumping to status lines.
        val step = TerminalImePanState()
        assertEquals(0, step.position(TerminalKeyboardFocus(470f, 490f, 500f), 500, 200, 40))
        assertEquals(10, step.position(TerminalKeyboardFocus(490f, 510f, 540f), 500, 200, 40))
    }

    @Test
    fun editorCursorMovesDoNotRecenterAVisibleViewport() {
        val pan = TerminalImePanState()
        assertEquals(400, pan.position(TerminalKeyboardFocus(600f, 620f, 1000f), 500, 400))
        for (y in listOf(590f, 500f, 499f, 480f, 600f, 700f)) {
            assertEquals(400, pan.position(TerminalKeyboardFocus(y, y + 20, 1000f), 500, 400))
        }
        assertEquals(399, pan.position(TerminalKeyboardFocus(399f, 419f, 1000f), 500, 400))
        assertEquals(399, pan.position(null, 500, 400))
        assertEquals(400, pan.position(TerminalKeyboardFocus(880f, 901f, 1000f), 500, 400))
        assertEquals(0, pan.position(null, 900, 0))
    }
}

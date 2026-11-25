package dev.lemon.rex

import org.junit.Assert.*
import org.junit.Test

class TerminalScrollMotionTest {
    private val history = TerminalScroll(120, 50, 20)

    @Test
    fun fractionalDragIsImmediateAndSurvivesTheNativeRowUpdate() {
        val motion = TerminalScrollMotion()
        assertEquals(0, motion.drag(7f, history, 20f))
        assertEquals(7f, motion.slide(history, 20f, 4, 4, true), 0f)
        assertEquals(1, motion.drag(18f, history, 20f))
        assertEquals(25f, motion.slide(history, 20f, 4, 4, true), 0f)
        assertEquals(5f, motion.slide(history.copy(offset = 51), 20f, 4, 4, true), 0f)
        motion.settle()
        assertEquals(0f, motion.slide(history.copy(offset = 51), 20f, 4, 4, false), 0f)
        assertNull(motion.target)
    }

    @Test
    fun pendingMovementNeverExposesPixelsOutsideTheCapturedRows() {
        val motion = TerminalScrollMotion()
        assertEquals(20, motion.drag(400f, history, 20f))
        assertEquals(80f, motion.slide(history, 20f, 4, 4, true), 0f)
        assertEquals(20f, motion.slide(history, 20f, 1, 1, true), 0f)
        motion.reset()
        motion.drag(-400f, history, 20f)
        assertEquals(-80f, motion.slide(history, 20f, 4, 4, true), 0f)
    }

    @Test
    fun boundariesDoNotAccumulateOverscrollOrSendRedundantRows() {
        val motion = TerminalScrollMotion()
        assertEquals(0, motion.drag(-500f, history.copy(offset = 0), 20f))
        assertEquals(0f, motion.remainder, 0f)
        motion.reset()
        assertEquals(0, motion.drag(500f, history.copy(offset = 100), 20f))
        assertEquals(0f, motion.remainder, 0f)
        assertEquals(-1, motion.drag(-20f, history.copy(offset = 100), 20f))
    }

    @Test
    fun resetAndInvalidGeometryCannotLeakMovementToAnotherPane() {
        val motion = TerminalScrollMotion()
        motion.drag(27f, history, 20f)
        motion.reset()
        assertNull(motion.target)
        assertEquals(0f, motion.remainder, 0f)
        assertEquals(0, motion.drag(Float.NaN, history, 20f))
        assertEquals(0, motion.drag(20f, history, 0f))
        assertEquals(0, motion.drag(20f, TerminalScroll(20, 0, 20), 20f))
    }
}

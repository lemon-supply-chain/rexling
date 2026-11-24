package dev.lemon.rex

import org.junit.Assert.*
import org.junit.Test

class PaneGestureTest {
    private fun gesture() = PaneGesture(tapSlop = 8f, swipeDistance = 48f)

    @Test
    fun horizontalSwipesChooseTheExpectedNeighbour() {
        val g = gesture()
        g.begin(100f, 100f)
        assertEquals(PaneGesture.Result.NEXT, g.finish(30f, 110f))
        g.begin(100f, 100f)
        assertEquals(PaneGesture.Result.PREVIOUS, g.finish(180f, 95f))
    }

    @Test
    fun tapsRemainTapsAndShortDragsDoNotOpenTheKeyboard() {
        val g = gesture()
        g.begin(100f, 100f)
        assertEquals(PaneGesture.Result.TAP, g.finish(103f, 103f))
        g.begin(100f, 100f)
        assertEquals(PaneGesture.Result.NONE, g.finish(120f, 101f))
    }

    @Test
    fun verticalAndCancelledGesturesNeverSwitchPanes() {
        val g = gesture()
        g.begin(100f, 100f)
        g.move(110f, 200f)
        assertEquals(PaneGesture.Result.NONE, g.finish(250f, 110f))
        g.begin(100f, 100f)
        assertEquals(PaneGesture.Result.NONE, g.finish(170f, 180f))
        g.begin(100f, 100f)
        g.cancel()
        assertEquals(PaneGesture.Result.NONE, g.finish(10f, 100f))
        assertEquals(PaneGesture.Result.NONE, g.finish(10f, 100f))
    }
}

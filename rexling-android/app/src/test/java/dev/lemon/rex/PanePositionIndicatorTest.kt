package dev.lemon.rex

import org.junit.Assert.*
import org.junit.Test

class PanePositionIndicatorTest {
    @Test
    fun expandingCapsulesTransferWidthWithoutChangingTheTrackSize() {
        for (progress in listOf(0f, .1f, .25f, .5f, .75f, 1f)) {
            val marks = paneIndicatorMarks(0, 3, progress)
            assertEquals(1f, marks.sumOf { it.active.toDouble() }.toFloat(), .0001f)
            assertEquals(25f, marks.sumOf { it.width.toDouble() }.toFloat(), .0001f)
            assertEquals(15f - 10f * progress, marks[0].width, .0001f)
            assertEquals(5f + 10f * progress, marks[1].width, .0001f)
        }
    }

    @Test
    fun previousAndNextWrapAtTheEnds() {
        assertEquals(listOf(.75f, 0f, .25f), paneIndicatorMarks(0, 3, -.25f).map { it.active })
        assertEquals(listOf(.25f, 0f, .75f), paneIndicatorMarks(2, 3, .25f).map { it.active })
    }

    @Test
    fun settlingAndTheCommittedPaneHaveIdenticalGeometry() {
        assertEquals(paneIndicatorMarks(0, 3, 1f), paneIndicatorMarks(1, 3, 0f))
        assertEquals(paneIndicatorMarks(0, 3, -1f), paneIndicatorMarks(2, 3, 0f))
    }

    @Test
    fun cancelledSwipesReturnTheWidthToTheOriginalCapsule() {
        val outgoing = listOf(.4f, .2f, 0f).map { paneIndicatorMarks(1, 3, it)[1].active }
        assertEquals(listOf(.6f, .8f, 1f), outgoing)
    }

    @Test
    fun emptySingleAndLongPaneListsRemainBounded() {
        assertTrue(paneIndicatorMarks(0, 0, 0f).isEmpty())
        assertEquals(listOf(PaneIndicatorMark(15f, 1f)), paneIndicatorMarks(0, 1, .7f))
        for (index in 0..19) {
            val marks = paneIndicatorMarks(index, 20, .5f)
            assertEquals(7, marks.size)
            assertEquals(1f, marks.sumOf { it.active.toDouble() }.toFloat(), .0001f)
            assertTrue(marks.all { it.width in 5f..15f })
        }
    }

    @Test
    fun overscrollCannotProduceNegativeOrExcessWidth() {
        assertEquals(paneIndicatorMarks(0, 3, 1f), paneIndicatorMarks(0, 3, 4f))
        assertEquals(paneIndicatorMarks(0, 3, -1f), paneIndicatorMarks(0, 3, -4f))
    }
}

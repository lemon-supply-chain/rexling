package dev.lemon.rex

import org.junit.Assert.*
import org.junit.Test

class LatestDisplayFrameTest {
    private class Clock : DisplayFrameClock {
        val pending = mutableListOf<() -> Unit>()
        var posts = 0

        override fun post(callback: () -> Unit) {
            pending += callback
            posts++
        }

        override fun cancel(callback: () -> Unit) {
            pending.removeAll { it === callback }
        }

        fun tick() {
            val callbacks = pending.toList()
            pending.clear()
            callbacks.forEach { it() }
        }
    }

    @Test
    fun aBurstPresentsOnlyTheLatestCompleteSnapshotWithOneCallback() {
        val clock = Clock()
        val shown = mutableListOf<Int>()
        val frames = LatestDisplayFrame(clock, shown::add)
        repeat(1000) { frames.offer(it) }
        assertEquals(1, clock.posts)
        assertEquals(1, clock.pending.size)
        assertTrue(shown.isEmpty())
        clock.tick()
        assertEquals(listOf(999), shown)
        assertTrue(clock.pending.isEmpty())
        repeat(4) { clock.tick() }
        assertEquals(1, clock.posts)
    }

    @Test
    fun clearingForNavigationDropsTheOldTargetAndCancelsItsCallback() {
        val clock = Clock()
        val shown = mutableListOf<String>()
        val frames = LatestDisplayFrame(clock, shown::add)
        frames.offer("old pane")
        frames.clear()
        clock.tick()
        assertTrue(shown.isEmpty())
        frames.offer("new pane")
        clock.tick()
        assertEquals(listOf("new pane"), shown)
    }

    @Test
    fun anAlreadyDispatchedCanceledCallbackCannotConsumeANewerFrame() {
        val clock = Clock()
        val shown = mutableListOf<String>()
        val frames = LatestDisplayFrame(clock, shown::add)
        frames.offer("old connection")
        val stale = clock.pending.single()
        frames.clear()
        frames.offer("new connection")
        stale()
        assertTrue(shown.isEmpty())
        assertEquals(1, clock.pending.size)
        clock.tick()
        assertEquals(listOf("new connection"), shown)
    }

    @Test
    fun aFrameArrivingDuringPresentationSchedulesExactlyOneFollowingTick() {
        val clock = Clock()
        val shown = mutableListOf<Int>()
        lateinit var frames: LatestDisplayFrame<Int>
        frames =
            LatestDisplayFrame(clock) { value ->
                shown += value
                if (value == 1) {
                    frames.offer(2)
                    frames.offer(3)
                }
            }
        frames.offer(1)
        clock.tick()
        assertEquals(listOf(1), shown)
        assertEquals(1, clock.pending.size)
        clock.tick()
        assertEquals(listOf(1, 3), shown)
        assertTrue(clock.pending.isEmpty())
    }

    @Test
    fun hiddenOrClosedPresentationCanBeClearedRepeatedlyWithoutAnotherTick() {
        val clock = Clock()
        val frames = LatestDisplayFrame<Int>(clock) { error("must not present") }
        frames.offer(1)
        frames.clear()
        frames.clear()
        clock.tick()
        assertTrue(clock.pending.isEmpty())
    }
}

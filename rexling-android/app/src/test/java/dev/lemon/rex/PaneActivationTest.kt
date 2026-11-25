package dev.lemon.rex

import org.junit.Assert.*
import org.junit.Test

class PaneActivationTest {
    private val screen = TerminalScreen(80, 24, null, null, -1, 0xff000000.toInt(), emptyList())
    private val ready =
        AppState(
            phase = "connected",
            selectedSession = "s",
            selectedBlock = "a",
            screen = screen,
            navigationId = 1,
        )
    private val request = NavigationRequest(id = 1)
    private val size = 40 to 30

    @Test
    fun waitsForNavigationSnapshotAndPhoneSize() {
        val a = PaneActivation()
        assertFalse(a.shouldTake(ready, NavigationRequest(block = "b", id = 2), true, size))
        assertFalse(a.shouldTake(ready.copy(screen = null), request, true, size))
        assertFalse(a.shouldTake(ready, request, true, 0 to 0))
        assertTrue(a.shouldTake(ready, request, true, size))
    }

    @Test
    fun claimsOnceAndDoesNotFightAnotherClient() {
        val a = PaneActivation()
        assertTrue(a.shouldTake(ready, request, true, size))
        assertFalse(a.shouldTake(ready.copy(controlled = true), request, true, size))
        assertFalse(a.shouldTake(ready, request, true, size))
        a.navigate()
        assertTrue(a.shouldTake(ready, request, true, size))
    }

    @Test
    fun releaseKeepsViewingUntilNavigationIncludingAfterResume() {
        val a = PaneActivation()
        a.release()
        a.resume()
        assertFalse(a.shouldTake(ready, request, true, size))
        a.navigate()
        assertTrue(a.shouldTake(ready, request, true, size))
    }

    @Test
    fun hiddenAndPausedConnectionsNeverClaim() {
        val a = PaneActivation()
        assertFalse(a.shouldTake(ready, request, false, size))
        assertFalse(a.shouldTake(ready.copy(phase = "paused"), request, true, size))
        assertTrue(a.shouldTake(ready, request, true, size))
        a.resume()
        assertTrue(a.shouldTake(ready, request, true, size))
    }

    @Test
    fun anOldOwnedFrameDoesNotConsumeThePendingActivation() {
        val a = PaneActivation()
        assertFalse(a.shouldTake(ready.copy(error = "Missing pane"), request, true, size))
        assertFalse(
            a.shouldTake(
                ready.copy(controlled = true),
                NavigationRequest(block = "b", id = 2),
                true,
                size,
            ),
        )
        assertTrue(
            a.shouldTake(
                ready.copy(selectedBlock = "b", navigationId = 2),
                NavigationRequest(id = 2),
                true,
                size,
            ),
        )
    }

    @Test
    fun returningToAnOwnedPaneDoesNotFightAnExplicitTakeover() {
        val a = PaneActivation()
        assertFalse(a.shouldTake(ready.copy(controlled = true, screen = null), request, true, size))
        assertFalse(a.shouldTake(ready, request, true, size))
        a.navigate()
        assertTrue(a.shouldTake(ready, request, true, size))
    }
}

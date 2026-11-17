package dev.lemon.rex

import org.junit.Assert.*
import org.junit.Test

class NavigationRequestTest {
    private val emptyScreen = TerminalScreen(80, 24, null, null, -1, 0xff000000.toInt(), emptyList())
    @Test fun anEarlierTabReplyCannotClearTheLatestTap() {
        val pending = NavigationRequest(block = "latest")
        val oldReply = AppState(phase = "connected", selectedBlock = "earlier", screen = emptyScreen)
        assertEquals(pending, pending.acknowledge(oldReply))
        assertFalse(pending.acknowledge(oldReply.copy(selectedBlock = "latest")).pending)
    }
    @Test fun inputWaitsForTheNewSnapshotRatherThanOnlyTheBlockId() {
        val pending = NavigationRequest(block = "target")
        val loading = AppState(phase = "connected", selectedBlock = "target")
        assertTrue(pending.acknowledge(loading).pending)
        assertFalse(pending.acknowledge(loading.copy(screen = emptyScreen)).pending)
    }
    @Test fun sessionRepliesAndErrorsResolveOnlyTheRelevantIntent() {
        val pending = NavigationRequest(session = "new")
        val prior = AppState(phase = "connected", selectedSession = "old")
        assertEquals(pending, pending.acknowledge(prior.copy(revision = 3)))
        assertFalse(pending.acknowledge(prior.copy(selectedSession = "new")).pending)
        assertFalse(pending.acknowledge(prior.copy(error = "session closed")).pending)
        assertFalse(pending.acknowledge(AppState()).pending)
    }
    @Test fun returningToTheOriginalTabWaitsForItsOwnAcknowledgement() {
        val request = NavigationRequest(block = "original", id = 3)
        val old = AppState(phase = "connected", selectedBlock = "original", screen = emptyScreen, navigationId = 1)
        assertTrue(request.acknowledge(old).pending)
        assertTrue(request.acknowledge(old.copy(selectedBlock = "middle", navigationId = 2)).pending)
        assertFalse(request.acknowledge(old.copy(navigationId = 3)).pending)
    }

}

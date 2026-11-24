package dev.lemon.rex

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ActivityFocusPolicyTest {
    private val server = ServerProfile.create("Mac", "https://mac.example", "mac")
    private val state = AppState(phase = "connected", selectedSession = "s", selectedBlock = "p")
    private val visible = WatchedPanes(server.id, server.endpoint, "s", "p", setOf("p"))

    private fun watched(
        foreground: Boolean = true,
        panes: WatchedPanes? = visible,
        navigating: Boolean = false,
        interactive: Boolean = true,
        locked: Boolean = false,
    ) =
        ActivityFocusPolicy.watched(
            server,
            state,
            panes,
            foreground,
            navigating,
            interactive,
            locked,
        )

    private fun record(session: String = "s", block: String = "p") =
        ActivityRecord.parse(
            JSONObject().put("state", "done").put("session", session).put("block", block),
        )!!

    @Test
    fun onlyTheVisibleExactPaneOnTheExactServerIsWatched() {
        val focus = watched()!!
        assertTrue(focus.contains(server, record()))
        assertFalse(focus.contains(server, record(block = "other")))
        assertFalse(focus.contains(server, record(session = "other")))
        assertFalse(focus.contains(server.copy(id = "other"), record()))
        assertFalse(focus.contains(server.copy(endpoint = "https://other.example"), record()))
    }

    @Test
    fun backgroundLockDrawersAndPendingNavigationHaveNoWatchedPanes() {
        assertNull(watched(foreground = false))
        assertNull(watched(panes = null))
        assertNull(watched(panes = visible.copy(blocks = emptySet())))
        assertNull(watched(navigating = true))
        assertNull(watched(interactive = false))
        assertNull(watched(locked = true))
        assertNull(
            ActivityFocusPolicy.watched(
                server,
                state.copy(phase = "reconnecting"),
                visible,
                true,
                false,
                true,
                false,
            ),
        )
        assertNull(
            ActivityFocusPolicy.watched(
                server,
                state.copy(selectedBlock = ""),
                visible,
                true,
                false,
                true,
                false,
            ),
        )
        assertNull(watched(panes = visible.copy(selectedBlock = "old")))
        assertNull(watched(panes = visible.copy(session = "old")))
    }

    @Test
    fun watchedCompletionsAreAcknowledgedWhileOtherPanesKeepTheirBadge() {
        val active = record()
        val split = record(block = "split")
        val other = record(block = "other")
        val working = record(block = "work").copy(state = ActivityState.WORKING)
        val records = listOf(active, split, other, working)
        val seen = mutableSetOf<String>()
        val watched = watched(panes = visible.copy(blocks = setOf("p", "split")))
        ActivityFocusPolicy.acknowledge(server, records, watched) { it.key in seen }
            .forEach { seen += it.key }
        assertEquals(setOf(active.key, split.key), seen)
        assertEquals(1, ActivityFocusPolicy.unread(records) { it.key in seen })
        assertTrue(ActivityFocusPolicy.acknowledge(server, records, null) { false }.isEmpty())
        assertTrue(
            ActivityFocusPolicy.acknowledge(server.copy(id = "other"), records, watched()) { false }
                .isEmpty(),
        )
    }
}

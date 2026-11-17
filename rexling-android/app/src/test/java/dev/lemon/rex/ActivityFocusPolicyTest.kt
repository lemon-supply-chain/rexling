package dev.lemon.rex

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

class ActivityFocusPolicyTest {
    private val server=ServerProfile.create("Mac","https://mac.example","mac")
    private val state=AppState(phase="connected",selectedSession="s",selectedBlock="p")
    private fun watched(foreground: Boolean=true,obscured: Boolean=false,screen: Boolean=true,navigating: Boolean=false,interactive: Boolean=true,locked: Boolean=false)=
        ActivityFocusPolicy.watched(server,state,foreground,obscured,screen,navigating,interactive,locked)
    private fun record(session: String="s",block: String="p")=ActivityRecord.parse(JSONObject().put("state","done").put("session",session).put("block",block))!!
    @Test fun onlyTheVisibleExactPaneOnTheExactServerIsWatched() {
        val focus=watched()!!
        assertTrue(focus.contains(server,record()))
        assertFalse(focus.contains(server,record(block="other")))
        assertFalse(focus.contains(server,record(session="other")))
        assertFalse(focus.contains(server.copy(id="other"),record()))
        assertFalse(focus.contains(server.copy(endpoint="https://other.example"),record()))
    }
    @Test fun backgroundLockDrawersAndPendingNavigationHaveNoWatchedPane() {
        assertNull(watched(foreground=false));assertNull(watched(obscured=true));assertNull(watched(screen=false))
        assertNull(watched(navigating=true));assertNull(watched(interactive=false));assertNull(watched(locked=true))
        assertNull(ActivityFocusPolicy.watched(server,state.copy(phase="reconnecting"),true,false,true,false,true,false))
        assertNull(ActivityFocusPolicy.watched(server,state.copy(selectedBlock=""),true,false,true,false,true,false))
    }
    @Test fun watchedCompletionsAreAcknowledgedWhileOtherPanesKeepTheirBadge() {
        val active=record();val other=record(block="other");val working=record(block="work").copy(state=ActivityState.WORKING)
        val records=listOf(active,other,working);val seen=mutableSetOf<String>()
        ActivityFocusPolicy.acknowledge(server,records,watched()) {it.key in seen}.forEach {seen+=it.key}
        assertTrue(active.key in seen);assertFalse(other.key in seen)
        assertEquals(1,ActivityFocusPolicy.unread(records) {it.key in seen})
        assertTrue(ActivityFocusPolicy.acknowledge(server,records,null) {false}.isEmpty())
        assertTrue(ActivityFocusPolicy.acknowledge(server.copy(id="other"),records,watched()) {false}.isEmpty())
    }
}

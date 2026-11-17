package dev.lemon.rex

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

class ActivityBadgePolicyTest {
    private val server=ServerProfile.create("Mac","https://mac.example","mac")
    private val windows=listOf(WindowItem("a","A",listOf(BlockItem("a1","shell"),BlockItem("a2","agent"))),WindowItem("b","B",listOf(BlockItem("b1","shell"))))
    private fun record(block: String="a1",session: String="s",state: String="done",id: String="")=ActivityRecord.parse(JSONObject()
        .put("session",session).put("block",block).put("window","stale-window").put("state",state).put("id",id))!!
    private fun source(items: List<ActivityRecord>,profile: ServerProfile=server,seen: Set<String> = emptySet(),connected: Boolean=true)=ActivitySource(profile,connected,false,"",false,items,seen)
    @Test fun currentSessionActivityGoesToItsTabInsteadOfTheEye() {
        val result=ActivityBadgePolicy.route(listOf(source(listOf(record(),record("b1")))),server,"s",windows)
        assertEquals(mapOf("a" to 1,"b" to 1),result.tabs);assertEquals(0,result.elsewhere)
    }
    @Test fun otherSessionsAndOtherServersStayOnTheEyeEvenWhenIdsMatch() {
        val remote=server.copy(id="remote",endpoint="https://remote.example")
        val sources=listOf(source(listOf(record(),record(session="other"))),source(listOf(record()),remote))
        val result=ActivityBadgePolicy.route(sources,server,"s",windows)
        assertEquals(mapOf("a" to 1),result.tabs);assertEquals(2,result.elsewhere)
        val changed=ActivityBadgePolicy.route(listOf(source(listOf(record()),server.copy(endpoint="https://old.example"))),server,"s",windows)
        assertTrue(changed.tabs.isEmpty());assertEquals(1,changed.elsewhere)
    }
    @Test fun seenWorkingAndProgressOnlyRecordsCannotCreateUnreadDots() {
        val seen=record();val work=record("a2",state="working");val progress=record("b1").copy(progressOnly=true)
        val sources=listOf(source(listOf(seen,work,progress),seen=setOf(seen.key)),source(listOf(record("a2")),connected=false))
        assertEquals(ActivityBadges(),ActivityBadgePolicy.route(sources,server,"s",windows))
    }
    @Test fun hiddenPanesAndChildTasksShareTheOwningTabDot() {
        val items=listOf(record("a2",state="blocked",id="one"),record("a2",state="error",id="two"))
        val result=ActivityBadgePolicy.route(listOf(source(items+items)),server,"s",windows)
        assertEquals(mapOf("a" to 2),result.tabs);assertEquals(0,result.elsewhere)
    }
    @Test fun paneMovesUseCurrentMembershipAndClosedPanesFallBackToTheEye() {
        val moved=listOf(windows[0].copy(blocks=listOf(BlockItem("a1","shell"))),windows[1].copy(blocks=listOf(BlockItem("a2","agent"),BlockItem("b1","shell"))))
        val sources=listOf(source(listOf(record("a2"),record("closed"))))
        val result=ActivityBadgePolicy.route(sources,server,"s",moved)
        assertEquals(mapOf("b" to 1),result.tabs);assertEquals(1,result.elsewhere)
    }
    @Test fun switchingSessionsReroutesWithoutAcknowledgingOrLosingRecords() {
        val items=listOf(record(),record("b1",session="other"));val sources=listOf(source(items))
        val first=ActivityBadgePolicy.route(sources,server,"s",windows)
        val second=ActivityBadgePolicy.route(sources,server,"other",windows)
        assertEquals(mapOf("a" to 1),first.tabs);assertEquals(mapOf("b" to 1),second.tabs)
        assertEquals(1,first.elsewhere);assertEquals(1,second.elsewhere);assertTrue(sources.single().seen.isEmpty())
    }
}

package dev.lemon.rex

import org.junit.Assert.*
import org.junit.Test

class TabOrderTest {
    @Test fun relativeMovesPreserveEveryOtherTabAndSupportTheEnd() {
        val ids=listOf("a","b","c","d")
        assertEquals(listOf("c","a","b","d"),moveTabBefore(ids,"c","a"))
        assertEquals(listOf("a","c","d","b"),moveTabBefore(ids,"b",null))
        assertEquals(ids,moveTabBefore(ids,"a","b"))
        assertNull(moveTabBefore(ids,"a","a"));assertNull(moveTabBefore(ids,"missing",null))
        assertNull(moveTabBefore(ids,"a","missing"));assertNull(moveTabBefore(listOf("a","a"),"a",null))
    }
    @Test fun optimisticOrderSurvivesStalePacketsButEndsOnAcknowledgementOrTargetChange() {
        val windows=listOf("a","b","c").map {WindowItem(it,it,emptyList())}
        val state=AppState(phase="connected",selectedSession="s",windows=windows,tabMoveId=7)
        val pending=PendingTabOrder("s",9,listOf("c","a","b"))
        assertEquals(listOf("c","a","b"),pending.apply(state)?.map {it.id})
        assertNotNull(pending.keepFor(state.copy(tabMoveId=8,titles=mapOf("pane" to "Spinner"))))
        assertNull(pending.keepFor(state.copy(tabMoveId=9))) // success and failure both ack
        assertNull(pending.keepFor(state.copy(selectedSession="other")))
        assertNull(pending.keepFor(state.copy(phase="disconnected")))
        assertNull(pending.keepFor(state.copy(windows=windows.dropLast(1))))
        assertEquals("Renamed",pending.apply(state.copy(windows=windows.map {it.copy(label="Renamed")}))?.first()?.label)
    }
    @Test fun paneOrderPreservesIdentityGeometryAndOtherTabsAndRollsBackOnAcknowledgement() {
        val layout=listOf(PanePlacement("a",0f,0f,.5f,1f),PanePlacement("b",.5f,0f,.5f,1f))
        val window=WindowItem("w","Work",listOf(BlockItem("a","A"),BlockItem("b","B")),layout)
        val state=AppState(phase="connected",selectedSession="s",selectedBlock="a",controlled=true,windows=listOf(window,WindowItem("other","Other",emptyList())),paneMoveId=2)
        val pending=PendingPaneOrder("s","w",4,listOf("b","a"))
        val result=pending.apply(state,state.windows)!!
        assertEquals(listOf("b","a"),result.first().blocks.map {it.id})
        assertSame(layout,result.first().layout);assertSame(state.windows.last(),result.last())
        assertEquals("a",state.selectedBlock);assertTrue(state.controlled)
        assertNotNull(pending.keepFor(state.copy(paneMoveId=3)))
        assertNull(pending.keepFor(state.copy(paneMoveId=4)))
        assertNull(pending.keepFor(state.copy(selectedSession="another")))
        assertNull(pending.keepFor(state.copy(phase="disconnected")))
        assertNull(pending.keepFor(state.copy(windows=listOf(window.copy(blocks=window.blocks.take(1))))))
        assertEquals(4L,AppState.parse("""{"paneMoveId":4}""").paneMoveId)
    }
}

package dev.lemon.rex

import org.junit.Assert.*
import org.junit.Test

class WorkspaceNavigationTest {
    private val first = WindowItem("one", "Editor", listOf(BlockItem("a", "Editor"), BlockItem("b", "Shell")))
    private val second = WindowItem("two", "Logs", listOf(BlockItem("c", "Logs")))

    @Test fun switchingTabsAndSessionsRestoresTheirOwnPane() {
        val navigation = WorkspaceNavigation()
        navigation.remember("session-a", listOf(first, second), "b")
        navigation.remember("session-a", listOf(first, second), "c")
        navigation.remember("session-b", listOf(first, second), "a")
        assertEquals("b", navigation.pane("session-a", first))
        assertEquals("c", navigation.pane("session-a", listOf(first, second)))
        assertEquals("a", navigation.pane("session-b", listOf(first, second)))
    }

    @Test fun paneSwipesWrapWithinTheirOwnTab() {
        val navigation = WorkspaceNavigation()
        assertEquals("b", navigation.neighbour(listOf(first, second), "a", 1))
        assertEquals("a", navigation.neighbour(listOf(first, second), "b", -1))
        assertEquals("b",navigation.neighbour(listOf(first, second), "a", -1))
        assertEquals("a",navigation.neighbour(listOf(first, second), "b", 1))
        assertNull(navigation.neighbour(listOf(first, second), "missing", 1))
        assertNull(navigation.neighbour(listOf(first, second), "a", 0))
    }

    @Test fun sessionSwipesFollowPickerOrderAndWrapAtBothEdges() {
        val navigation = WorkspaceNavigation()
        val sessions = listOf(SessionItem("one", "First"), SessionItem("two", "Second"), SessionItem("three", "Third"))
        assertEquals("two", navigation.neighbourSession(sessions, "one", 1))
        assertEquals("two", navigation.neighbourSession(sessions, "three", -1))
        assertEquals("three",navigation.neighbourSession(sessions, "one", -1))
        assertEquals("one",navigation.neighbourSession(sessions, "three", 1))
        assertNull(navigation.neighbourSession(sessions, "missing", 1))
        assertNull(navigation.neighbourSession(sessions, "one", 0))
    }

    @Test fun closedPanesAndWindowsFallBackToExistingTerminal() {
        val navigation = WorkspaceNavigation()
        navigation.remember("session", listOf(first, second), "b")
        assertEquals("a", navigation.pane("session", first.copy(blocks = first.blocks.take(1))))
        assertEquals("c", navigation.pane("session", listOf(second)))
        assertEquals("c", navigation.pane("new-session", listOf(first.copy(blocks = emptyList()), second)))
        assertNull(navigation.pane("session", emptyList<WindowItem>()))
        assertNull(navigation.pane("session", first.copy(blocks = emptyList())))
    }
    @Test fun emptyAndSingleItemListsDoNotNavigate() {
        val navigation=WorkspaceNavigation()
        assertNull(navigation.neighbourSession(emptyList(),"a",1))
        assertNull(navigation.neighbourSession(listOf(SessionItem("a","Only")),"a",-1))
        assertNull(navigation.neighbour(listOf(second),"c",1))
    }

    @Test fun historyRoundTripRetainsEachSessionsTabAndEachTabsPane() {
        val history=WorkspaceNavigation()
        history.remember("a",listOf(first,second),"b")
        history.remember("a",listOf(first,second),"c")
        history.remember("b",listOf(first,second),"a")
        val restored=WorkspaceNavigation.decode(history.encode())
        assertEquals("b",restored.pane("a",first))
        assertEquals("c",restored.pane("a",listOf(first,second)))
        assertEquals("a",restored.pane("b",listOf(first,second)))
    }
    @Test fun aPendingUserSelectionSurvivesBeforeItsSnapshotArrives() {
        val history=WorkspaceNavigation()
        history.remember("s",listOf(first,second),"a")
        history.request("s","c")
        val restored=WorkspaceNavigation.decode(history.encode())
        val initial=AppState(phase="connected",sessions=listOf(SessionItem("s","Session")),selectedSession="s",selectedBlock="a",windows=listOf(first,second))
        assertEquals(WorkspaceTarget("s","c"),restored.target(initial,"s"))
        assertEquals("c",restored.pane("s",listOf(first,second)))
    }
    @Test fun restoringSelectsTheSavedSessionBeforeItsPaneAndIgnoresTransientStates() {
        val history=WorkspaceNavigation();history.remember("saved",listOf(first,second),"b")
        val initial=AppState(phase="connected",sessions=listOf(SessionItem("other","Other"),SessionItem("saved","Saved")),selectedSession="other",selectedBlock="a",windows=listOf(first,second))
        assertEquals(WorkspaceTarget("saved"),history.target(initial,"saved"))
        assertEquals(WorkspaceTarget("saved","b"),history.target(initial.copy(selectedSession="saved"),"saved"))
        assertNull(history.target(initial.copy(phase="reconnecting"),"saved"))
        assertNull(history.target(initial.copy(creating=true),"saved"))
        assertNull(history.target(initial.copy(selectedSession="saved",selectedBlock="b"),"saved"))
    }
    @Test fun deletedSavedTerminalsFallBackWithoutRetryingMissingIds() {
        val history=WorkspaceNavigation();history.remember("s",listOf(first,second),"b")
        val state=AppState(phase="connected",sessions=listOf(SessionItem("s","Session")),selectedSession="s",windows=listOf(first.copy(blocks=listOf(first.blocks[0])),second))
        assertEquals(WorkspaceTarget("s","a"),history.target(state,"s"))
        assertEquals(WorkspaceTarget("s","c"),history.target(state.copy(windows=listOf(second)),"s"))
        assertEquals(WorkspaceTarget("s","a"),history.target(state,"deleted-session"))
        assertNull(history.target(state.copy(sessions=emptyList()),"s"))
    }
    @Test fun repeatedSnapshotsDoNotProduceNewHistoryWrites() {
        val history=WorkspaceNavigation()
        assertTrue(history.remember("s",listOf(first,second),"b"))
        assertFalse(history.remember("s",listOf(first,second),"b"))
        assertFalse(history.request("s","b"))
        assertTrue(history.request("s","c"))
    }
    @Test fun malformedHistoryAndMissingFieldsRemainSafe() {
        for(raw in listOf(null,"broken","[]","""{"s":{"window":42,"block":{},"panes":{"one":true}},"bad":4}""")) {
            assertEquals("a",WorkspaceNavigation.decode(raw).pane("s",listOf(first,second)))
        }
    }

}

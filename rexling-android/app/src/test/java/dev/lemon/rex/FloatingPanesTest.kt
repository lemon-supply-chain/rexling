package dev.lemon.rex

import org.junit.Assert.*
import org.junit.Test

class FloatingPanesTest {
    private val window =
        WindowItem(
            "tab",
            "Tab",
            listOf(BlockItem("a", "Editor"), BlockItem("f", "Float"), BlockItem("b", "Shell")),
            listOf(PanePlacement("f", 0f, 0f, 1f, 1f, floating = true)),
        )

    @Test
    fun floatingPanesAreExcludedFromLoopingSwipesButStillOpenIndividually() {
        assertEquals(listOf("f"), window.floatingPanes().map { it.id })
        assertEquals(listOf("a", "b"), window.carouselPanes(true, "a").map { it.id })
        assertEquals(listOf("f"), window.carouselPanes(true, "f").map { it.id })
        assertEquals(window.blocks, window.carouselPanes(false, "a"))
        val navigation = WorkspaceNavigation()
        val filtered = listOf(window.copy(blocks = window.tiledPanes()))
        assertEquals("b", navigation.neighbour(filtered, "a", -1))
        assertEquals("a", navigation.neighbour(filtered, "b", 1))
        assertNull(navigation.neighbour(filtered, "f", 1))
    }

    @Test
    fun minimizingReturnsToLastTiledPanePerSessionAndTab() {
        val navigation = FloatingPaneNavigation()
        navigation.remember("one", window, "b")
        navigation.remember("one", window, "f")
        assertEquals("b", navigation.returnPane("one", window))
        assertEquals("a", navigation.returnPane("two", window))
        assertEquals("a", navigation.returnPane("one", window.copy(id = "other")))
        assertEquals(
            "a",
            navigation.returnPane(
                "one",
                window.copy(blocks = window.blocks.filterNot { it.id == "b" }),
            ),
        )
    }

    @Test
    fun serverMinimizedLayerStaysInDockButIsSkippedBySwipesAndWorkspaceRestore() {
        val minimized = window.copy(layout = window.layout.map { it.copy(minimized = true) })
        assertEquals(listOf("f"), minimized.floatingPanes().map { it.id })
        assertEquals(listOf("a", "b"), minimized.carouselPanes(false, "a").map { it.id })
        assertEquals(listOf("a", "b"), minimized.carouselPanes(true, "f").map { it.id })
        val navigation = WorkspaceNavigation().apply { remember("one", listOf(window), "f") }
        val state =
            AppState(
                phase = "connected",
                selectedSession = "one",
                sessions = listOf(SessionItem("one", "One")),
                windows = listOf(minimized),
            )
        assertEquals(WorkspaceTarget("one", "a"), navigation.target(state, "one"))
        assertFalse(
            NavigationRequest(block = "f", id = 1)
                .acknowledge(state.copy(navigationId = 1))
                .pending,
        )
    }

    @Test
    fun unknownGeometryStaysSwipeableAndFloatingOnlyTabsCanMinimizeToEmpty() {
        assertEquals(window.blocks, window.copy(layout = emptyList()).tiledPanes())
        val only = window.copy(blocks = window.blocks.filter { it.id == "f" })
        assertTrue(only.tiledPanes().isEmpty())
        assertNull(FloatingPaneNavigation().returnPane("one", only))
        assertEquals(only.blocks, only.carouselPanes(true, "f"))
    }
}

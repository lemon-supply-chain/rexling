package dev.shulhi.rex

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalRowWindowTest {
    private val cell = 16f
    private val insetTop = 8f

    @Test fun a_grid_that_fills_the_area_starts_at_the_padding() {
        val window = terminalRowWindow(rows = 30, fittedRows = 30, insetTop = insetTop, contentHeight = 480f, cellHeight = cell)
        assertEquals(0, window.firstRow)
        assertEquals(insetTop, window.origin, .0001f)
        assertEquals(insetTop, window.top(0, cell), .0001f)
    }

    @Test fun a_grid_taller_than_the_area_clips_the_top_and_glues_the_bottom() {
        // The keyboard is half way up: 12 rows fit out of a 30 row grid.
        val height = 192f
        val window = terminalRowWindow(rows = 30, fittedRows = 12, insetTop = insetTop, contentHeight = height, cellHeight = cell)
        assertEquals("the top 18 rows are clipped", 18, window.firstRow)
        assertEquals(insetTop + height, window.top(30, cell), .0001f)
        assertEquals(window.firstRow, window.row(window.top(window.firstRow, cell), cell))
    }

    @Test fun a_grid_shorter_than_the_area_anchors_at_padding_and_leaves_gap_below() {
        // The keyboard is closing: the 12 row grid keeps existing text anchored at the top,
        // so space opens below it instead of sliding content down and blanking the top.
        val height = 480f
        val window = terminalRowWindow(rows = 12, fittedRows = 30, insetTop = insetTop, contentHeight = height, cellHeight = cell)
        assertEquals(0, window.firstRow)
        assertEquals(insetTop, window.origin, .0001f)
        assertEquals(insetTop, window.top(0, cell), .0001f)
        assertEquals(insetTop + 12 * cell, window.top(12, cell), .0001f)
        assertTrue("the gap is below the content", window.top(12, cell) < insetTop + height)
    }

    @Test fun the_bottom_edge_stays_on_the_keyboard_while_the_area_moves() {
        var previous = Float.NaN
        for (height in 480 downTo 190) {
            val fitted = (height / cell).toInt()
            val bottom = terminalRowWindow(rows = 30, fittedRows = fitted, insetTop = insetTop, contentHeight = height.toFloat(), cellHeight = cell)
                .top(30, cell)
            if (!previous.isNaN()) assertTrue("the bottom must not jump", bottom <= previous + .0001f)
            previous = bottom
        }
    }

    @Test fun the_touch_mapping_inverts_the_row_positions() {
        val window = terminalRowWindow(rows = 30, fittedRows = 12, insetTop = insetTop, contentHeight = 192f, cellHeight = cell)
        for (row in 18 until 30) {
            val y = window.top(row, cell) + cell / 2
            assertEquals(row, window.row(y, cell))
        }
        assertTrue("a point above the grid misses the content", window.row(window.origin - 1, cell) < 0)
    }
}
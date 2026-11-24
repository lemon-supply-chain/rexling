package dev.lemon.rex

import org.junit.Assert.*
import org.junit.Test

class TerminalSelectionTest {
    private val frame =
        TerminalScreen(
            12,
            2,
            null,
            null,
            -1,
            0,
            listOf(
                TerminalCell(0, 0, "h", -1, 0, 0, 1),
                TerminalCell(1, 0, "i", -1, 0, 0, 1),
                TerminalCell(3, 0, "🦖", -1, 0, 0, 2),
                TerminalCell(4, 0, "", -1, 0, 0, 0),
                TerminalCell(0, 1, "o", -1, 0, 0, 1),
                TerminalCell(1, 1, "k", -1, 0, 0, 1),
            ),
        )

    @Test
    fun wordSelectionKeepsWideGlyphsWhole() {
        val selection = TerminalSelection(frame, 4, 0)
        selection.word()
        assertEquals("🦖", selection.text())
        assertEquals(3, selection.first.column)
        assertEquals(5, selection.endColumn(selection.last))
    }

    @Test
    fun reversedMultilineSelectionPreservesSpacesAndLineBreaks() {
        val selection = TerminalSelection(frame, 1, 1)
        selection.move(0, 0)
        assertEquals("hi 🦖\nok", selection.text())
    }

    @Test
    fun selectAllIncludesSparseCellsWithoutCopyingWideSpacers() {
        val selection = TerminalSelection(frame, 0, 0)
        selection.all()
        assertEquals("hi 🦖\nok", selection.text())
    }

    @Test
    fun eitherHandleCanCrossTheOtherAndBoundsAreClamped() {
        val selection = TerminalSelection(frame, 0, 0)
        selection.word()
        assertEquals("hi", selection.text())
        selection.move(1, 1, true)
        assertEquals("i 🦖\nok", selection.text())
        selection.move(-50, -50, true)
        selection.move(100, 100)
        assertEquals("hi 🦖\nok", selection.text())
    }
}

package dev.lemon.rex

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ImeAnchorTest {
    private fun screen(rows: Int=6,columns: Int=20,cursor: Pair<Int,Int>?=null,scroll: TerminalScroll=TerminalScroll(rows.toLong(),0,rows.toLong()),painted: List<Int> = emptyList()): TerminalScreen {
        val cells=painted.flatMap {y->listOf(TerminalCell(0,y,"line",0,0,0,4))}
        return TerminalScreen(columns=columns,rows=rows,cursorX=cursor?.first,cursorY=cursor?.second,
            foreground=0,background=0,cells=cells,scroll=scroll)
    }
    private fun scrolledUp(rows: Int=6)=TerminalScroll(rows+40L,0,rows.toLong())

    @Test fun aReportedCursorWinsEvenWithAHiddenCaretRule() {
        val visible=screen(cursor=1 to 2,painted=listOf(0,1,2,3,4,5))
        assertEquals(2,visible.imeAnchorRow(ImeAnchor.CONTENT))
        assertEquals(2,visible.imeAnchorRow(ImeAnchor.CURSOR))
    }

    @Test fun aHiddenCaretAnchorsOnTheLastPaintedRow() {
        val hidden=screen(cursor=null,painted=listOf(0,1,2,4))
        assertEquals(4,hidden.imeAnchorRow(ImeAnchor.CONTENT))
        assertNull(hidden.imeAnchorRow(ImeAnchor.CURSOR))
    }

    @Test fun anEmptyScreenNeverMovesAndTheCursorAnchorStillDoesNot() {
        val blank=screen(cursor=null)
        assertNull(blank.imeAnchorRow(ImeAnchor.CONTENT))
        // Only whitespace and wide-cell padding count as unpainted.
        assertNull(screen(cursor=null,painted=emptyList()).imeAnchorRow(ImeAnchor.CONTENT))
    }

    @Test fun scrollbackKeepsTheCurrentViewForBothAnchors() {
        val scrolled=screen(cursor=1 to 1,painted=listOf(0,1,2,3,4,5),scroll=scrolledUp())
        assertNull(scrolled.imeAnchorRow(ImeAnchor.CONTENT))
        assertNull(scrolled.imeAnchorRow(ImeAnchor.CURSOR))
        assertEquals(5,screen(cursor=null,painted=listOf(0,5)).imeAnchorRow(ImeAnchor.CONTENT))
    }

    @Test fun resizeProgramsNeverPan() {
        assertNull(screen(cursor=1 to 2,painted=listOf(0,1,2,4)).imeAnchorRow(ImeAnchor.RESIZE))
        assertNull(screen(cursor=null,painted=listOf(0,1,2,4)).imeAnchorRow(ImeAnchor.RESIZE))
    }

    @Test fun aHiddenCaretRevealsTheLastRowJustAboveTheKeyboard() {
        // A hidden caret on the bottom row still pans the viewport until that row clears the IME.
        assertEquals(120,terminalImePan(TerminalKeyboardFocus(300f,320f,320f),200,200))
        // Content far above the visible window does not drag the view up.
        assertEquals(0,terminalImePan(TerminalKeyboardFocus(100f,120f,120f),200,200))
    }
}

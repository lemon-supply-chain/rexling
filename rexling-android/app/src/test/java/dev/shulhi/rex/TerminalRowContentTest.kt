package dev.shulhi.rex

import org.junit.Assert.*
import org.junit.Test

class TerminalRowContentTest {
    @Test fun movedRowsReuseOnlyIdenticalDrawingContent() {
        val cell=TerminalCell(0,3,"λ",-1,0,0,1,"https://example.com")
        assertTrue(sameRowCells(listOf(cell),listOf(cell.copy(y=0))))
        for(changed in listOf(cell.copy(text="x"),cell.copy(width=2),cell.copy(x=1),
            cell.copy(attributes=1),cell.copy(background=1),cell.copy(foreground=1),cell.copy(hyperlink=null))) {
            assertFalse(sameRowCells(listOf(cell),listOf(changed)))
        }
        val link=TerminalLink(3,0,1,"https://example.com",true)
        assertTrue(sameRowLinks(listOf(link),listOf(link.copy(row=0))))
        assertFalse(sameRowLinks(listOf(link),listOf(link.copy(end=2))))
        assertFalse(sameRowLinks(listOf(link),listOf(link.copy(url="https://example.org"))))
        assertFalse(sameRowLinks(listOf(link),listOf(link.copy(explicit=false))))
    }
}

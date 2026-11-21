package dev.lemon.rex

/** How the keyboard viewport reacts when the IME covers the bottom of the grid. */
internal enum class ImeAnchor(val id: String) {
    /** Only the reported hardware cursor moves the window; a program that hides it never pans. */
    CURSOR("cursor"),
    /** Fall back to the last painted row while no hardware cursor is reported. */
    CONTENT("content"),
    /** Let the view shrink and the PTY take SIGWINCH instead of panning a full-height grid. */
    RESIZE("resize"),
}

internal fun TerminalScreen.lastPaintedRow(): Int =
    lines.indexOfLast {cells->cells.any {cell->cell.width>0 && cell.text.any {it!='\u0000' && !it.isWhitespace()}}}

/**
 * The row the IME viewport must reveal, or null when nothing may move. Scrollback keeps the
 * current view, and a cursor-only anchor needs a reported cursor.
 */
internal fun TerminalScreen.imeAnchorRow(anchor: ImeAnchor): Int? {
    if(scroll.below>0) return null
    if(anchor==ImeAnchor.RESIZE) return null
    val cursor=cursorY?.takeIf {it in 0 until rows}
    if(anchor==ImeAnchor.CURSOR) return cursor
    return cursor ?: lastPaintedRow().takeIf {it>=0}
}

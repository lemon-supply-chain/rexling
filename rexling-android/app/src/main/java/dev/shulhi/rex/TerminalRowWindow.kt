package dev.shulhi.rex

/**
 * Where the frame rows are drawn when the grid and the visible area disagree.
 *
 * A keyboard animation shrinks the area one text row at a time, while the PTY only answers
 * after the animation. Bottom-anchoring keeps the cursor row glued to the top of the
 * keyboard instead of cropping it off, so the content rides up with the keyboard and the
 * program's repaint at the end only fills the rows that were clipped away.
 *
 * When the grid is shorter than the visible area (for instance when a keyboard is closing
 * before the PTY has restored the full height), anchoring at the top padding prevents the
 * existing text from sliding down and creating a blank void above it.
 */
internal data class TerminalRowWindow(val firstRow: Int,val origin: Float) {
    /** Screen y of a frame row's top edge; `origin` itself is frame row 0. */
    fun top(row: Int,cellHeight: Float): Float = origin + row * cellHeight
    /** Frame row under a screen y. Values outside the grid mean the touch missed the content. */
    fun row(y: Float,cellHeight: Float): Int = kotlin.math.floor((y - origin) / cellHeight).toInt()
}

internal fun terminalRowWindow(rows: Int,fittedRows: Int,insetTop: Float,contentHeight: Float,cellHeight: Float): TerminalRowWindow {
    val fit=fittedRows.coerceAtLeast(1)
    val firstRow=(rows - fit).coerceAtLeast(0)
    // A grid taller than the area is glued to the bottom edge, so the cursor and content
    // ride smoothly with the keyboard. Any other grid stays anchored at the padding.
    val origin=if(rows > fit) insetTop + contentHeight - rows * cellHeight else insetTop
    return TerminalRowWindow(firstRow,origin)
}
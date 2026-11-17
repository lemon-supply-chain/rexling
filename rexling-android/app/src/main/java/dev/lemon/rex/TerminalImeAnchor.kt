package dev.lemon.rex

/** The line the keyboard viewport reveals once the IME covers the bottom of the grid. */
internal enum class ImeAnchor(val id: String) {
    /** Only the reported hardware cursor moves the window; a program that hides it never pans. */
    CURSOR("cursor"),
    /** Fall back to the last painted row while no hardware cursor is reported. */
    CONTENT("content"),
}

/** One program family and the anchor its terminal needs. The first matching rule wins. */
internal data class ImeAnchorRule(
    val id: String,
    val anchor: ImeAnchor,
    val names: Set<String> = emptySet(),
    val pathFragments: Set<String> = emptySet(),
) {
    fun matches(candidate: String): Boolean {
        val path=candidate.replace('\\','/').lowercase()
        val name=path.substringAfterLast('/').trimStart('-').removeSuffix(".exe").removeSuffix(".cmd")
        return name in names || pathFragments.any {path.contains(it)}
    }
}

/**
 * Programs whose TUI hides the hardware cursor and paints its own caret. Their snapshots carry
 * no cursor, so the IME viewport anchors on the last painted row instead of staying put.
 * Adding a program stays data: append a rule, no new code path.
 */
internal object TerminalImeAnchors {
    internal val rules=listOf(
        ImeAnchorRule("pi",ImeAnchor.CONTENT,
            names=setOf("pi"),
            pathFragments=setOf("/@earendil-works/pi-coding-agent/","/.pi/agent/")),
        ImeAnchorRule("cursor-agent",ImeAnchor.CONTENT,
            names=setOf("cursor-agent"),
            pathFragments=setOf("/cursor-agent/")),
    )
    /** Pending or unknown programs keep the cursor-only behavior, which never moves a viewport on its own. */
    fun anchor(identity: ProgramIdentity?): ImeAnchor {
        if(identity==null || identity.pending) return ImeAnchor.CURSOR
        val names=identity.matchNames
        return rules.firstOrNull {rule->names.any(rule::matches)}?.anchor ?: ImeAnchor.CURSOR
    }
    internal fun ids(): List<String> = rules.map {it.id}
}

internal fun TerminalScreen.lastPaintedRow(): Int =
    lines.indexOfLast {cells->cells.any {cell->cell.width>0 && cell.text.any {it!='\u0000' && !it.isWhitespace()}}}

/**
 * The row the IME viewport must reveal, or null when nothing may move. Scrollback keeps the
 * current view, and a cursor-only anchor needs a reported cursor.
 */
internal fun TerminalScreen.imeAnchorRow(anchor: ImeAnchor): Int? {
    if(scroll.below>0) return null
    val cursor=cursorY?.takeIf {it in 0 until rows}
    if(anchor==ImeAnchor.CURSOR) return cursor
    return cursor ?: lastPaintedRow().takeIf {it>=0}
}
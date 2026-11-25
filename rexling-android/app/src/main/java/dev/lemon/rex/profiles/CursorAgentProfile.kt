package dev.lemon.rex

/**
 * cursor-agent: an inline agent TUI that hides its caret like pi. The RXT7 hidden caret position is
 * the input-row tap target, and the IME pans to the last painted row instead of resizing the grid.
 */
internal data object CursorAgentProfile : ProgramProfile {
    override val hiddenCursorInput = true
    override val cursorTapKeyboard = true
    override val imeAnchor = ImeAnchor.CONTENT

    override fun matches(identity: ProgramIdentity) =
        matchesCandidates(
            identity,
            names = setOf("cursor-agent"),
            pathFragments = setOf("/cursor-agent/"),
        )
}

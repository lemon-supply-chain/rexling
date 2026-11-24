package dev.lemon.rex

/**
 * pi (earendil-works/pi-coding-agent): a full-screen TUI on the alternate screen with all-motion
 * mouse tracking and a hidden caret. libghostty never reports at-prompt on the alternate screen, so
 * semantic input rows are unavailable and the RXT7 hidden caret position is the only input-row tap
 * target. The IME pans to the last painted row instead of resizing the grid.
 */
internal data object PiProfile : ProgramProfile {
    override val hiddenCursorInput = true
    override val cursorTapKeyboard = true
    override val imeAnchor = ImeAnchor.CONTENT

    override fun matches(identity: ProgramIdentity) =
        matchesCandidates(
            identity,
            names = setOf("pi"),
            pathFragments = setOf("/@earendil-works/pi-coding-agent/", "/.pi/agent/"),
        )
}

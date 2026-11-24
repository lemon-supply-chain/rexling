package dev.lemon.rex

/**
 * yazi: a full-screen file manager that repaints correctly on SIGWINCH. Letting the view shrink and
 * the PTY resize keeps its preview panes correct instead of panning a full-height grid.
 */
internal data object YaziProfile : ProgramProfile {
    override val imeAnchor = ImeAnchor.RESIZE

    override fun matches(identity: ProgramIdentity) =
        matchesCandidates(
            identity,
            names = setOf("yazi"),
            pathFragments = emptySet(),
        )
}

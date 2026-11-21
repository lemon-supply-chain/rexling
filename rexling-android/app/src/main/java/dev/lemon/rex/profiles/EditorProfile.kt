package dev.lemon.rex

/**
 * Full-screen modal editors (vim, neovim, helix, nano, micro, emacs, kakoune):
 * the grid extends seamlessly into the view padding, and the soft keyboard stays
 * a dumb key sender because modal editing must not be autocorrected.
 */
internal data object EditorProfile : ProgramProfile {
    override val edgePolicy = TerminalEdgePolicy.EDITOR
    override val keyboardSuggestions = false
    override fun matches(identity: ProgramIdentity) =
        identity.kind in setOf(ProgramKind.VIM, ProgramKind.NEOVIM) || matchesCandidates(
            identity,
            names = setOf("vim", "nvim", "vi", "gvim", "helix", "hx", "nano", "micro", "emacs", "kakoune"),
            pathFragments = setOf("/vim", "/nvim", "/helix", "/hx", "/nano", "/micro", "/emacs", "/kakoune"),
        )
}

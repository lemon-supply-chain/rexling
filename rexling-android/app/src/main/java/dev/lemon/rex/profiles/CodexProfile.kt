package dev.lemon.rex

/**
 * Codex CLI: an inline chat TUI on the primary screen that hides its caret while rendering. The
 * RXT7 hidden caret position doubles as the input-row tap target, and its OSC 133 markers let a tap
 * on the input row summon the keyboard.
 */
internal data object CodexProfile : ProgramProfile {
    override val hiddenCursorInput = true
    override val cursorTapKeyboard = true

    override fun matches(identity: ProgramIdentity) = identity.kind == ProgramKind.CODEX
}

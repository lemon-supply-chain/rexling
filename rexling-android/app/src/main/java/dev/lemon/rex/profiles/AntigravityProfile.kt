package dev.lemon.rex

/**
 * Antigravity: renders a visible caret, so it needs no hidden-position trust,
 * but tapping its input row may still summon the keyboard.
 */
internal data object AntigravityProfile : ProgramProfile {
    override val cursorTapKeyboard = true
    override fun matches(identity: ProgramIdentity) = identity.kind == ProgramKind.ANTIGRAVITY
}

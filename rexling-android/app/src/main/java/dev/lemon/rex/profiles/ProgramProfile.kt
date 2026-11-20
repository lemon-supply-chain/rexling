package dev.lemon.rex

import java.util.Locale

/**
 * Program-specific terminal behaviors, resolved from the live foreground identity.
 * Defaults are safe for unknown programs; each known program family lives in its
 * own profile file in this folder. Adding a program stays data: add a profile
 * object and append it to [specific].
 */
internal sealed interface ProgramProfile {
    /** Trust a hidden caret position as the input-row tap target and IME pan anchor. */
    val hiddenCursorInput: Boolean get() = false
    /** Tapping the input row opens the soft keyboard while the pane is controlled. */
    val cursorTapKeyboard: Boolean get() = false
    /** How the keyboard viewport reacts when the IME covers the bottom of the grid. */
    val imeAnchor: ImeAnchor get() = ImeAnchor.CURSOR
    /** How padding outside the terminal grid is rendered when extendPadding is enabled. */
    val edgePolicy: TerminalEdgePolicy get() = TerminalEdgePolicy.NON_EDITOR
    /** Whether the soft keyboard may offer suggestions and autocorrect. */
    val keyboardSuggestions: Boolean get() = true
    /** Recognition against the foreground program's reported identity. */
    fun matches(identity: ProgramIdentity): Boolean

    /** Fallback for shells, unknown and still-loading programs: every default. */
    data object Generic : ProgramProfile {
        override fun matches(identity: ProgramIdentity) = false
    }

    companion object {
        // First match wins; no current profiles overlap, order documents precedence.
        private val specific = listOf(
            PiProfile, CursorAgentProfile, ClaudeProfile, CodexProfile,
            AntigravityProfile, EditorProfile, YaziProfile,
        )

        fun resolve(identity: ProgramIdentity?): ProgramProfile =
            identity?.takeUnless { it.pending }?.let { candidate ->
                specific.firstOrNull { it.matches(candidate) }
            } ?: Generic
    }
}

/** True when a reported candidate's basename equals one of [names] or its
 * normalized path contains one of [pathFragments]. */
internal fun matchesCandidates(
    identity: ProgramIdentity,
    names: Set<String>,
    pathFragments: Set<String>,
): Boolean = identity.matchNames.any { candidate ->
    val path = candidate.replace('\\', '/').lowercase(Locale.ROOT)
    val name = path.substringAfterLast('/').trimStart('-').removeSuffix(".exe").removeSuffix(".cmd")
    name in names || pathFragments.any { path.contains(it) }
}

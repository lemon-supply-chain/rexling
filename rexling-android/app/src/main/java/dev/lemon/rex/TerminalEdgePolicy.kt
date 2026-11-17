package dev.lemon.rex

import java.util.Locale

/**
 * How padding outside the terminal grid is rendered when extendPadding is enabled.
 *
 * NON_EDITOR (default): strictly isolates row bounds to content dimensions so
 * segmented status bars (e.g. yazi, tmux) and CLI tools do not bleed colored
 * badges into top/bottom insets or pager dots.
 *
 * EDITOR: seamlessly extends row 0 background to view top and last row background
 * to view bottom, giving full-screen editors (e.g. vim, neovim) a seamless
 * borderless native feel without top/bottom letterbox gutters.
 */
internal enum class TerminalEdgePolicy(val id: String) {
    NON_EDITOR("non_editor"),
    EDITOR("editor"),
}

/** One program rule mapping to an edge policy. The first matching rule wins. */
internal data class TerminalEdgeRule(
    val id: String,
    val policy: TerminalEdgePolicy,
    val kinds: Set<ProgramKind> = emptySet(),
    val names: Set<String> = emptySet(),
    val pathFragments: Set<String> = emptySet(),
)

/**
 * Resolves the edge rendering policy for a program.
 * Default policy is strictly NON_EDITOR.
 */
internal object TerminalEdgePolicies {
    internal val rules = listOf(
        TerminalEdgeRule(
            id = "editors",
            policy = TerminalEdgePolicy.EDITOR,
            kinds = setOf(ProgramKind.VIM, ProgramKind.NEOVIM),
            names = setOf("vim", "nvim", "vi", "gvim", "helix", "hx", "nano", "micro", "emacs", "kakoune"),
            pathFragments = setOf("/vim", "/nvim", "/helix", "/hx", "/nano", "/micro", "/emacs", "/kakoune")
        ),
    )

    fun policy(identity: ProgramIdentity?): TerminalEdgePolicy {
        if (identity == null || identity.pending) return TerminalEdgePolicy.NON_EDITOR
        for (rule in rules) {
            if (rule.kinds.isNotEmpty() && identity.kind in rule.kinds) return rule.policy
            if (rule.names.isNotEmpty() && identity.matchNames.any { it.lowercase(Locale.ROOT) in rule.names }) return rule.policy
            if (rule.pathFragments.isNotEmpty()) {
                val exec = identity.executable.lowercase(Locale.ROOT)
                if (rule.pathFragments.any { exec.contains(it) }) return rule.policy
            }
        }
        return TerminalEdgePolicy.NON_EDITOR
    }
}

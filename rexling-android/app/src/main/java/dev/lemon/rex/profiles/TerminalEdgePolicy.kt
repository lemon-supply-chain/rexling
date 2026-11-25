package dev.lemon.rex

/**
 * How padding outside the terminal grid is rendered when extendPadding is enabled.
 *
 * NON_EDITOR (default): strictly isolates row bounds to content dimensions so segmented status bars
 * (e.g. yazi, tmux) and CLI tools do not bleed colored badges into top/bottom insets or pager dots.
 *
 * EDITOR: seamlessly extends row 0 background to view top and last row background to view bottom,
 * giving full-screen editors (e.g. vim, neovim) a seamless borderless native feel without
 * top/bottom letterbox gutters.
 */
internal enum class TerminalEdgePolicy(val id: String) {
    NON_EDITOR("non_editor"),
    EDITOR("editor"),
}

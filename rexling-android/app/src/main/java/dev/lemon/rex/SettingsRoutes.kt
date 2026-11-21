package dev.lemon.rex

/** One settings drawer; the stack order is the hierarchy the user walks through. */
internal sealed interface SettingsRoute {
    object Preferences: SettingsRoute
    object Appearance: SettingsRoute
    object FontSize: SettingsRoute
    object Language: SettingsRoute
    object BackgroundAlerts: SettingsRoute
    object About: SettingsRoute
    object Shortcuts: SettingsRoute
    /** Third level from [Shortcuts]; an empty [id] starts a new profile draft. */
    data class ShortcutEditor(val id: String): SettingsRoute
    object KeyboardToolbar: SettingsRoute
    object ControlShortcuts: SettingsRoute
    object SshKeys: SettingsRoute
    object Network: SettingsRoute
}

/**
 * Cancel, swipe-down and system Back close the top drawer and reveal the level it was opened
 * from. Only the top renders, and an editor opened from the terminal keeps no parent, so closing
 * it still returns to the terminal.
 */
internal object SettingsNav {
    fun open(stack: MutableList<SettingsRoute>,route: SettingsRoute) {
        if(stack.lastOrNull()==route) return
        stack.add(route)
    }
    fun close(stack: MutableList<SettingsRoute>) {stack.removeLastOrNull()}
}
package dev.lemon.rex

/** One settings drawer; the stack order is the hierarchy the user walks through. */
internal sealed interface SettingsRoute {
    object Preferences : SettingsRoute

    object Appearance : SettingsRoute

    object FontSize : SettingsRoute

    object Language : SettingsRoute

    object BackgroundAlerts : SettingsRoute

    object About : SettingsRoute

    object Shortcuts : SettingsRoute

    /** Third level from [Shortcuts]; an empty [id] starts a new profile draft. */
    data class ShortcutEditor(val id: String) : SettingsRoute

    object KeyboardToolbar : SettingsRoute

    object ControlShortcuts : SettingsRoute

    object SshKeys : SettingsRoute

    object Network : SettingsRoute
}

/**
 * Cancel, swipe-down and system Back close the top drawer and reveal the level it was opened from.
 * Every route in the stack stays mounted underneath the top drawer so returning preserves scroll
 * and UI state without re-entering animation, while an editor opened directly from the terminal
 * still has no parent and closes back to the terminal.
 */
internal object SettingsNav {
    fun open(stack: MutableList<SettingsRoute>, route: SettingsRoute) {
        if (stack.lastOrNull() == route) return
        stack.add(route)
    }

    fun close(stack: MutableList<SettingsRoute>) {
        stack.removeLastOrNull()
    }

    fun close(stack: MutableList<SettingsRoute>, index: Int, route: SettingsRoute) {
        if (index == stack.lastIndex && stack.getOrNull(index) == route) stack.removeAt(index)
    }
}

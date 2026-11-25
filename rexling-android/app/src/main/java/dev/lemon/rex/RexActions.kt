package dev.lemon.rex

/**
 * A shortcut written `Name := action` names a custom Rex action instead of key notation. The app
 * forwards the name as written; Rex validates it against its configuration, and what it refuses
 * comes back as the command's error.
 */
internal object RexActions {
    const val SEPARATOR = " := "

    fun editorText(label: String, action: String): String = "$label$SEPARATOR$action"
}

package dev.lemon.rex

/**
 * Known text sent by one IME connection, never inferred from terminal output. External keys, paste
 * and pane changes discard this context in TerminalView. Preserve the remote cursor when a keyboard
 * edits inside its local field.
 */
internal class TerminalImeLine {
    var text = ""
        private set

    var cursor = 0
        private set

    fun reset() {
        text = ""
        cursor = 0
    }

    fun edit(value: String, position: Int): String {
        var at = position.coerceIn(0, value.length)
        if (
            at > 0 &&
                at < value.length &&
                Character.isLowSurrogate(value[at]) &&
                Character.isHighSurrogate(value[at - 1])
        )
            at--
        if (value == text && at == cursor) return ""
        var common = 0
        while (common < minOf(text.length, value.length) && text[common] == value[common]) common++
        if (
            common > 0 &&
                common < text.length &&
                Character.isLowSurrogate(text[common]) &&
                Character.isHighSurrogate(text[common - 1])
        )
            common--
        val keys = buildString {
            repeat(text.codePointCount(cursor, text.length)) { append("\u001b[C") }
            repeat(text.codePointCount(common, text.length)) { append('\u007f') }
            append(value.substring(common))
            repeat(value.codePointCount(at, value.length)) { append("\u001b[D") }
        }
        // Update before delivery: the callback may synchronously flush the view.
        text = value
        cursor = at
        return keys
    }
}

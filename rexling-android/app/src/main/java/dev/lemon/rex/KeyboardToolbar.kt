package dev.lemon.rex

/** Stable preference IDs; terminal input behavior remains owned by KeyboardBar. */
internal enum class ToolbarKey(
    val id: String,
    val label: String,
    val icon: ChromeIcon? = null,
    val terminal: String = "",
    val controlLetter: String = "",
) {
    ESCAPE("escape", "Escape", ChromeIcon.ESCAPE, "esc"),
    CONTROL("control", "Ctrl", ChromeIcon.CONTROL),
    ALT("alt", "Alt", ChromeIcon.ALT),
    SHIFT("shift", "Shift", ChromeIcon.SHIFT),
    TAB("tab", "Tab", ChromeIcon.TAB, "tab"),
    ARROWS("arrows", "Arrows / joystick", ChromeIcon.ARROWS),
    ENTER("enter", "Enter", ChromeIcon.ENTER, "enter"),
    PASTE("paste", "Paste", ChromeIcon.PASTE),
    BACKSPACE("backspace", "Backspace", ChromeIcon.BACKSPACE, "backspace"),
    DELETE("delete", "Delete", null, "delete"),
    HOME("home", "Home", null, "home"),
    END("end", "End", null, "end"),
    PAGE_UP("pageup", "Page Up", null, "pageup"),
    PAGE_DOWN("pagedown", "Page Down", null, "pagedown"),
    INSERT("insert", "Insert", null, "insert"),
    UP("up", "Up", ChromeIcon.UP, "up"),
    DOWN("down", "Down", ChromeIcon.DOWN, "down"),
    LEFT("left", "Left", ChromeIcon.LEFT, "left"),
    RIGHT("right", "Right", ChromeIcon.RIGHT, "right"),
    CTRL_C("ctrl-c", "Ctrl+C", controlLetter = "c"),
    CTRL_D("ctrl-d", "Ctrl+D", controlLetter = "d"),
    CTRL_Z("ctrl-z", "Ctrl+Z", controlLetter = "z"),
    CTRL_L("ctrl-l", "Ctrl+L", controlLetter = "l"),
    CTRL_R("ctrl-r", "Ctrl+R", controlLetter = "r"),
    F1("f1", "F1", terminal = "f1"),
    F2("f2", "F2", terminal = "f2"),
    F3("f3", "F3", terminal = "f3"),
    F4("f4", "F4", terminal = "f4"),
    F5("f5", "F5", terminal = "f5"),
    F6("f6", "F6", terminal = "f6"),
    F7("f7", "F7", terminal = "f7"),
    F8("f8", "F8", terminal = "f8"),
    F9("f9", "F9", terminal = "f9"),
    F10("f10", "F10", terminal = "f10"),
    F11("f11", "F11", terminal = "f11"),
    F12("f12", "F12", terminal = "f12"),
}

internal object ToolbarLayout {
    const val MAX_KEYS = 7
    val defaults =
        listOf(
            ToolbarKey.ESCAPE,
            ToolbarKey.CONTROL,
            ToolbarKey.ALT,
            ToolbarKey.SHIFT,
            ToolbarKey.TAB,
            ToolbarKey.ARROWS,
            ToolbarKey.ENTER,
        )

    fun normalize(keys: List<ToolbarKey>) = keys.distinct().take(MAX_KEYS)

    fun encode(keys: List<ToolbarKey>) = normalize(keys).joinToString(",") { it.id }

    fun decode(raw: String?): List<ToolbarKey> {
        if (raw == null) return defaults
        if (raw.isEmpty()) return emptyList()
        val keys =
            raw.split(',').mapNotNull { id -> ToolbarKey.entries.firstOrNull { it.id == id } }
        return normalize(keys).ifEmpty { defaults }
    }

    fun assign(keys: List<ToolbarKey>, index: Int, key: ToolbarKey): List<ToolbarKey> {
        if (index !in keys.indices)
            return if (keys.size < MAX_KEYS && key !in keys) keys + key else keys
        val result = keys.toMutableList()
        val previous = result.indexOf(key)
        if (previous >= 0) result[previous] = result[index]
        result[index] = key
        return result
    }

    fun drop(keys: List<ToolbarKey>, key: ToolbarKey, index: Int): List<ToolbarKey> {
        val existing = keys.indexOf(key)
        val target = index.coerceIn(0, keys.lastIndex.coerceAtLeast(0))
        if (existing >= 0)
            return keys.toMutableList().apply {
                removeAt(existing)
                add(target.coerceAtMost(size), key)
            }
        if (keys.size == MAX_KEYS) return assign(keys, target, key)
        return keys.toMutableList().apply { add(index.coerceIn(0, size), key) }
    }

    fun move(keys: List<ToolbarKey>, index: Int, step: Int): List<ToolbarKey> {
        val to = index + step
        if (index !in keys.indices || to !in keys.indices) return keys
        return keys.toMutableList().apply { add(to, removeAt(index)) }
    }
}

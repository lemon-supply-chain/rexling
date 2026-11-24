package dev.lemon.rex

/** Remembers the last tiled pane for tabs whose floating panes live in the dock. */
internal class FloatingPaneNavigation {
    private val lastTiled = mutableMapOf<Pair<String, String>, String>()

    fun remember(session: String, window: WindowItem, block: String) {
        if (window.tiledPanes().any { it.id == block }) lastTiled[session to window.id] = block
    }

    fun returnPane(session: String, window: WindowItem): String? {
        val tiled = window.tiledPanes()
        return tiled.firstOrNull { it.id == lastTiled[session to window.id] }?.id
            ?: tiled.firstOrNull()?.id
    }
}

internal fun WindowItem.floatingPanes(minimizedOnly: Boolean = false): List<BlockItem> {
    val ids =
        layout
            .asSequence()
            .filter { it.floating && (!minimizedOnly || it.minimized) }
            .map { it.id }
            .toSet()
    return if (ids.isEmpty()) emptyList() else blocks.filter { it.id in ids }
}

internal fun WindowItem.tiledPanes(): List<BlockItem> {
    val floating = floatingPanes().mapTo(hashSetOf()) { it.id }
    return blocks.filterNot { it.id in floating }
}

internal fun WindowItem.visiblePanes(): List<BlockItem> {
    if (layout.none { it.minimized }) return blocks
    val minimized = layout.asSequence().filter { it.minimized }.map { it.id }.toSet()
    return blocks.filterNot { it.id in minimized }
}

internal fun WindowItem.visibleFloatingPlacements(): List<PanePlacement> = layout.filter {
    it.floating && !it.minimized && it.width > 0f && it.height > 0f
}

internal fun WindowItem.carouselPanes(docked: Boolean, selected: String): List<BlockItem> {
    val visible = visiblePanes()
    if (!docked) return visible
    if (layout.any { it.id == selected && it.floating && !it.minimized })
        return visible.filter { it.id == selected }
    return tiledPanes().ifEmpty { visible }
}

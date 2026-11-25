package dev.lemon.rex

/** Keep preview insertion/eviction order, without copying on ordinary live updates. */
internal fun updatePaneFrames(
    current: Map<String, TerminalScreen>,
    old: AppState,
    next: AppState,
): Map<String, TerminalScreen> {
    val leaving =
        old.screen
            ?.takeIf { old.selectedBlock != next.selectedBlock && old.selectedBlock.isNotBlank() }
            ?.let { old.selectedBlock to it }
    return updatePaneFrames(current, next.previewFrames, leaving)
}

internal fun updatePaneFrames(
    current: Map<String, TerminalScreen>,
    previews: Map<String, TerminalScreen>,
    leaving: Pair<String, TerminalScreen>? = null,
): Map<String, TerminalScreen> {
    if (previews.isEmpty() && leaving == null) return current
    val frames = current.toMutableMap()
    frames.putAll(previews)
    leaving?.let { (block, screen) -> frames[block] = screen }
    while (frames.size > 16) frames.remove(frames.keys.first())
    return frames
}

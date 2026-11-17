package dev.lemon.rex

/** Keep preview insertion/eviction order, without copying on ordinary live updates. */
internal fun updatePaneFrames(current: Map<String,TerminalScreen>,old: AppState,next: AppState): Map<String,TerminalScreen> {
    val leaving=old.selectedBlock!=next.selectedBlock && old.screen!=null && old.selectedBlock.isNotBlank()
    if(next.previewFrames.isEmpty() && !leaving) return current
    val frames=current.toMutableMap()
    frames.putAll(next.previewFrames)
    if(leaving) frames[old.selectedBlock]=old.screen!!
    while(frames.size>16) frames.remove(frames.keys.first())
    return frames
}

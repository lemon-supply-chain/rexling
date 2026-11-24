package dev.lemon.rex

/** Rex expresses order changes as “move this tab before that tab”; null means the end. */
internal fun moveTabBefore(ids: List<String>, id: String, before: String?): List<String>? {
    if (
        id !in ids ||
            before == id ||
            (before != null && before !in ids) ||
            ids.toSet().size != ids.size
    )
        return null
    return ids.filterNot { it == id }
        .toMutableList()
        .apply { add(if (before == null) size else indexOf(before), id) }
}

internal data class PendingTabOrder(val session: String, val request: Long, val ids: List<String>) {
    fun keepFor(state: AppState): PendingTabOrder? = takeIf {
        state.connected &&
            state.selectedSession == session &&
            state.tabMoveId < request &&
            state.windows.map { it.id }.toSet() == ids.toSet()
    }

    fun apply(state: AppState): List<WindowItem>? {
        if (keepFor(state) == null) return null
        val windows = state.windows.associateBy { it.id }
        return ids.map { windows.getValue(it) }
    }
}

internal data class PendingPaneOrder(
    val session: String,
    val window: String,
    val request: Long,
    val ids: List<String>,
) {
    fun keepFor(state: AppState): PendingPaneOrder? = takeIf {
        state.connected &&
            state.selectedSession == session &&
            state.paneMoveId < request &&
            state.windows.firstOrNull { it.id == window }?.blocks?.map { it.id }?.toSet() ==
                ids.toSet()
    }

    fun apply(state: AppState, windows: List<WindowItem>): List<WindowItem>? {
        if (keepFor(state) == null) return null
        return windows.map { w ->
            if (w.id != window) w
            else {
                val blocks = w.blocks.associateBy { it.id }
                w.copy(blocks = ids.map { blocks.getValue(it) })
            }
        }
    }
}

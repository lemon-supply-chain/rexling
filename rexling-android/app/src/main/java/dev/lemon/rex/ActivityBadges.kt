package dev.lemon.rex

/** Unread presentation only; never marks records seen or changes notification delivery. */
internal data class ActivityBadges(val tabs: Map<String,Int> = emptyMap(),val elsewhere: Int=0)
internal object ActivityBadgePolicy {
    fun route(sources: List<ActivitySource>,server: ServerProfile,session: String,windows: List<WindowItem>,visibleBlocks: Set<String> = emptySet()): ActivityBadges {
        val panes=windows.flatMap {w->w.blocks.map {it.id to w.id}}.toMap()
        val counts=mutableMapOf<String,Int>();var elsewhere=0
        for(source in sources.filter {it.connected}) {
            val local=source.server.id==server.id && source.server.endpoint==server.endpoint
            for(record in source.items.distinctBy {it.key}.filter {it.notifiable && it.key !in source.seen}) {
                val inSession=local && session.isNotBlank() && record.session==session
                if(inSession && record.block in visibleBlocks) continue
                val tab=if(inSession) panes[record.block] else null
                if(tab!=null) counts[tab]=(counts[tab] ?: 0)+1 else elsewhere++
            }
        }
        return ActivityBadges(counts,elsewhere)
    }
}

internal fun activityPaneCandidates(chosen: String,tiles: List<PanePlacement>?,floating: List<PanePlacement>,zoomed: String): List<String> {
    if(tiles==null) return listOfNotNull(chosen.takeIf {it.isNotBlank()})
    val panes=tiles+floating
    return if(panes.any {it.id==zoomed}) listOf(zoomed) else panes.map {it.id}
}

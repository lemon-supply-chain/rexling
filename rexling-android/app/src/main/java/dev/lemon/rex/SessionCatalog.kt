package dev.lemon.rex

/** IDs are local to a server. Only actual session IDs are sent over the wire. */
internal data class SessionTarget(
    val server: ServerProfile,
    val session: SessionItem,
    val detail: SessionDetail? = null,
) {
    val key: String
        get() = sessionKey(server.id, session.id)

    val stripItem: SessionItem
        get() = SessionItem(key, "${server.label} · ${session.label}")
}

internal fun sessionKey(serverId: String, sessionId: String): String =
    "${serverId.length}:$serverId$sessionId"

internal data class SessionGroup(val server: ServerProfile, val sessions: List<SessionTarget>)

internal object SessionCatalog {
    fun groups(targets: List<SessionTarget>, current: String, query: String): List<SessionGroup> {
        val currentServer = targets.firstOrNull { it.key == current }?.server?.id
        return targets
            .filter {
                it.session.label.contains(query, true) || it.server.label.contains(query, true)
            }
            .groupBy { it.server.id }
            .values
            .map { items ->
                SessionGroup(
                    items.first().server,
                    items.sortedBy { if (it.key == current) 0 else 1 },
                )
            }
            .sortedBy { if (it.server.id == currentServer) 0 else 1 }
    }

    fun collect(servers: List<ServerProfile>, states: Map<String, AppState>): List<SessionTarget> =
        servers.flatMap { server ->
            val state = states[server.id]
            if (state?.connected != true) emptyList()
            else
                state.sessions.map { session ->
                    val detail =
                        if (session.id == state.selectedSession)
                            SessionDetail(
                                "this device",
                                state.windows.size,
                                state.windows.sumOf { it.blocks.size },
                            )
                        else state.sessionDetails[session.id]
                    SessionTarget(server, session, detail)
                }
        }

    fun strip(targets: List<SessionTarget>, active: SessionTarget): List<SessionItem> =
        (if (targets.none { it.key == active.key }) listOf(active.stripItem) else emptyList()) +
            targets.map { it.stripItem }
}

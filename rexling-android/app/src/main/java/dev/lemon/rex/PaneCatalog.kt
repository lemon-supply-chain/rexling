package dev.lemon.rex

import org.json.JSONObject

data class PaneCatalogEntry(
    val session: String,
    val block: String,
    val label: String,
    val program: ProgramIdentity,
    val title: String,
    val directory: String,
)

data class PaneCatalogSnapshot(
    val items: List<PaneCatalogEntry>,
    val partial: Boolean,
    val error: Boolean,
) {
    companion object {
        internal fun parse(value: JSONObject): PaneCatalogSnapshot {
            fun JSONObject.display(key: String, max: Int) =
                optString(key)
                    .filterNot {
                        it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt()
                    }
                    .take(max)
            val items =
                value
                    .optJSONArray("items")
                    .objects { it as? JSONObject }
                    .mapNotNull { pane ->
                        val session = pane?.optString("session").orEmpty()
                        val block = pane?.optString("block").orEmpty()
                        if (pane == null || session.isBlank() || block.isBlank())
                            return@mapNotNull null
                        PaneCatalogEntry(
                            session,
                            block,
                            pane.display("label", 256),
                            ProgramIdentity.resolve(
                                pane.optJSONArray("programs").objects { it as String },
                            ),
                            pane.display("title", 256),
                            pane.display("directory", 1024),
                        )
                    }
            return PaneCatalogSnapshot(
                items,
                value.optBoolean("partial"),
                value.optBoolean("error"),
            )
        }
    }
}

internal data class PaneCatalogTarget(
    val server: ServerProfile,
    val session: SessionItem,
    val pane: PaneCatalogEntry,
    val activity: ActivityState?,
) {
    val key = sessionKey(server.id, session.id).let { "${it.length}:$it${pane.block}" }
    val title: String
        get() = pane.program.paneTitle(pane.label, pane.title)
}

internal data class PaneCatalogSource(
    val server: ServerProfile,
    val panes: List<PaneCatalogTarget>,
    val loading: Boolean,
    val incomplete: Boolean,
)

internal object PaneCatalog {
    fun collect(server: ServerProfile, state: AppState): PaneCatalogSource {
        val sessions = state.sessions.associateBy { it.id }
        val snapshot = state.paneCatalog
        val cached =
            snapshot
                ?.items
                .orEmpty()
                .filter { it.session == state.selectedSession }
                .associateBy { it.block }
        val live =
            state.windows.flatMap { window ->
                window.blocks.map { block ->
                    val fallback = cached[block.id]
                    PaneCatalogEntry(
                        state.selectedSession,
                        block.id,
                        block.label,
                        state.programs[block.id]?.takeUnless { it.pending }
                            ?: fallback?.program
                            ?: ProgramIdentity.Terminal,
                        state.titles[block.id].orEmpty().ifBlank { fallback?.title.orEmpty() },
                        state.directories[block.id].orEmpty().ifBlank {
                            fallback?.directory.orEmpty()
                        },
                    )
                }
            }
        val entries =
            snapshot?.items.orEmpty().filterNot { it.session == state.selectedSession } + live
        val activity = state.activity.groupBy { it.session to it.block }
        val panes =
            entries
                .distinctBy { it.session to it.block }
                .mapNotNull { entry ->
                    val session = sessions[entry.session] ?: return@mapNotNull null
                    val status =
                        activity[entry.session to entry.block]?.minByOrNull { it.state.priority }
                    val working =
                        entry.session == state.selectedSession && entry.block in state.workingBlocks
                    val indicator =
                        if (
                            working &&
                                (status?.state?.priority ?: Int.MAX_VALUE) >=
                                    ActivityState.WORKING.priority
                        )
                            ActivityState.WORKING
                        else status?.state
                    val pane =
                        if (status != null)
                            entry.copy(
                                program =
                                    if (entry.program == ProgramIdentity.Terminal) status.program
                                    else entry.program,
                                title = entry.title.ifBlank { status.title },
                            )
                        else entry
                    PaneCatalogTarget(server, session, pane, indicator)
                }
                .groupBy { it.session.id }
        return PaneCatalogSource(
            server,
            state.sessions.flatMap { panes[it.id].orEmpty() },
            snapshot == null || state.paneCatalogLoading,
            snapshot?.partial == true || snapshot?.error == true,
        )
    }

    fun matches(target: PaneCatalogTarget, query: String): Boolean =
        listOf(target.title, target.pane.directory, target.session.label, target.server.label).any {
            it.contains(query, ignoreCase = true)
        }
}

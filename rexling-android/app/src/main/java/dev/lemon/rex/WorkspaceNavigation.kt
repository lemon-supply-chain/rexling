package dev.lemon.rex

import org.json.JSONObject

data class WorkspaceTarget(val session: String,val block: String?=null)

/** Per-session/tab history; closed tabs/panes fall back to an existing terminal. */
class WorkspaceNavigation {
    private val windows = mutableMapOf<String, String>()
    private val panes = mutableMapOf<Pair<String, String>, String>()
    private val blocks = mutableMapOf<String,String>()

    fun neighbourSession(available: List<SessionItem>, session: String, step: Int): String? {
        if (step != -1 && step != 1) return null
        val index = available.indexOfFirst { it.id == session }
        return if (index < 0 || available.size<2) null else available[Math.floorMod(index+step,available.size)].id
    }

    fun neighbour(available: List<WindowItem>, block: String, step: Int): String? {
        if (step != -1 && step != 1) return null
        val window = available.firstOrNull { it.blocks.any { pane -> pane.id == block } } ?: return null
        val index = window.blocks.indexOfFirst { it.id == block }
        return if(window.blocks.size<2) null else window.blocks[Math.floorMod(index+step,window.blocks.size)].id
    }

    fun request(session: String,block: String): Boolean {
        if(session.isBlank() || block.isBlank() || blocks[session]==block) return false
        blocks[session]=block
        return true
    }

    fun remember(session: String, available: List<WindowItem>, block: String): Boolean {
        if(session.isBlank()) return false
        val window = available.firstOrNull { it.blocks.any { pane -> pane.id == block } } ?: return false
        val changed=request(session,block) || windows[session]!=window.id || panes[session to window.id]!=block
        windows[session] = window.id
        panes[session to window.id] = block
        return changed
    }

    fun pane(session: String, window: WindowItem): String? =
        window.blocks.firstOrNull { it.id == panes[session to window.id] }?.id ?: window.blocks.firstOrNull()?.id

    fun pane(session: String, available: List<WindowItem>): String? {
        available.firstOrNull {w->w.blocks.any {it.id==blocks[session]}}?.let {return blocks[session]}
        val window = available.firstOrNull { it.id == windows[session] && it.blocks.isNotEmpty() }
            ?: available.firstOrNull { it.blocks.isNotEmpty() } ?: return null
        return pane(session, window)
    }

    fun target(state: AppState,savedSession: String): WorkspaceTarget? {
        if(!state.connected || state.creating || state.awaitingPaneSelection) return null
        val session=state.sessions.firstOrNull {it.id==savedSession}
            ?: state.sessions.firstOrNull {it.id==state.selectedSession} ?: state.sessions.firstOrNull() ?: return null
        if(session.id!=state.selectedSession) return WorkspaceTarget(session.id)
        val block=pane(session.id,state.windows.map {window->window.copy(blocks=window.visiblePanes())}) ?: return null
        return if(block!=state.selectedBlock) WorkspaceTarget(session.id,block) else null
    }

    fun forget(session: String): Boolean {
        val hadWindow=windows.remove(session)!=null
        val hadBlock=blocks.remove(session)!=null
        val hadPanes=panes.keys.removeAll {it.first==session}
        return hadWindow || hadBlock || hadPanes
    }

    fun encode(): String = JSONObject().apply {
        for(session in windows.keys+blocks.keys) put(session,JSONObject().apply {
            put("window",windows[session].orEmpty());put("block",blocks[session].orEmpty())
            put("panes",JSONObject().apply {panes.filterKeys {it.first==session}.forEach {(key,block)->put(key.second,block)}})
        })
    }.toString()

    companion object {
        fun decode(value: String?): WorkspaceNavigation {
            val result=WorkspaceNavigation()
            val root=runCatching {JSONObject(value ?: "{}")}.getOrNull() ?: return result
            for(session in root.keys()) {
                if(session.isBlank()) continue
                val item=root.optJSONObject(session) ?: continue
                (item.opt("window") as? String)?.takeIf {it.isNotBlank()}?.let {result.windows[session]=it}
                (item.opt("block") as? String)?.takeIf {it.isNotBlank()}?.let {result.blocks[session]=it}
                item.optJSONObject("panes")?.let {saved->for(window in saved.keys()) {
                    if(window.isNotBlank()) (saved.opt(window) as? String)?.takeIf {it.isNotBlank()}?.let {result.panes[session to window]=it}
                }}
            }
            return result
        }
    }
}

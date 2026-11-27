package dev.lemon.rex

import kotlin.math.abs
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Debug-only showcase configuration launched explicitly from the Welcome preview button. */
internal object DebugShowcase {
    const val available = true
    const val ENDPOINT = "showcase://debug"
    val Profile = ServerProfile("__debug_showcase__", "Preview", ENDPOINT, ServerIcon.DESKTOP)

    fun profile(): ServerProfile? = Profile

    fun isProfile(id: String): Boolean = id == Profile.id

    fun createSession(
        scope: CoroutineScope,
        initialPalette: TerminalPalette,
        onState: (AppState) -> Unit,
        onPaneFrames: (Map<String, TerminalScreen>) -> Unit,
    ): ShowcaseSession? =
        DebugShowcaseSession(
            scope = scope,
            endpoint = ENDPOINT,
            initialPalette = initialPalette,
            onState = onState,
            onPaneFrames = onPaneFrames,
        )
}

/**
 * In-memory simulated Rex workspace for testing UI states, running animations, looping countdown
 * dials, floating/tiled layouts, activity alerts, and terminal interactions in debug builds.
 */
internal class DebugShowcaseSession(
    private val scope: CoroutineScope,
    private val endpoint: String,
    initialPalette: TerminalPalette = TerminalPalette.Dark,
    private val tickIntervalMs: Long = 200L,
    private val tickerDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val onState: (AppState) -> Unit,
    private val onPaneFrames: (Map<String, TerminalScreen>) -> Unit = {},
) : ShowcaseSession {
    private enum class PaneRole {
        RUNNING_AGENT,
        LOOP_COUNTDOWN,
        NEOVIM_EDITOR,
        FLOATING_REVIEW,
        LOOP_DIAL,
        INDETERMINATE_SERVER,
        PAUSED_SYNC,
        RUNNING_TESTS,
        DONE_MONITOR,
        ERROR_DEPLOY,
        INTERACTIVE_SHELL,
        LAB_API,
        LAB_DB,
        LAB_LOGS,
        USER_CREATED,
    }

    private data class PaneState(
        val id: String,
        val label: String,
        val program: ProgramIdentity,
        val role: PaneRole,
        val directory: String,
        val baseTitle: String,
        val countdownOffset: Int = 0,
        var scrollOffsetFromBottom: Int = 0,
        val extraLines: MutableList<String> = mutableListOf(),
        val inputLine: StringBuilder = StringBuilder(),
        val editorKeys: MutableList<String> = mutableListOf(),
    )

    private data class TabState(
        val id: String,
        val label: String,
        val blockIds: MutableList<String>,
        val layout: MutableList<PanePlacement>,
        val splits: MutableList<PaneSplitLine>,
        var zoomed: String = "",
    )

    private data class SessionState(
        val id: String,
        var label: String,
        val tabs: MutableList<TabState>,
        val panes: MutableMap<String, PaneState>,
    )

    private val sessions = mutableListOf<SessionState>()
    private var selectedSessionId = ""
    private var selectedBlockId = ""
    private var phase = "disconnected"
    private var controlled = false
    private var palette = initialPalette
    private var revision = 0L
    private var connectionEpoch = 0L
    private var navigationId = 0L
    private var tabMoveId = 0L
    private var paneMoveId = 0L
    private var tickCount = 0L
    private var columns = 52
    private var rows = 26
    private val paneSizes = mutableMapOf<String, Pair<Int, Int>>()
    private val mirroredPanes = mutableSetOf<String>()
    private val peekedPanes = mutableSetOf<String>()
    private var mirrorObscured = false
    private var visible = true
    private var activityOpen = false
    private var paneCatalogOpen = false
    private val seenAttention = mutableMapOf<String, String>()
    private var error = ""
    private var notice = ""
    private var nextGeneratedId = 1
    private var tickerJob: Job? = null
    private var closed = false

    init {
        seedInitialWorkspace()
    }

    override fun start(active: Boolean) {
        if (closed) return
        connectionEpoch++
        controlled = active
        phase = if (active) "connected" else "paused"
        error = ""
        notice = "Showcase mode"
        if (active) startTicker() else stopTicker()
        emitState()
    }

    internal fun tick() {
        if (closed || phase != "connected") return
        tickCount++
        emitState()
        emitPreviewFrames()
    }

    override fun close() {
        closed = true
        stopTicker()
    }

    override fun command(type: String, request: JSONObject): Boolean {
        if (closed) return false
        error = ""
        when (type) {
            "session" -> {
                val id = request.optString("id")
                val requestedBlock = request.optString("block")
                val nav = request.optLong("navigationId")
                val session = sessions.firstOrNull { it.id == id }
                if (session == null) {
                    error = "Session not found: $id"
                    emitState()
                    return false
                }
                selectedSessionId = session.id
                navigationId = nav
                selectedBlockId =
                    if (requestedBlock.isNotBlank() && session.panes.containsKey(requestedBlock)) {
                        requestedBlock
                    } else {
                        firstVisibleBlock(session)
                    }
                applyGridArgs(selectedBlockId, request)
                emitState()
                emitPreviewFrames()
            }
            "block" -> {
                val id = request.optString("id")
                val nav = request.optLong("navigationId")
                val restoreMinimized = request.optBoolean("restoreMinimized")
                val session = currentSession() ?: return false
                if (!session.panes.containsKey(id)) {
                    error = "Pane not found: $id"
                    emitState()
                    return false
                }
                if (restoreMinimized) {
                    setFloatingMinimized(session, id, minimized = false)
                }
                selectedBlockId = id
                navigationId = nav
                applyGridArgs(id, request)
                emitState()
                emitPreviewFrames()
            }
            "take" -> {
                val sessionId = request.optString("session")
                val blockId = request.optString("block")
                val nav = request.optLong("navigationId")
                if (sessionId.isNotBlank() && sessions.any { it.id == sessionId }) {
                    selectedSessionId = sessionId
                }
                val session = currentSession()
                if (session != null && blockId.isNotBlank() && session.panes.containsKey(blockId)) {
                    selectedBlockId = blockId
                }
                if (nav > 0) navigationId = nav
                applyGridArgs(selectedBlockId, request)
                controlled = true
                emitState()
            }
            "release" -> {
                controlled = false
                emitState()
            }
            "resize" -> {
                val blockId = request.optString("block").ifBlank { selectedBlockId }
                val cols = request.optInt("columns", columns).coerceIn(16, 240)
                val r = request.optInt("rows", rows).coerceIn(6, 120)
                if (blockId == selectedBlockId || blockId.isBlank()) {
                    columns = cols
                    rows = r
                }
                if (blockId.isNotBlank()) paneSizes[blockId] = cols to r
                emitState()
            }
            "input" -> {
                val text = request.optString("text")
                val blockId = request.optString("block").ifBlank { selectedBlockId }
                val pane = currentSession()?.panes?.get(blockId) ?: return false
                handleInput(pane, text)
                emitState()
            }
            "paste" -> {
                val text = request.optString("text")
                val blockId = request.optString("block").ifBlank { selectedBlockId }
                val pane = currentSession()?.panes?.get(blockId) ?: return false
                handleInput(pane, text)
                emitState()
            }
            "scroll" -> {
                val blockId = request.optString("block").ifBlank { selectedBlockId }
                val pane = currentSession()?.panes?.get(blockId) ?: return false
                if (request.optBoolean("bottom")) {
                    pane.scrollOffsetFromBottom = 0
                } else {
                    val delta = request.optInt("lines", 0)
                    val maxOffset = maxScrollOffset(pane, rowsFor(blockId))
                    val currentOffset =
                        (maxOffset - pane.scrollOffsetFromBottom).coerceIn(0, maxOffset)
                    val nextOffset = (currentOffset + delta).coerceIn(0, maxOffset)
                    pane.scrollOffsetFromBottom = (maxOffset - nextOffset).coerceIn(0, maxOffset)
                }
                emitState()
            }
            "wheel" -> {
                val blockId = request.optString("block").ifBlank { selectedBlockId }
                val pane = currentSession()?.panes?.get(blockId) ?: return false
                val delta = request.optInt("lines", 0)
                val maxOffset = maxScrollOffset(pane, rowsFor(blockId))
                val currentOffset = (maxOffset - pane.scrollOffsetFromBottom).coerceIn(0, maxOffset)
                val nextOffset = (currentOffset + delta).coerceIn(0, maxOffset)
                pane.scrollOffsetFromBottom = (maxOffset - nextOffset).coerceIn(0, maxOffset)
                emitState()
            }
            "scroll_prompt" -> {
                val blockId = request.optString("block").ifBlank { selectedBlockId }
                val pane = currentSession()?.panes?.get(blockId) ?: return false
                val direction = request.optInt("direction", 0)
                val maxOffset = maxScrollOffset(pane, rowsFor(blockId))
                val step = (rowsFor(blockId) / 2).coerceAtLeast(4)
                val next =
                    if (direction < 0) (pane.scrollOffsetFromBottom + step).coerceAtMost(maxOffset)
                    else (pane.scrollOffsetFromBottom - step).coerceAtLeast(0)
                pane.scrollOffsetFromBottom = next
                emitState()
            }
            "new_session" -> {
                val rawLabel = request.optString("label").trim()
                val label = rawLabel.ifBlank { "Showcase ${sessions.size + 1}" }
                val sid = "demo-session-${nextGeneratedId++}"
                val wid = "w-user-${nextGeneratedId++}"
                val pid = "p-user-${nextGeneratedId++}"
                val pane =
                    PaneState(
                        id = pid,
                        label = "zsh",
                        program = ProgramIdentity.resolve(listOf("zsh")),
                        role = PaneRole.INTERACTIVE_SHELL,
                        directory = "~/dev/rexling",
                        baseTitle = "zsh",
                    )
                val tab =
                    TabState(
                        id = wid,
                        label = "Shell",
                        blockIds = mutableListOf(pid),
                        layout = mutableListOf(PanePlacement(pid, 0f, 0f, 1f, 1f)),
                        splits = mutableListOf(),
                    )
                sessions +=
                    SessionState(
                        id = sid,
                        label = label,
                        tabs = mutableListOf(tab),
                        panes = mutableMapOf(pid to pane),
                    )
                selectedSessionId = sid
                selectedBlockId = pid
                emitState()
            }
            "rename_session" -> {
                val id = request.optString("id")
                val label = request.optString("label").trim()
                val session = sessions.firstOrNull { it.id == id }
                if (session == null || label.isEmpty()) return false
                session.label = label
                emitState()
            }
            "close_session" -> {
                val id = request.optString("id")
                if (sessions.none { it.id == id }) return false
                sessions.removeAll { it.id == id }
                if (selectedSessionId == id) {
                    val next = sessions.firstOrNull()
                    selectedSessionId = next?.id.orEmpty()
                    selectedBlockId = next?.let(::firstVisibleBlock).orEmpty()
                }
                emitState()
            }
            "new_tab" -> {
                val sessionId = request.optString("session").ifBlank { selectedSessionId }
                val session = sessions.firstOrNull { it.id == sessionId } ?: return false
                val wid = "w-tab-${nextGeneratedId++}"
                val pid = "p-pane-${nextGeneratedId++}"
                val pane =
                    PaneState(
                        id = pid,
                        label = "zsh",
                        program = ProgramIdentity.resolve(listOf("zsh")),
                        role = PaneRole.INTERACTIVE_SHELL,
                        directory = "~/dev/rexling",
                        baseTitle = "zsh",
                    )
                val tab =
                    TabState(
                        id = wid,
                        label = "Tab ${session.tabs.size + 1}",
                        blockIds = mutableListOf(pid),
                        layout = mutableListOf(PanePlacement(pid, 0f, 0f, 1f, 1f)),
                        splits = mutableListOf(),
                    )
                session.panes[pid] = pane
                session.tabs += tab
                if (session.id == selectedSessionId) selectedBlockId = pid
                emitState()
            }
            "new_pane" -> {
                val sessionId = request.optString("session").ifBlank { selectedSessionId }
                val session = sessions.firstOrNull { it.id == sessionId } ?: return false
                val anchor = request.optString("anchor").ifBlank { selectedBlockId }
                val direction = request.optString("direction", "horizontal")
                val tab = session.tabs.firstOrNull { anchor in it.blockIds } ?: return false
                val pid = "p-pane-${nextGeneratedId++}"
                val pane =
                    PaneState(
                        id = pid,
                        label = "Pane ${tab.blockIds.size + 1}",
                        program = ProgramIdentity.resolve(listOf("zsh")),
                        role = PaneRole.USER_CREATED,
                        directory = "~/dev/rexling",
                        baseTitle = "Interactive shell",
                    )
                session.panes[pid] = pane
                val anchorIndex = tab.blockIds.indexOf(anchor).coerceAtLeast(0)
                tab.blockIds.add(anchorIndex + 1, pid)
                splitPlacement(tab, anchor, pid, horizontal = direction != "vertical")
                tab.zoomed = ""
                if (session.id == selectedSessionId) selectedBlockId = pid
                emitState()
            }
            "close_pane" -> {
                val sessionId = request.optString("session").ifBlank { selectedSessionId }
                val id = request.optString("id")
                val session = sessions.firstOrNull { it.id == sessionId } ?: return false
                val tab = session.tabs.firstOrNull { id in it.blockIds } ?: return false
                tab.blockIds.remove(id)
                session.panes.remove(id)
                if (tab.zoomed == id) tab.zoomed = ""
                if (tab.blockIds.isEmpty()) {
                    session.tabs.remove(tab)
                } else {
                    rebuildTabGeometryAfterRemoval(tab)
                }
                if (session.tabs.isEmpty()) {
                    sessions.remove(session)
                    if (selectedSessionId == session.id) {
                        val next = sessions.firstOrNull()
                        selectedSessionId = next?.id.orEmpty()
                        selectedBlockId = next?.let(::firstVisibleBlock).orEmpty()
                    }
                } else if (selectedSessionId == session.id && selectedBlockId == id) {
                    selectedBlockId = tab.blockIds.firstOrNull() ?: firstVisibleBlock(session)
                }
                emitState()
            }
            "close_tab" -> {
                val sessionId = request.optString("session").ifBlank { selectedSessionId }
                val tabId = request.optString("id")
                val session = sessions.firstOrNull { it.id == sessionId } ?: return false
                val tab = session.tabs.firstOrNull { it.id == tabId } ?: return false
                val removed = tab.blockIds.toSet()
                session.tabs.remove(tab)
                removed.forEach { session.panes.remove(it) }
                if (session.tabs.isEmpty()) {
                    sessions.remove(session)
                    if (selectedSessionId == session.id) {
                        val next = sessions.firstOrNull()
                        selectedSessionId = next?.id.orEmpty()
                        selectedBlockId = next?.let(::firstVisibleBlock).orEmpty()
                    }
                } else if (selectedSessionId == session.id && selectedBlockId in removed) {
                    selectedBlockId = firstVisibleBlock(session)
                }
                emitState()
            }
            "rename_tab" -> {
                val session =
                    sessions.firstOrNull { it.id == request.optString("session") }
                        ?: currentSession()
                        ?: return false
                val tabId = request.optString("window").ifBlank { request.optString("id") }
                val label = request.optString("label").trim()
                val tab = session.tabs.firstOrNull { it.id == tabId } ?: return false
                if (label.isEmpty()) return false
                val idx = session.tabs.indexOf(tab)
                session.tabs[idx] = tab.copy(label = label)
                emitState()
            }
            "rename_pane" -> {
                val session =
                    sessions.firstOrNull { it.id == request.optString("session") }
                        ?: currentSession()
                        ?: return false
                val paneId = request.optString("id")
                val label = request.optString("label").trim()
                val pane = session.panes[paneId] ?: return false
                if (label.isEmpty()) return false
                session.panes[paneId] = pane.copy(label = label)
                emitState()
            }
            "float_pane" -> {
                val session =
                    sessions.firstOrNull { it.id == request.optString("session") }
                        ?: currentSession()
                        ?: return false
                val tabId = request.optString("window")
                val paneId = request.optString("id")
                val tab = session.tabs.firstOrNull { it.id == tabId } ?: return false
                if (paneId !in tab.blockIds) return false
                val bounds = PaneBounds(0.18f, 0.14f, 0.64f, 0.68f)
                val layerIndex = (tab.layout.maxOfOrNull { it.layer } ?: 0) + 1
                tab.layout.removeAll { it.id == paneId }
                tab.layout +=
                    PanePlacement(
                        id = paneId,
                        x = bounds.x,
                        y = bounds.y,
                        width = bounds.width,
                        height = bounds.height,
                        layer = layerIndex,
                        floating = true,
                        layerId = "float-$paneId",
                        layerBounds = bounds,
                        minimized = false,
                    )
                rebuildTiledGeometry(tab)
                if (request.optBoolean("focus")) selectedBlockId = paneId
                emitState()
            }
            "dock_pane" -> {
                val session =
                    sessions.firstOrNull { it.id == request.optString("session") }
                        ?: currentSession()
                        ?: return false
                val tabId = request.optString("window")
                val paneId = request.optString("id")
                val anchor = request.optString("anchor")
                val direction = request.optString("direction", "horizontal")
                val tab = session.tabs.firstOrNull { it.id == tabId } ?: return false
                if (paneId !in tab.blockIds) return false
                tab.layout.removeAll { it.id == paneId }
                val targetAnchor =
                    anchor.takeIf { it in tab.blockIds && it != paneId }
                        ?: tab.layout.firstOrNull { !it.floating }?.id
                if (targetAnchor != null) {
                    splitPlacement(tab, targetAnchor, paneId, horizontal = direction != "vertical")
                } else {
                    tab.layout += PanePlacement(paneId, 0f, 0f, 1f, 1f)
                }
                emitState()
            }
            "resize_floating" -> {
                val session =
                    sessions.firstOrNull { it.id == request.optString("session") }
                        ?: currentSession()
                        ?: return false
                val tabId = request.optString("window")
                val paneId = request.optString("id")
                val bounds = PaneBounds.parse(request.optJSONArray("bounds")) ?: return false
                val tab = session.tabs.firstOrNull { it.id == tabId } ?: return false
                val idx = tab.layout.indexOfFirst { it.id == paneId && it.floating }
                if (idx >= 0) {
                    val existing = tab.layout[idx]
                    tab.layout[idx] =
                        existing.copy(
                            x = bounds.x,
                            y = bounds.y,
                            width = bounds.width,
                            height = bounds.height,
                            layerBounds = bounds,
                        )
                    emitState()
                }
            }
            "minimize_floating" -> {
                val session =
                    sessions.firstOrNull { it.id == request.optString("session") }
                        ?: currentSession()
                        ?: return false
                val paneId = request.optString("id")
                setFloatingMinimized(session, paneId, minimized = true)
                if (selectedBlockId == paneId) {
                    val tab = session.tabs.firstOrNull { paneId in it.blockIds }
                    selectedBlockId =
                        tab?.layout?.firstOrNull { !it.floating && !it.minimized }?.id
                            ?: firstVisibleBlock(session)
                }
                emitState()
            }
            "move_tab" -> {
                val sessionId = request.optString("session").ifBlank { selectedSessionId }
                val session = sessions.firstOrNull { it.id == sessionId } ?: return false
                val id = request.optString("id")
                val before = request.optString("before").takeIf { it.isNotEmpty() }
                val reqId = request.optLong("requestId")
                val nextOrder =
                    moveTabBefore(session.tabs.map { it.id }, id, before) ?: return false
                val byId = session.tabs.associateBy { it.id }
                session.tabs.clear()
                nextOrder.mapTo(session.tabs) { byId.getValue(it) }
                if (reqId > tabMoveId) tabMoveId = reqId
                emitState()
            }
            "move_pane" -> {
                val sessionId = request.optString("session").ifBlank { selectedSessionId }
                val session = sessions.firstOrNull { it.id == sessionId } ?: return false
                val windowId = request.optString("window")
                val tab = session.tabs.firstOrNull { it.id == windowId } ?: return false
                val source = request.optString("id")
                val swapWith = request.optString("swapWith")
                if (swapWith.isNotBlank()) {
                    val i = tab.blockIds.indexOf(source)
                    val j = tab.blockIds.indexOf(swapWith)
                    if (i >= 0 && j >= 0 && i != j) {
                        tab.blockIds[i] = swapWith
                        tab.blockIds[j] = source
                        swapLayoutPlacements(tab, source, swapWith)
                    }
                } else {
                    val before = request.optString("before").takeIf { it.isNotEmpty() }
                    val reqId = request.optLong("requestId")
                    val nextOrder = moveTabBefore(tab.blockIds, source, before) ?: return false
                    tab.blockIds.clear()
                    tab.blockIds.addAll(nextOrder)
                    if (reqId > paneMoveId) paneMoveId = reqId
                }
                emitState()
            }
            "place_pane" -> {
                val sessionId = request.optString("session").ifBlank { selectedSessionId }
                val session = sessions.firstOrNull { it.id == sessionId } ?: return false
                val windowId = request.optString("window")
                val tab = session.tabs.firstOrNull { it.id == windowId } ?: return false
                val source = request.optString("id")
                val anchor = request.optString("anchor")
                val position = request.optString("position")
                if (source !in tab.blockIds || anchor !in tab.blockIds || source == anchor) {
                    return false
                }
                val sourceIdx = tab.blockIds.indexOf(source)
                val anchorIdx = tab.blockIds.indexOf(anchor)
                if (sourceIdx >= 0 && anchorIdx >= 0) {
                    tab.blockIds.removeAt(sourceIdx)
                    val insertAt =
                        tab.blockIds.indexOf(anchor).let { idx ->
                            if (position == "right" || position == "below") idx + 1 else idx
                        }
                    tab.blockIds.add(insertAt.coerceIn(0, tab.blockIds.size), source)
                    val horizontal = position == "left" || position == "right"
                    val placeBefore = position == "left" || position == "above"
                    tab.layout.removeAll { it.id == source }
                    rebuildTiledGeometry(tab)
                    splitPlacement(tab, anchor, source, horizontal, placeBefore)
                }
                emitState()
            }
            "zoom" -> {
                val sessionId = request.optString("session").ifBlank { selectedSessionId }
                val session = sessions.firstOrNull { it.id == sessionId } ?: return false
                val blockId = request.optString("block")
                val zoom = request.optBoolean("zoom")
                val tab = session.tabs.firstOrNull { blockId in it.blockIds } ?: return false
                tab.zoomed = if (zoom) blockId else ""
                emitState()
            }
            "resize_split" -> {
                val sessionId = request.optString("session").ifBlank { selectedSessionId }
                val session = sessions.firstOrNull { it.id == sessionId } ?: return false
                val direction = request.optString("direction")
                val before = request.optString("before")
                val after = request.optString("after")
                val ratio = request.optDouble("ratio", 0.5).toFloat().coerceIn(0.1f, 0.9f)
                val horizontal = direction == "horizontal"
                val tab =
                    session.tabs.firstOrNull { t ->
                        t.splits.any {
                            it.horizontal == horizontal && it.before == before && it.after == after
                        }
                    } ?: return false
                val split =
                    tab.splits.first {
                        it.horizontal == horizontal && it.before == before && it.after == after
                    }
                val (nextLayout, nextSplits) = movedSplit(tab.layout, tab.splits, split, ratio)
                tab.layout.clear()
                tab.layout.addAll(nextLayout)
                tab.splits.clear()
                tab.splits.addAll(nextSplits)
                emitState()
            }
            "peek" -> {
                peekedPanes.clear()
                val ids = request.optJSONArray("ids")
                if (ids != null) {
                    for (i in 0 until ids.length()) {
                        ids.optString(i).takeIf { it.isNotBlank() }?.let { peekedPanes += it }
                    }
                }
                emitPreviewFrames()
            }
            "mirror" -> {
                mirroredPanes.clear()
                val panes = request.optJSONArray("panes")
                if (panes != null) {
                    for (i in 0 until panes.length()) {
                        val obj = panes.optJSONObject(i) ?: continue
                        val id = obj.optString("block")
                        if (id.isNotBlank()) {
                            mirroredPanes += id
                            val cols = obj.optInt("columns", 0)
                            val r = obj.optInt("rows", 0)
                            if (cols > 0 && r > 0) {
                                paneSizes[id] = cols.coerceIn(16, 240) to r.coerceIn(6, 120)
                            }
                        }
                    }
                }
                emitPreviewFrames()
            }
            "mirror_obscured" -> {
                mirrorObscured = request.optBoolean("obscured")
            }
            "pane_catalog" -> {
                paneCatalogOpen = request.optBoolean("open")
                emitState()
            }
            "sessions_info" -> {
                emitState()
            }
            "activity" -> {
                activityOpen = request.optBoolean("open")
                emitState()
            }
            "activity_seen" -> {
                val items = request.optJSONObject("items")
                if (items != null) {
                    seenAttention.clear()
                    items.keys().forEach { key -> seenAttention[key] = items.optString(key) }
                }
                emitState()
            }
            "theme" -> {
                val args = request.optJSONObject("args")
                if (args != null) {
                    palette = parseThemePalette(args, palette)
                    emitState()
                    emitPreviewFrames()
                }
            }
            "pause" -> {
                stopTicker()
                controlled = false
                if (phase == "connected") phase = "paused"
                emitState()
            }
            "resume" -> {
                if (phase == "paused" || phase == "disconnected") {
                    phase = "connected"
                    controlled = true
                    startTicker()
                    emitState()
                }
            }
            "visibility" -> {
                visible = request.optBoolean("visible", true)
                if (visible && phase == "connected") startTicker() else stopTicker()
            }
            "retry",
            "network" -> {
                if (phase != "disconnected") {
                    phase = "connected"
                    startTicker()
                    emitState()
                }
            }
            "refresh" -> {
                emitState()
                emitPreviewFrames()
            }
            "action" -> {
                val action = request.optString("action")
                val blockId = request.optString("block").ifBlank { selectedBlockId }
                val pane = currentSession()?.panes?.get(blockId)
                if (pane != null && action.isNotBlank()) {
                    appendPaneLine(pane, "Action executed: $action")
                    emitState()
                }
            }
            "click",
            "copy_semantic",
            "prediction" -> Unit
            "disconnect" -> {
                stopTicker()
                controlled = false
                phase = "disconnected"
                selectedSessionId = ""
                selectedBlockId = ""
                mirroredPanes.clear()
                peekedPanes.clear()
                emitState()
            }
            else -> return false
        }
        return true
    }

    private fun startTicker() {
        if (closed || tickerJob?.isActive == true) return
        tickerJob =
            scope.launch(tickerDispatcher) {
                while (isActive && !closed && phase == "connected") {
                    delay(tickIntervalMs)
                    if (!closed && phase == "connected") {
                        tick()
                    }
                }
            }
    }

    private fun stopTicker() {
        tickerJob?.cancel()
        tickerJob = null
    }

    private fun currentSession(): SessionState? = sessions.firstOrNull {
        it.id == selectedSessionId
    }

    private fun firstVisibleBlock(session: SessionState): String {
        for (tab in session.tabs) {
            val visible =
                tab.blockIds.firstOrNull { id -> tab.layout.none { it.id == id && it.minimized } }
            if (visible != null) return visible
        }
        return session.tabs.firstOrNull()?.blockIds?.firstOrNull().orEmpty()
    }

    private fun applyGridArgs(blockId: String, request: JSONObject) {
        val cols = request.optInt("columns", 0)
        val r = request.optInt("rows", 0)
        if (cols > 0 && r > 0) {
            val clampedCols = cols.coerceIn(16, 240)
            val clampedRows = r.coerceIn(6, 120)
            if (blockId == selectedBlockId || blockId.isBlank()) {
                columns = clampedCols
                rows = clampedRows
            }
            if (blockId.isNotBlank()) paneSizes[blockId] = clampedCols to clampedRows
        }
    }

    private fun setFloatingMinimized(session: SessionState, paneId: String, minimized: Boolean) {
        for (tab in session.tabs) {
            val index = tab.layout.indexOfFirst { it.id == paneId && it.floating }
            if (index >= 0) {
                tab.layout[index] = tab.layout[index].copy(minimized = minimized)
            }
        }
    }

    private fun swapLayoutPlacements(tab: TabState, a: String, b: String) {
        val idxA = tab.layout.indexOfFirst { it.id == a }
        val idxB = tab.layout.indexOfFirst { it.id == b }
        if (idxA >= 0 && idxB >= 0) {
            val placeA = tab.layout[idxA]
            val placeB = tab.layout[idxB]
            tab.layout[idxA] = placeB.copy(id = a)
            tab.layout[idxB] = placeA.copy(id = b)
        }
    }

    private fun splitPlacement(
        tab: TabState,
        anchorId: String,
        newId: String,
        horizontal: Boolean,
        placeBefore: Boolean = false,
    ) {
        val anchorIndex = tab.layout.indexOfFirst { it.id == anchorId && !it.floating }
        if (anchorIndex < 0) {
            tab.layout += PanePlacement(newId, 0f, 0f, 1f, 1f)
            return
        }
        val anchor = tab.layout[anchorIndex]
        val firstId = if (placeBefore) newId else anchorId
        val secondId = if (placeBefore) anchorId else newId
        val firstBounds: PanePlacement
        val secondBounds: PanePlacement
        if (horizontal) {
            val halfW = (anchor.width / 2f).coerceAtLeast(0.05f)
            firstBounds = anchor.copy(id = firstId, width = halfW)
            secondBounds =
                anchor.copy(id = secondId, x = anchor.x + halfW, width = anchor.width - halfW)
        } else {
            val halfH = (anchor.height / 2f).coerceAtLeast(0.05f)
            firstBounds = anchor.copy(id = firstId, height = halfH)
            secondBounds =
                anchor.copy(id = secondId, y = anchor.y + halfH, height = anchor.height - halfH)
        }
        tab.layout[anchorIndex] = if (placeBefore) secondBounds else firstBounds
        tab.layout += if (placeBefore) firstBounds else secondBounds
        tab.splits +=
            PaneSplitLine(
                id = "split-${nextGeneratedId++}",
                horizontal = horizontal,
                ratio = 0.5f,
                x = anchor.x,
                y = anchor.y,
                width = anchor.width,
                height = anchor.height,
                before = firstId,
                after = secondId,
            )
    }

    private fun rebuildTiledGeometry(tab: TabState) {
        val floatingIds = tab.layout.filter { it.floating }.mapTo(hashSetOf()) { it.id }
        val tiledIds = tab.blockIds.filterNot { it in floatingIds }
        tab.layout.removeAll { !it.floating }
        tab.splits.clear()
        if (tiledIds.isEmpty()) return
        if (tiledIds.size == 1) {
            tab.layout += PanePlacement(tiledIds[0], 0f, 0f, 1f, 1f)
            return
        }
        val step = 1f / tiledIds.size
        tiledIds.forEachIndexed { index, id ->
            tab.layout +=
                PanePlacement(
                    id = id,
                    x = index * step,
                    y = 0f,
                    width = if (index == tiledIds.lastIndex) 1f - index * step else step,
                    height = 1f,
                )
        }
        for (i in 0 until tiledIds.lastIndex) {
            val leftStart = i * step
            val rightEnd = if (i + 1 == tiledIds.lastIndex) 1f else (i + 2) * step
            val totalW = rightEnd - leftStart
            tab.splits +=
                PaneSplitLine(
                    id = "split-${nextGeneratedId++}",
                    horizontal = true,
                    ratio = step / totalW,
                    x = leftStart,
                    y = 0f,
                    width = totalW,
                    height = 1f,
                    before = tiledIds[i],
                    after = tiledIds[i + 1],
                )
        }
    }

    private fun rebuildTabGeometryAfterRemoval(tab: TabState) {
        tab.layout.removeAll { it.id !in tab.blockIds }
        rebuildTiledGeometry(tab)
    }

    private fun handleInput(pane: PaneState, text: String) {
        if (pane.role == PaneRole.NEOVIM_EDITOR) {
            val formatted = formatEscapeSequence(text)
            pane.editorKeys += formatted
            while (pane.editorKeys.size > 8) pane.editorKeys.removeAt(0)
            return
        }
        for (ch in text) {
            when (ch) {
                '\r',
                '\n' -> {
                    val cmd = pane.inputLine.toString()
                    pane.inputLine.clear()
                    executeShellCommand(pane, cmd)
                }
                '\u007f',
                '\b' -> {
                    if (pane.inputLine.isNotEmpty()) {
                        pane.inputLine.deleteCharAt(pane.inputLine.length - 1)
                    }
                }
                '\u0003' -> {
                    appendPaneLine(pane, "^C")
                    pane.inputLine.clear()
                }
                else -> if (!ch.isISOControl()) pane.inputLine.append(ch)
            }
        }
        pane.scrollOffsetFromBottom = 0
    }

    private fun executeShellCommand(pane: PaneState, rawCmd: String) {
        val cmd = rawCmd.trim()
        appendPaneLine(pane, "$ $rawCmd")
        when {
            cmd.isEmpty() -> Unit
            cmd == "clear" -> pane.extraLines.clear()
            cmd == "help" -> {
                appendPaneLine(pane, "Commands: help, status, ls, countdown, clear, <text>")
            }
            cmd == "status" -> {
                val pct = loopPercent(pane.countdownOffset)
                appendPaneLine(
                    pane,
                    "phase=$phase session=$selectedSessionId pane=${pane.id} tick=$tickCount countdown=$pct%",
                )
            }
            cmd == "ls" -> {
                appendPaneLine(pane, "rexling-android/  rexling-core/  docs/  research/")
            }
            cmd == "countdown" -> {
                val pct = loopPercent(0)
                appendPaneLine(pane, "Loop countdown: $pct% (cycle ${tickCount / 51 + 1})")
            }
            else -> appendPaneLine(pane, "echo: $cmd")
        }
    }

    private fun appendPaneLine(pane: PaneState, line: String) {
        pane.extraLines += line
        while (pane.extraLines.size > 120) pane.extraLines.removeAt(0)
    }

    private fun formatEscapeSequence(text: String): String =
        when (text) {
            "\u001b" -> "<Esc>"
            "\r",
            "\n" -> "<CR>"
            "\t" -> "<Tab>"
            "\u001b[A" -> "<Up>"
            "\u001b[B" -> "<Down>"
            "\u001b[C" -> "<Right>"
            "\u001b[D" -> "<Left>"
            else ->
                text
                    .map { c ->
                        if (c.code in 1..26) "<C-${('a' + c.code - 1)}>"
                        else if (c == '\u001b') "<Esc>" else c.toString()
                    }
                    .joinToString("")
        }

    private fun loopPercent(offset: Int): Int = 100 - (((tickCount + offset) % 51).toInt() * 2)

    private fun rowsFor(blockId: String): Int = paneSizes[blockId]?.second ?: rows

    private fun columnsFor(blockId: String): Int = paneSizes[blockId]?.first ?: columns

    private fun emitState() {
        revision++
        if (phase != "connected" && phase != "paused") {
            onState(
                AppState(
                    revision = revision,
                    phase = phase,
                    endpoint = endpoint,
                    identity = "Showcase",
                    error = error,
                    notice = notice,
                    connectionEpoch = connectionEpoch,
                    navigationId = navigationId,
                    tabMoveId = tabMoveId,
                    paneMoveId = paneMoveId,
                ),
            )
            return
        }

        val session = currentSession()
        val programs = mutableMapOf<String, ProgramIdentity>()
        val titles = mutableMapOf<String, String>()
        val directories = mutableMapOf<String, String>()
        val workingBlocks = mutableSetOf<String>()
        val terminalProgress = mutableMapOf<String, TerminalProgress>()
        val windows = mutableListOf<WindowItem>()

        if (session != null) {
            for (tab in session.tabs) {
                val blocks =
                    tab.blockIds.mapNotNull { id ->
                        session.panes[id]?.let { BlockItem(it.id, it.label) }
                    }
                windows +=
                    WindowItem(
                        id = tab.id,
                        label = tab.label,
                        blocks = blocks,
                        layout = tab.layout.toList(),
                        splits = tab.splits.toList(),
                        zoomed = tab.zoomed,
                    )
            }
            for ((id, pane) in session.panes) {
                programs[id] = pane.program
                directories[id] = pane.directory
                titles[id] = liveTitle(pane)
                when (pane.role) {
                    PaneRole.RUNNING_AGENT,
                    PaneRole.RUNNING_TESTS,
                    PaneRole.LAB_LOGS -> workingBlocks += id
                    PaneRole.LOOP_COUNTDOWN,
                    PaneRole.LOOP_DIAL -> {
                        terminalProgress[id] =
                            TerminalProgress("set", loopPercent(pane.countdownOffset))
                    }
                    PaneRole.INDETERMINATE_SERVER -> {
                        terminalProgress[id] = TerminalProgress("indeterminate", null)
                    }
                    PaneRole.PAUSED_SYNC -> {
                        terminalProgress[id] = TerminalProgress("pause", 64)
                    }
                    PaneRole.ERROR_DEPLOY -> {
                        terminalProgress[id] = TerminalProgress("error", 42)
                    }
                    else -> Unit
                }
            }
        }

        val activity = buildActivityRecords()
        val attentionCount = activity.count { record ->
            record.notifiable &&
                (record.attentionKey.isBlank() ||
                    seenAttention[record.attentionKey] != record.attentionVersion)
        }
        val selectedPane = session?.panes?.get(selectedBlockId)
        val screen = selectedPane?.let {
            renderPaneScreen(it, columnsFor(it.id), rowsFor(it.id), isActive = true)
        }
        val sessionDetails = sessions.associate { s ->
            s.id to
                SessionDetail(
                    attachment = if (s.id == selectedSessionId) "this device" else "showcase",
                    tabs = s.tabs.size,
                    panes = s.panes.size,
                )
        }
        val catalog =
            if (paneCatalogOpen) {
                PaneCatalogSnapshot(
                    items =
                        sessions.flatMap { s ->
                            s.tabs.flatMap { tab ->
                                tab.blockIds.mapNotNull { pid ->
                                    s.panes[pid]?.let { p ->
                                        PaneCatalogEntry(
                                            session = s.id,
                                            block = p.id,
                                            label = p.label,
                                            program = p.program,
                                            title = liveTitle(p),
                                            directory = p.directory,
                                        )
                                    }
                                }
                            }
                        },
                    partial = false,
                    error = false,
                )
            } else null

        onState(
            AppState(
                terminalProgress = terminalProgress,
                programs = programs,
                workingBlocks = workingBlocks,
                activity = activity,
                titles = titles,
                directories = directories,
                sessionDetails = sessionDetails,
                paneCatalog = catalog,
                revision = revision,
                phase = phase,
                endpoint = endpoint,
                identity = "Showcase",
                error = error,
                notice = notice,
                sessions = sessions.map { SessionItem(it.id, it.label) },
                windows = windows,
                selectedSession = selectedSessionId,
                selectedBlock = selectedBlockId,
                controlled = controlled,
                screen = screen,
                navigationId = navigationId,
                tabMoveId = tabMoveId,
                paneMoveId = paneMoveId,
                connectionEpoch = connectionEpoch,
                attentionCount = attentionCount,
            ),
        )
    }

    private fun emitPreviewFrames() {
        if (closed || phase != "connected") return
        val session = currentSession() ?: return
        val targets =
            (mirroredPanes + peekedPanes)
                .filter { it != selectedBlockId && session.panes.containsKey(it) }
                .distinct()
        if (targets.isEmpty() || mirrorObscured) return
        val frames = targets.associateWith { id ->
            val pane = session.panes.getValue(id)
            renderPaneScreen(pane, columnsFor(id), rowsFor(id), isActive = false)
        }
        onPaneFrames(frames)
    }

    private fun liveTitle(pane: PaneState): String =
        when (pane.role) {
            PaneRole.RUNNING_AGENT -> {
                val frame = SPINNER_FRAMES[(tickCount % SPINNER_FRAMES.size).toInt()]
                "$frame Refactoring layout engine"
            }
            PaneRole.RUNNING_TESTS -> {
                val frame = SPINNER_FRAMES[(tickCount % SPINNER_FRAMES.size).toInt()]
                "$frame Running test suite"
            }
            PaneRole.LOOP_COUNTDOWN,
            PaneRole.LOOP_DIAL -> {
                "Loop countdown ${loopPercent(pane.countdownOffset)}%"
            }
            else -> pane.baseTitle
        }

    private fun buildActivityRecords(): List<ActivityRecord> {
        val list = mutableListOf<ActivityRecord>()
        for (session in sessions) {
            for (tab in session.tabs) {
                for (blockId in tab.blockIds) {
                    val pane = session.panes[blockId] ?: continue
                    val attentionKey = "[\"${session.id}\",\"${pane.id}\",\"\"]"
                    when (pane.role) {
                        PaneRole.RUNNING_AGENT -> {
                            list +=
                                ActivityRecord(
                                    session = session.id,
                                    sessionLabel = session.label,
                                    window = tab.id,
                                    windowLabel = tab.label,
                                    block = pane.id,
                                    blockLabel = pane.label,
                                    id = "",
                                    title = "Refactoring layout engine",
                                    message = "Verifying pane split metrics and recomposition",
                                    state = ActivityState.WORKING,
                                    kind = "task",
                                    progress = ((tickCount * 4) % 100).toInt(),
                                    updated = "2026-11-26T09:38:00Z",
                                    program = pane.program,
                                    attentionKey = attentionKey,
                                    attentionVersion = "v1-working",
                                )
                        }
                        PaneRole.LOOP_COUNTDOWN,
                        PaneRole.LOOP_DIAL -> {
                            val pct = loopPercent(pane.countdownOffset)
                            val prog = TerminalProgress("set", pct)
                            list +=
                                ActivityRecord(
                                    session = session.id,
                                    sessionLabel = session.label,
                                    window = tab.id,
                                    windowLabel = tab.label,
                                    block = pane.id,
                                    blockLabel = pane.label,
                                    id = "osc9-progress",
                                    title = "Loop countdown",
                                    message = "",
                                    state = ActivityState.WORKING,
                                    kind = "progress",
                                    progress = pct,
                                    updated = "2026-11-26T09:37:50Z",
                                    program = pane.program,
                                    terminalProgress = prog,
                                    progressOnly = true,
                                )
                        }
                        PaneRole.FLOATING_REVIEW -> {
                            list +=
                                ActivityRecord(
                                    session = session.id,
                                    sessionLabel = session.label,
                                    window = tab.id,
                                    windowLabel = tab.label,
                                    block = pane.id,
                                    blockLabel = pane.label,
                                    id = "",
                                    title = "Approve test run",
                                    message = "Run ./gradlew :app:testDebugUnitTest?",
                                    state = ActivityState.BLOCKED,
                                    kind = "permission",
                                    progress = null,
                                    updated = "2026-11-26T09:38:10Z",
                                    program = pane.program,
                                    attentionKey = attentionKey,
                                    attentionVersion = "v1-blocked-perm",
                                )
                        }
                        PaneRole.INDETERMINATE_SERVER -> {
                            list +=
                                ActivityRecord(
                                    session = session.id,
                                    sessionLabel = session.label,
                                    window = tab.id,
                                    windowLabel = tab.label,
                                    block = pane.id,
                                    blockLabel = pane.label,
                                    id = "osc9-progress",
                                    title = "Building bundle",
                                    message = "",
                                    state = ActivityState.WORKING,
                                    kind = "progress",
                                    progress = null,
                                    updated = "2026-11-26T09:37:40Z",
                                    program = pane.program,
                                    terminalProgress = TerminalProgress("indeterminate", null),
                                    progressOnly = true,
                                )
                        }
                        PaneRole.PAUSED_SYNC -> {
                            list +=
                                ActivityRecord(
                                    session = session.id,
                                    sessionLabel = session.label,
                                    window = tab.id,
                                    windowLabel = tab.label,
                                    block = pane.id,
                                    blockLabel = pane.label,
                                    id = "osc9-progress",
                                    title = "Sync paused",
                                    message = "",
                                    state = ActivityState.IDLE,
                                    kind = "progress",
                                    progress = 64,
                                    updated = "2026-11-26T09:36:00Z",
                                    program = pane.program,
                                    terminalProgress = TerminalProgress("pause", 64),
                                    progressOnly = true,
                                )
                        }
                        PaneRole.RUNNING_TESTS -> {
                            list +=
                                ActivityRecord(
                                    session = session.id,
                                    sessionLabel = session.label,
                                    window = tab.id,
                                    windowLabel = tab.label,
                                    block = pane.id,
                                    blockLabel = pane.label,
                                    id = "",
                                    title = "Running tests",
                                    message = "Executing Compose & unit verification suite",
                                    state = ActivityState.WORKING,
                                    kind = "task",
                                    progress = null,
                                    updated = "2026-11-26T09:38:05Z",
                                    program = pane.program,
                                    attentionKey = attentionKey,
                                    attentionVersion = "v1-running-tests",
                                )
                        }
                        PaneRole.DONE_MONITOR -> {
                            list +=
                                ActivityRecord(
                                    session = session.id,
                                    sessionLabel = session.label,
                                    window = tab.id,
                                    windowLabel = tab.label,
                                    block = pane.id,
                                    blockLabel = pane.label,
                                    id = "",
                                    title = "Benchmark complete",
                                    message = "60/60 frames under 4.2ms p95",
                                    state = ActivityState.DONE,
                                    kind = "result",
                                    progress = null,
                                    updated = "2026-11-26T09:35:00Z",
                                    program = pane.program,
                                    attentionKey = attentionKey,
                                    attentionVersion = "v1-done-bench",
                                )
                        }
                        PaneRole.ERROR_DEPLOY -> {
                            list +=
                                ActivityRecord(
                                    session = session.id,
                                    sessionLabel = session.label,
                                    window = tab.id,
                                    windowLabel = tab.label,
                                    block = pane.id,
                                    blockLabel = pane.label,
                                    id = "",
                                    title = "Image push failed",
                                    message = "Registry timeout after 42% (retrying)",
                                    state = ActivityState.ERROR,
                                    kind = "error",
                                    progress = null,
                                    updated = "2026-11-26T09:34:00Z",
                                    program = pane.program,
                                    attentionKey = attentionKey,
                                    attentionVersion = "v1-error-deploy",
                                    terminalProgress = TerminalProgress("error", 42),
                                )
                        }
                        PaneRole.LAB_LOGS -> {
                            list +=
                                ActivityRecord(
                                    session = session.id,
                                    sessionLabel = session.label,
                                    window = tab.id,
                                    windowLabel = tab.label,
                                    block = pane.id,
                                    blockLabel = pane.label,
                                    id = "",
                                    title = "Confirm staging rollout",
                                    message = "Promote showcase build #177 to staging?",
                                    state = ActivityState.BLOCKED,
                                    kind = "question",
                                    progress = null,
                                    updated = "2026-11-26T09:38:20Z",
                                    program = pane.program,
                                    attentionKey = attentionKey,
                                    attentionVersion = "v1-lab-question",
                                )
                        }
                        else -> Unit
                    }
                }
            }
        }
        return list
    }

    private data class StyledLine(
        val text: String,
        val ansiFg: Int? = null,
        val bold: Boolean = false,
        val dim: Boolean = false,
        val inverse: Boolean = false,
        val underline: Boolean = false,
        val hyperlink: String? = null,
        val semanticPrompt: Boolean = false,
    )

    private fun paneBufferLines(
        pane: PaneState,
        cols: Int,
    ): Pair<List<StyledLine>, Pair<Int, Int>> {
        val barWidth = (cols - 16).coerceIn(10, 28)
        val spinner = SPINNER_FRAMES[(tickCount % SPINNER_FRAMES.size).toInt()]
        val lines = mutableListOf<StyledLine>()
        var cursorRowOffset = 0
        var cursorCol = 0

        when (pane.role) {
            PaneRole.RUNNING_AGENT -> {
                lines += StyledLine("REX SHOWCASE · RUNNING AGENT", ansiFg = 6, bold = true)
                lines += StyledLine("Pane: ${pane.id} (${pane.program.title})", dim = true)
                lines += StyledLine("")
                lines +=
                    StyledLine(
                        "$spinner Active task: Refactoring layout engine",
                        ansiFg = 2,
                        bold = true,
                    )
                val step = ((tickCount / 5) % 4).toInt()
                val steps =
                    listOf(
                        "Inspecting pane geometry",
                        "Updating tab strip measurements",
                        "Running unit assertions",
                        "Streaming live diff",
                    )
                steps.forEachIndexed { idx, label ->
                    val marker =
                        when {
                            idx < step -> "✓"
                            idx == step -> spinner
                            else -> "·"
                        }
                    val color = if (idx < step) 2 else if (idx == step) 3 else null
                    lines += StyledLine("  $marker $label", ansiFg = color, dim = idx > step)
                }
                lines += StyledLine("")
                lines +=
                    StyledLine(
                        "Live elapsed: ${formatElapsed(tickCount)}  tick #$tickCount",
                        ansiFg = 4,
                    )
            }
            PaneRole.LOOP_COUNTDOWN,
            PaneRole.LOOP_DIAL -> {
                val pct = loopPercent(pane.countdownOffset)
                val cycle = ((tickCount + pane.countdownOffset) / 51) + 1
                val remainTenths = pct / 10
                val remainSub = pct % 10
                val filled = ((pct / 100f) * barWidth).toInt().coerceIn(0, barWidth)
                val bar = "█".repeat(filled) + "░".repeat((barWidth - filled).coerceAtLeast(0))
                val title =
                    if (pane.role == PaneRole.LOOP_COUNTDOWN) "REX SHOWCASE · LOOP COUNTDOWN"
                    else "REX SHOWCASE · COUNTDOWN DIAL"
                lines += StyledLine(title, ansiFg = 3, bold = true)
                lines += StyledLine("OSC 9;4 state=set  percent=$pct%  cycle=#$cycle", dim = true)
                lines += StyledLine("")
                lines +=
                    StyledLine("[$bar] ${pct.toString().padStart(3)}%", ansiFg = 2, bold = true)
                lines += StyledLine("")
                lines +=
                    StyledLine(
                        "Remaining: ${remainTenths}.${remainSub}s   Looping 100% -> 0%",
                        ansiFg = 6,
                    )
                lines += StyledLine("Watch tab tile & header dial sweep smoothly.", dim = true)
            }
            PaneRole.NEOVIM_EDITOR -> {
                lines += StyledLine("  1  package dev.lemon.rex", ansiFg = 5)
                lines += StyledLine("  2  ", dim = true)
                lines += StyledLine("  3  internal fun showcaseDemo() {", ansiFg = 4, bold = true)
                lines += StyledLine("  4      // Tap or drag Neovim shortcut wheel", dim = true)
                lines += StyledLine("  5      val active = true", ansiFg = 2)
                lines += StyledLine("  6  }", ansiFg = 4, bold = true)
                lines += StyledLine("  ~", ansiFg = 4, dim = true)
                val keysSummary =
                    if (pane.editorKeys.isEmpty()) "(waiting for shortcut wheel or key)"
                    else pane.editorKeys.joinToString(" ")
                lines +=
                    StyledLine(" NORMAL  MainActivity.kt   utf-8  $keysSummary", inverse = true)
            }
            PaneRole.FLOATING_REVIEW -> {
                lines += StyledLine("FLOATING PANE · PERMISSION CHECK", ansiFg = 3, bold = true)
                lines += StyledLine("Claude Review requested approval:", ansiFg = 7)
                lines += StyledLine("")
                lines += StyledLine("  $ ./gradlew :app:testDebugUnitTest", ansiFg = 6, bold = true)
                lines += StyledLine("")
                lines += StyledLine("Use floating controls to drag, resize, dock,", dim = true)
                lines += StyledLine("minimize to dock, or inspect in Activity.", dim = true)
            }
            PaneRole.INDETERMINATE_SERVER -> {
                lines += StyledLine("DEV SERVER · INDETERMINATE PROGRESS", ansiFg = 6, bold = true)
                lines +=
                    StyledLine(
                        "$spinner Building client bundle (OSC 9;4 indeterminate)",
                        ansiFg = 3,
                    )
                lines += StyledLine("")
                lines += StyledLine("Local preview URL (tap to test forwarding):", dim = true)
                lines +=
                    StyledLine(
                        "  http://localhost:5173/preview",
                        ansiFg = 4,
                        underline = true,
                        hyperlink = "http://localhost:5173/preview",
                    )
            }
            PaneRole.PAUSED_SYNC -> {
                lines += StyledLine("SYNC PAUSED · OSC 9;4 state=pause", ansiFg = 3, bold = true)
                lines += StyledLine("⏸ Paused at 64% · waiting for resume", ansiFg = 3)
                lines += StyledLine("Demonstrates paused progress color & dial.", dim = true)
            }
            PaneRole.RUNNING_TESTS -> {
                lines += StyledLine("RUNNING · WORKER MARQUEE", ansiFg = 2, bold = true)
                lines += StyledLine("$spinner Running unit & UI verification suite", ansiFg = 2)
                val testIdx = (tickCount % 6).toInt() + 1
                lines += StyledLine("  [$testIdx/6] DebugShowcaseTest ... PASS", ansiFg = 2)
                lines += StyledLine("Tab tile shows breathing rainbow marquee.", dim = true)
            }
            PaneRole.DONE_MONITOR -> {
                lines += StyledLine("BENCHMARK COMPLETE · DONE", ansiFg = 2, bold = true)
                lines += StyledLine("✓ 60/60 frames under 4.2ms p95", ansiFg = 2)
                lines += StyledLine("Appears in Activity -> Finished filter.", dim = true)
            }
            PaneRole.ERROR_DEPLOY -> {
                lines += StyledLine("DEPLOY ERROR · OSC 9;4 state=error", ansiFg = 1, bold = true)
                lines += StyledLine("✗ Image push failed at 42%", ansiFg = 1)
                lines += StyledLine("Appears in Activity -> Attention filter.", dim = true)
            }
            PaneRole.INTERACTIVE_SHELL,
            PaneRole.USER_CREATED -> {
                if (pane.role == PaneRole.INTERACTIVE_SHELL) {
                    for (i in 1..40) {
                        val color = ((i - 1) % 6) + 1
                        lines +=
                            StyledLine(
                                "[log ${i.toString().padStart(2, '0')}] showcase scrollback line $i",
                                ansiFg = color,
                                dim = i % 2 == 0,
                                semanticPrompt = i % 10 == 1,
                            )
                    }
                    lines +=
                        StyledLine(
                            "Type 'help', 'status', 'ls', 'countdown', or 'clear':",
                            ansiFg = 6,
                            bold = true,
                            semanticPrompt = true,
                        )
                } else {
                    lines += StyledLine("New Showcase Pane (${pane.id})", ansiFg = 6, bold = true)
                    lines += StyledLine("Type commands or drag divider to resize.", dim = true)
                }
            }
            PaneRole.LAB_API -> {
                lines += StyledLine("STAGING LAB · API SERVER", ansiFg = 2, bold = true)
                lines += StyledLine("Listening on :8080 · 0 errors", ansiFg = 6)
            }
            PaneRole.LAB_DB -> {
                lines += StyledLine("STAGING LAB · POSTGRESQL", ansiFg = 4, bold = true)
                lines += StyledLine("Connections: 4 active · 12 idle", dim = true)
            }
            PaneRole.LAB_LOGS -> {
                lines += StyledLine("STAGING LAB · LIVE TAIL ($spinner)", ansiFg = 3, bold = true)
                lines += StyledLine("? Confirm rollout build #177 to staging", ansiFg = 3)
            }
        }

        pane.extraLines.forEach { text ->
            lines += StyledLine(text, semanticPrompt = text.startsWith("$ "))
        }

        if (
            pane.role == PaneRole.INTERACTIVE_SHELL ||
                pane.role == PaneRole.USER_CREATED ||
                pane.extraLines.isNotEmpty()
        ) {
            val prompt = "$ ${pane.inputLine}"
            cursorRowOffset = lines.size
            cursorCol = prompt.length.coerceAtMost((cols - 1).coerceAtLeast(0))
            lines += StyledLine(prompt, ansiFg = 2, semanticPrompt = true)
        } else {
            cursorRowOffset = (lines.size - 1).coerceAtLeast(0)
            cursorCol = 0
        }
        return lines to (cursorRowOffset to cursorCol)
    }

    private fun maxScrollOffset(pane: PaneState, viewportRows: Int): Int {
        val (buffer, _) = paneBufferLines(pane, columnsFor(pane.id))
        return (buffer.size - viewportRows).coerceAtLeast(0)
    }

    private fun renderPaneScreen(
        pane: PaneState,
        cols: Int,
        viewportRows: Int,
        isActive: Boolean,
    ): TerminalScreen {
        val safeCols = cols.coerceIn(16, 240)
        val safeRows = viewportRows.coerceIn(6, 120)
        val (buffer, cursorTarget) = paneBufferLines(pane, safeCols)
        val total = buffer.size.coerceAtLeast(safeRows)
        val available = (total - safeRows).coerceAtLeast(0)
        val clampedFromBottom = pane.scrollOffsetFromBottom.coerceIn(0, available)
        pane.scrollOffsetFromBottom = clampedFromBottom
        val offset = (available - clampedFromBottom).coerceIn(0, available)

        val cellLines = ArrayList<List<TerminalCell>>(safeRows)
        val allCells = ArrayList<TerminalCell>(safeRows * 24)
        for (row in 0 until safeRows) {
            val lineIndex = offset + row
            val styled = buffer.getOrNull(lineIndex)
            if (styled == null) {
                cellLines.add(emptyList())
                continue
            }
            var attr = 256 // default theme background
            attr =
                attr or
                    when (styled.ansiFg) {
                        null -> 128
                        else -> 512 or ((styled.ansiFg and 255) shl 11)
                    }
            if (styled.bold) attr = attr or 1
            if (styled.dim) attr = attr or 4
            if (styled.inverse) attr = attr or 8
            if (styled.underline) attr = attr or 64
            if (styled.semanticPrompt) attr = attr or (1 shl 30)

            val text = styled.text.take(safeCols)
            val rowCells = ArrayList<TerminalCell>(text.length)
            for (x in text.indices) {
                val ch = text[x].toString()
                val link =
                    if (styled.hyperlink != null && !text[x].isWhitespace()) styled.hyperlink
                    else null
                val cell =
                    TerminalCell(
                        x = x,
                        y = row,
                        text = ch,
                        foreground = palette.foreground,
                        background = palette.background,
                        attributes = attr,
                        width = 1,
                        hyperlink = link,
                    )
                rowCells.add(cell)
                allCells.add(cell)
            }
            cellLines.add(rowCells)
        }

        val cursorY =
            if (clampedFromBottom == 0)
                (cursorTarget.first - offset).takeIf { it in 0 until safeRows }
            else null
        val cursorX = if (cursorY != null) cursorTarget.second.coerceIn(0, safeCols - 1) else null
        val inputFlags = if (pane.role == PaneRole.INTERACTIVE_SHELL) 32 or 16 else 0

        return TerminalScreen(
            columns = safeCols,
            rows = safeRows,
            cursorX = cursorX,
            cursorY = cursorY,
            foreground = palette.foreground,
            background = palette.background,
            cells = allCells,
            scroll =
                TerminalScroll(
                    total = total.toLong(),
                    offset = offset.toLong(),
                    visible = safeRows.toLong(),
                ),
            lines = cellLines,
            inputFlags = inputFlags,
            cursorStyle = 1,
            cursorBlinking = isActive && controlled,
        )
    }

    private fun seedInitialWorkspace() {
        val pRunning =
            PaneState(
                id = "p-running",
                label = "Agent Runner",
                program = ProgramIdentity.resolve(listOf("codex")),
                role = PaneRole.RUNNING_AGENT,
                directory = "~/dev/rexling",
                baseTitle = "Refactoring layout engine",
            )
        val pCountdown =
            PaneState(
                id = "p-countdown",
                label = "Loop Countdown",
                program = ProgramIdentity.resolve(listOf("python")),
                role = PaneRole.LOOP_COUNTDOWN,
                directory = "~/dev/rexling/scripts",
                baseTitle = "Loop countdown",
            )
        val pEditor =
            PaneState(
                id = "p-editor",
                label = "Neovim",
                program = ProgramIdentity.resolve(listOf("nvim")),
                role = PaneRole.NEOVIM_EDITOR,
                directory = "~/dev/rexling/src",
                baseTitle = "MainActivity.kt",
            )
        val pFloating =
            PaneState(
                id = "p-floating",
                label = "Claude Review",
                program = ProgramIdentity.resolve(listOf("claude")),
                role = PaneRole.FLOATING_REVIEW,
                directory = "~/dev/rexling",
                baseTitle = "Approve test run",
            )
        val tabAgents =
            TabState(
                id = "w-agents",
                label = "Agents",
                blockIds = mutableListOf("p-running", "p-countdown", "p-editor", "p-floating"),
                layout =
                    mutableListOf(
                        PanePlacement("p-running", 0f, 0f, 0.5f, 1f),
                        PanePlacement("p-countdown", 0.5f, 0f, 0.5f, 0.5f),
                        PanePlacement("p-editor", 0.5f, 0.5f, 0.5f, 0.5f),
                        PanePlacement(
                            id = "p-floating",
                            x = 0.42f,
                            y = 0.16f,
                            width = 0.52f,
                            height = 0.62f,
                            layer = 1,
                            floating = true,
                            layerId = "float-1",
                            layerBounds = PaneBounds(0.42f, 0.16f, 0.52f, 0.62f),
                            minimized = false,
                        ),
                    ),
                splits =
                    mutableListOf(
                        PaneSplitLine(
                            id = "split-agents-main",
                            horizontal = true,
                            ratio = 0.5f,
                            x = 0f,
                            y = 0f,
                            width = 1f,
                            height = 1f,
                            before = "p-running",
                            after = "p-countdown",
                        ),
                        PaneSplitLine(
                            id = "split-agents-right",
                            horizontal = false,
                            ratio = 0.5f,
                            x = 0.5f,
                            y = 0f,
                            width = 0.5f,
                            height = 1f,
                            before = "p-countdown",
                            after = "p-editor",
                        ),
                    ),
            )

        val pLoopDial =
            PaneState(
                id = "p-loop-dial",
                label = "Loop Timer",
                program = ProgramIdentity.resolve(listOf("python")),
                role = PaneRole.LOOP_DIAL,
                directory = "~/dev/rexling/timers",
                baseTitle = "Loop countdown",
                countdownOffset = 15,
            )
        val pIndeterminate =
            PaneState(
                id = "p-indeterminate",
                label = "Dev Server",
                program = ProgramIdentity.resolve(listOf("node")),
                role = PaneRole.INDETERMINATE_SERVER,
                directory = "~/dev/rexling/web",
                baseTitle = "Building bundle",
            )
        val pPausedSync =
            PaneState(
                id = "p-paused-sync",
                label = "Paused Sync",
                program = ProgramIdentity.resolve(listOf("git")),
                role = PaneRole.PAUSED_SYNC,
                directory = "~/dev/rexling",
                baseTitle = "Sync paused",
            )
        val tabCountdown =
            TabState(
                id = "w-countdown",
                label = "Countdown",
                blockIds = mutableListOf("p-loop-dial", "p-indeterminate", "p-paused-sync"),
                layout =
                    mutableListOf(
                        PanePlacement("p-loop-dial", 0f, 0f, 0.5f, 1f),
                        PanePlacement("p-indeterminate", 0.5f, 0f, 0.5f, 0.5f),
                        PanePlacement("p-paused-sync", 0.5f, 0.5f, 0.5f, 0.5f),
                    ),
                splits =
                    mutableListOf(
                        PaneSplitLine(
                            id = "split-cd-main",
                            horizontal = true,
                            ratio = 0.5f,
                            x = 0f,
                            y = 0f,
                            width = 1f,
                            height = 1f,
                            before = "p-loop-dial",
                            after = "p-indeterminate",
                        ),
                        PaneSplitLine(
                            id = "split-cd-right",
                            horizontal = false,
                            ratio = 0.5f,
                            x = 0.5f,
                            y = 0f,
                            width = 0.5f,
                            height = 1f,
                            before = "p-indeterminate",
                            after = "p-paused-sync",
                        ),
                    ),
            )

        val pBuild =
            PaneState(
                id = "p-build",
                label = "Claude Code",
                program = ProgramIdentity.resolve(listOf("claude")),
                role = PaneRole.RUNNING_TESTS,
                directory = "~/dev/rexling",
                baseTitle = "Running tests",
            )
        val pDone =
            PaneState(
                id = "p-done",
                label = "Monitor",
                program = ProgramIdentity.resolve(listOf("htop")),
                role = PaneRole.DONE_MONITOR,
                directory = "~/dev/rexling",
                baseTitle = "Benchmark complete",
            )
        val pError =
            PaneState(
                id = "p-error",
                label = "Deploy",
                program = ProgramIdentity.resolve(listOf("docker")),
                role = PaneRole.ERROR_DEPLOY,
                directory = "~/dev/rexling/infra",
                baseTitle = "Image push failed",
            )
        val tabRunning =
            TabState(
                id = "w-work",
                label = "Running",
                blockIds = mutableListOf("p-build", "p-done", "p-error"),
                layout =
                    mutableListOf(
                        PanePlacement("p-build", 0f, 0f, 0.5f, 1f),
                        PanePlacement("p-done", 0.5f, 0f, 0.5f, 0.5f),
                        PanePlacement("p-error", 0.5f, 0.5f, 0.5f, 0.5f),
                    ),
                splits =
                    mutableListOf(
                        PaneSplitLine(
                            id = "split-work-main",
                            horizontal = true,
                            ratio = 0.5f,
                            x = 0f,
                            y = 0f,
                            width = 1f,
                            height = 1f,
                            before = "p-build",
                            after = "p-done",
                        ),
                        PaneSplitLine(
                            id = "split-work-right",
                            horizontal = false,
                            ratio = 0.5f,
                            x = 0.5f,
                            y = 0f,
                            width = 0.5f,
                            height = 1f,
                            before = "p-done",
                            after = "p-error",
                        ),
                    ),
            )

        val pShell =
            PaneState(
                id = "p-shell",
                label = "zsh",
                program = ProgramIdentity.resolve(listOf("zsh")),
                role = PaneRole.INTERACTIVE_SHELL,
                directory = "~/dev/rexling",
                baseTitle = "zsh",
            )
        val tabShell =
            TabState(
                id = "w-shell",
                label = "Shell",
                blockIds = mutableListOf("p-shell"),
                layout = mutableListOf(PanePlacement("p-shell", 0f, 0f, 1f, 1f)),
                splits = mutableListOf(),
            )

        val showcasePanes =
            linkedMapOf(
                pRunning.id to pRunning,
                pCountdown.id to pCountdown,
                pEditor.id to pEditor,
                pFloating.id to pFloating,
                pLoopDial.id to pLoopDial,
                pIndeterminate.id to pIndeterminate,
                pPausedSync.id to pPausedSync,
                pBuild.id to pBuild,
                pDone.id to pDone,
                pError.id to pError,
                pShell.id to pShell,
            )

        val pLabApi =
            PaneState(
                id = "p-lab-api",
                label = "API Server",
                program = ProgramIdentity.resolve(listOf("node")),
                role = PaneRole.LAB_API,
                directory = "/srv/api",
                baseTitle = "API Server",
            )
        val pLabDb =
            PaneState(
                id = "p-lab-db",
                label = "PostgreSQL",
                program = ProgramIdentity.resolve(listOf("postgres")),
                role = PaneRole.LAB_DB,
                directory = "/srv/db",
                baseTitle = "PostgreSQL",
            )
        val tabLabApi =
            TabState(
                id = "w-lab-api",
                label = "API",
                blockIds = mutableListOf("p-lab-api", "p-lab-db"),
                layout =
                    mutableListOf(
                        PanePlacement("p-lab-api", 0f, 0f, 0.5f, 1f),
                        PanePlacement("p-lab-db", 0.5f, 0f, 0.5f, 1f),
                    ),
                splits =
                    mutableListOf(
                        PaneSplitLine(
                            id = "split-lab-api",
                            horizontal = true,
                            ratio = 0.5f,
                            x = 0f,
                            y = 0f,
                            width = 1f,
                            height = 1f,
                            before = "p-lab-api",
                            after = "p-lab-db",
                        ),
                    ),
            )

        val pLabLogs =
            PaneState(
                id = "p-lab-logs",
                label = "Tail Logs",
                program = ProgramIdentity.resolve(listOf("ssh")),
                role = PaneRole.LAB_LOGS,
                directory = "/var/log/rex",
                baseTitle = "Tail Logs",
            )
        val tabLabLogs =
            TabState(
                id = "w-lab-logs",
                label = "Logs",
                blockIds = mutableListOf("p-lab-logs"),
                layout = mutableListOf(PanePlacement("p-lab-logs", 0f, 0f, 1f, 1f)),
                splits = mutableListOf(),
            )

        val labPanes =
            linkedMapOf(pLabApi.id to pLabApi, pLabDb.id to pLabDb, pLabLogs.id to pLabLogs)

        sessions.clear()
        sessions +=
            SessionState(
                id = "demo-workspace",
                label = "Showcase",
                tabs = mutableListOf(tabAgents, tabCountdown, tabRunning, tabShell),
                panes = showcasePanes,
            )
        sessions +=
            SessionState(
                id = "demo-lab",
                label = "Staging Lab",
                tabs = mutableListOf(tabLabApi, tabLabLogs),
                panes = labPanes,
            )
        selectedSessionId = "demo-workspace"
        selectedBlockId = "p-running"
    }

    private fun parseThemePalette(args: JSONObject, fallback: TerminalPalette): TerminalPalette {
        val dark = args.optString("scheme", "dark") != "light"
        val bg = parseHexColor(args.optString("background"), fallback.background)
        val fg = parseHexColor(args.optString("foreground"), fallback.foreground)
        val cursor = parseHexColor(args.optString("cursor"), fallback.accent)
        val ansiList = fallback.ansi.toMutableList()
        val array = args.optJSONArray("palette")
        if (array != null) {
            for (i in 0 until array.length()) {
                val entry = array.optString(i)
                val eq = entry.indexOf('=')
                if (eq > 0) {
                    val idx = entry.substring(0, eq).toIntOrNull()
                    if (idx != null && idx in ansiList.indices) {
                        ansiList[idx] = parseHexColor(entry.substring(eq + 1), ansiList[idx])
                    }
                }
            }
        }
        return fallback.copy(
            dark = dark,
            background = bg,
            foreground = fg,
            accent = cursor,
            ansi = ansiList,
        )
    }

    private fun parseHexColor(value: String, fallback: Int): Int {
        val hex = value.removePrefix("#")
        val rgb = hex.toLongOrNull(16) ?: return fallback
        return (0xff000000 or (rgb and 0xffffff)).toInt()
    }

    private fun formatElapsed(ticks: Long): String {
        val totalTenths = (abs(ticks) * tickIntervalMs) / 100
        val seconds = totalTenths / 10
        val tenths = totalTenths % 10
        return "${seconds}.${tenths}s"
    }

    companion object {
        private val SPINNER_FRAMES = listOf("⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏")
    }
}

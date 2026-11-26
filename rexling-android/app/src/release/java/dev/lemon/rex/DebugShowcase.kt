package dev.lemon.rex

import kotlinx.coroutines.CoroutineScope

/** Release no-op stub so showcase code is excluded from non-debug compilation. */
internal object DebugShowcase {
    const val available = false

    fun profile(): ServerProfile? = null

    fun isProfile(id: String): Boolean = false

    fun createSession(
        scope: CoroutineScope,
        initialPalette: TerminalPalette,
        onState: (AppState) -> Unit,
        onPaneFrames: (Map<String, TerminalScreen>) -> Unit,
    ): ShowcaseSession? = null
}

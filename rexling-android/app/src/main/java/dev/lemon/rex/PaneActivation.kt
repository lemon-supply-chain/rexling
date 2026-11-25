package dev.lemon.rex

/** Claim once per navigation; never fight ownership changes from another client. */
internal class PaneActivation {
    private var generation = 0L
    private var attempted = ""
    private var viewing = false

    fun navigate() {
        generation++
        viewing = false
    }

    fun resume() {
        generation++
    }

    fun release() {
        viewing = true
    }

    fun shouldTake(
        state: AppState,
        request: NavigationRequest,
        active: Boolean,
        size: Pair<Int, Int>,
    ): Boolean {
        if (
            !active ||
                viewing ||
                !state.connected ||
                state.creating ||
                request.pending ||
                state.selectedSession.isBlank() ||
                state.selectedBlock.isBlank()
        )
            return false
        val key = "$generation:${request.id}:${state.selectedSession}:${state.selectedBlock}"
        if (state.controlled) {
            // A retained claim already activated this visit. Respect a later takeover.
            attempted = key
            return false
        }
        if (state.screen == null || state.error.isNotBlank() || size.first <= 0 || size.second <= 0)
            return false
        if (key == attempted) return false
        attempted = key
        return true
    }
}

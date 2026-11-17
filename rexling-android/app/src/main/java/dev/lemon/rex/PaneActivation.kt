package dev.lemon.rex

/** Claim once per navigation; never fight ownership changes from another client. */
internal class PaneActivation {
    private var generation=0L
    private var attempted=""
    private var viewing=false
    fun navigate() {generation++;viewing=false}
    fun resume() {generation++}
    fun release() {viewing=true}
    fun shouldTake(state: AppState,request: NavigationRequest,active: Boolean,size: Pair<Int,Int>): Boolean {
        if(!active || viewing || state.controlled || !state.connected || state.creating || request.pending || state.screen==null ||
            state.selectedSession.isBlank() || state.selectedBlock.isBlank() || state.error.isNotBlank() || size.first<=0 || size.second<=0) return false
        val key="$generation:${request.id}:${state.selectedSession}:${state.selectedBlock}"
        if(key==attempted) return false
        attempted=key
        return true
    }
}

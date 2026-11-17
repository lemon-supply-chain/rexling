package dev.lemon.rex

import android.content.SharedPreferences
import androidx.core.content.edit

/** Endpoint scope keeps similarly named sessions on different servers separate. */
internal class SavedWorkspacePresentation(private val preferences: SharedPreferences,endpoint: String) {
    private val key="workspace_presentation:$endpoint"
    private val cache=WorkspacePresentation.decode(preferences.getString(key,null))
    fun chrome(state: AppState): AppState {
        if(cache.observe(state)) flush()
        return cache.present(state)
    }
    fun flush() {
        if(!cache.dirty) return
        preferences.edit {putString(key,cache.encode())}
        cache.markSaved()
    }
}

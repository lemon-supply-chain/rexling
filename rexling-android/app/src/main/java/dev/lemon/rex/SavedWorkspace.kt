package dev.lemon.rex

import android.content.SharedPreferences
import androidx.core.content.edit

/** Each connection owns its history; identical IDs on different servers never mix. */
internal class SavedWorkspace(private val preferences: SharedPreferences, endpoint: String) {
    private val key = "workspace_navigation:$endpoint"
    private val sessionKey = "current_session:$endpoint"
    val navigation = WorkspaceNavigation.decode(preferences.getString(key, null))
    var session = preferences.getString(sessionKey, "").orEmpty()
        private set

    fun selectSession(id: String) {
        if (id.isBlank() || session == id) return
        session = id
        preferences.edit { putString(sessionKey, id) }
    }

    fun remember(session: String, windows: List<WindowItem>, block: String) {
        if (navigation.remember(session, windows, block))
            preferences.edit { putString(key, navigation.encode()) }
        selectSession(session)
    }

    fun request(session: String, block: String) {
        if (navigation.request(session, block))
            preferences.edit { putString(key, navigation.encode()) }
        selectSession(session)
    }

    fun forgetSession(id: String) {
        val removed = navigation.forget(id)
        val current = session == id
        if (current) session = ""
        if (removed || current)
            preferences.edit {
                if (removed) putString(key, navigation.encode())
                if (current) remove(sessionKey)
            }
    }

    fun target(state: AppState) = navigation.target(state, session)
}

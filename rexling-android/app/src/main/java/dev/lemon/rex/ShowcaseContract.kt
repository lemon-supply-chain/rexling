package dev.lemon.rex

import org.json.JSONObject

/**
 * Hook implemented only in the debug source set so showcase code is never compiled into release.
 */
internal interface ShowcaseSession {
    fun start(active: Boolean)

    fun command(type: String, request: JSONObject): Boolean

    fun close()
}

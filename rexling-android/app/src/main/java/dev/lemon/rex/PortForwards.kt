package dev.lemon.rex

import android.content.*
import android.net.Uri
import androidx.compose.runtime.*
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

internal data class PortForward(
    val id: String,
    val server: String,
    val host: String,
    val port: Int,
    val phase: String = "connecting",
    val address: String = "",
    val error: String = "",
    val serverId: String = "",
    val endpoint: String = "",
    val session: String = "",
    val localPort: Int = 0,
    val generation: Long = 0,
    val scheme: String = "http",
) {
    val url
        get() = "$scheme://$address"

    val target
        get() = if (':' in host) "[$host]:$port" else "$host:$port"

    val running
        get() = phase in listOf("connecting", "active", "reconnecting")

    val boundPort
        get() = address.substringAfterLast(':').toIntOrNull() ?: localPort
}

internal data class ForwardServer(
    val profile: ServerProfile,
    val session: String,
    val connected: Boolean,
)

internal data class ForwardConflict(val id: String, val owner: String?)

internal data class ForwardLinkNotice(
    val id: String,
    val title: String,
    val url: String = "",
    val error: String = "",
    val autoOpen: Boolean = false,
)

internal object PortForwards {
    val items = mutableStateListOf<PortForward>()
    var notice by mutableStateOf<String?>(null)
    var conflict by mutableStateOf<ForwardConflict?>(null)
    var linkNotice by mutableStateOf<ForwardLinkNotice?>(null)
    private val pendingLinks = mutableMapOf<String, LoopbackLink>()
    private lateinit var app: Context
    private val preferences
        get() = app.getSharedPreferences("forwards", Context.MODE_PRIVATE)

    var policy by mutableStateOf(PortConflictPolicy.ASK)
        private set

    fun initialize(context: Context) {
        app = context.applicationContext
        policy = PortConflictPolicy.decode(preferences.getString("conflict_policy", null))
        runCatching {
            val saved = JSONArray(preferences.getString("saved", "[]"))
            repeat(minOf(saved.length(), 64)) { index ->
                runCatching {
                    val v = saved.getJSONObject(index)
                    val item =
                        PortForward(
                            v.getString("id"),
                            v.getString("server"),
                            v.getString("host"),
                            v.getInt("port"),
                            "stopped",
                            serverId = v.getString("server_id"),
                            endpoint = ServerProfile.normalizeEndpoint(v.getString("endpoint")),
                            session = v.getString("session"),
                            localPort = v.optInt("local_port"),
                            scheme =
                                v.optString("scheme", "http").takeIf {
                                    it in listOf("http", "https")
                                } ?: "http",
                        )
                    require(
                        item.port in 1..65535 &&
                            item.localPort in 0..65535 &&
                            item.host.isNotBlank(),
                    )
                    if (items.none { it.id == item.id }) items.add(item)
                }
            }
        }
    }

    fun changePolicy(value: PortConflictPolicy) {
        policy = value
        preferences.edit().putString("conflict_policy", value.name).apply()
    }

    fun persist() {
        preferences
            .edit()
            .putString(
                "saved",
                JSONArray(
                        items.map { v ->
                            JSONObject()
                                .put("id", v.id)
                                .put("server", v.server)
                                .put("server_id", v.serverId)
                                .put("endpoint", v.endpoint)
                                .put("session", v.session)
                                .put("host", v.host)
                                .put("port", v.port)
                                .put("local_port", v.localPort)
                                .put("scheme", v.scheme)
                        },
                    )
                    .toString(),
            )
            .apply()
    }

    fun update(value: PortForward) {
        val at = items.indexOfFirst { it.id == value.id }
        if (at >= 0) items[at] = value
    }

    fun start(
        context: Context,
        server: ServerProfile,
        session: String,
        host: String,
        port: Int,
        localPort: Int,
    ): String {
        val existing = items.firstOrNull {
            it.endpoint == server.endpoint && it.host == host && it.port == port
        }
        if (existing?.running == true) return existing.id
        val item =
            PortForward(
                existing?.id ?: UUID.randomUUID().toString(),
                server.label,
                host,
                port,
                "stopped",
                serverId = server.id,
                endpoint = server.endpoint,
                session = session,
                localPort = localPort,
                generation = existing?.generation ?: 0,
                scheme = existing?.scheme ?: "http",
            )
        if (existing == null) {
            if (items.size >= 64) {
                notice = "Remove a saved forward before adding another."
                return ""
            }
            items.add(item)
        } else update(item)
        persist()
        requestStart(context, item.id)
        return item.id
    }

    fun openLink(context: Context, server: ServerProfile, session: String, link: LoopbackLink) {
        val existing = items.firstOrNull {
            it.endpoint == server.endpoint && it.host == link.host && it.port == link.port
        }
        val id = existing?.id ?: UUID.randomUUID().toString()
        if (existing == null) {
            if (items.size >= 64) {
                notice = "Remove a saved forward before adding another."
                linkNotice = ForwardLinkNotice("limit", "Port forwarding", error = notice.orEmpty())
                return
            }
            items.add(
                PortForward(
                    id,
                    server.label,
                    link.host,
                    link.port,
                    "stopped",
                    serverId = server.id,
                    endpoint = server.endpoint,
                    session = session,
                    localPort = link.port,
                    scheme = java.net.URI(link.original).scheme.lowercase(),
                ),
            )
            persist()
        } else {
            update(
                existing.copy(
                    session = if (existing.running) existing.session else session,
                    scheme = java.net.URI(link.original).scheme.lowercase(),
                ),
            )
            persist()
        }
        pendingLinks[id] = link
        linkNotice = ForwardLinkNotice(id, "${server.label} · ${link.port}")
        if (existing?.phase == "active") ready(existing)
        else if (existing?.running != true) requestStart(context, id)
    }

    fun requestStart(
        context: Context,
        id: String,
        port: Int? = null,
        bypassConflict: Boolean = false,
    ) {
        val saved = items.firstOrNull { it.id == id } ?: return
        if (saved.running) return
        val item = saved.copy(localPort = port ?: saved.localPort)
        update(item)
        val owner = items.firstOrNull {
            it.id != id && it.running && item.localPort > 0 && it.boundPort == item.localPort
        }
        if (owner != null && !bypassConflict) {
            when (policy) {
                PortConflictPolicy.ASK -> {
                    conflict = ForwardConflict(id, owner.id)
                    return
                }
                PortConflictPolicy.AUTO -> {
                    requestStart(context, id, 0, true)
                    return
                }
                PortConflictPolicy.REPLACE -> {
                    stop(context, owner.id)
                    requestStart(context, id, item.localPort, true)
                    return
                }
            }
        }
        if (items.count { it.running } >= 8) {
            notice = "Stop a forward before starting another (maximum 8)."
            val failure = saved.copy(phase = "failed", error = notice.orEmpty())
            update(failure)
            failed(failure)
            return
        }
        update(
            item.copy(
                phase = "connecting",
                address = "",
                error = "",
                generation = item.generation + 1,
            ),
        )
        persist()
        try {
            context.startForegroundService(
                Intent(context, PortForwardService::class.java)
                    .setAction(PortForwardService.ADD)
                    .putExtra("id", id),
            )
        } catch (e: Exception) {
            val failure =
                item.copy(
                    phase = "failed",
                    error = e.message ?: "Could not start forward",
                    generation = item.generation + 1,
                )
            update(failure)
            failed(failure)
            notice = e.message
        }
    }

    fun portBusy(context: Context, id: String) {
        val item = items.firstOrNull { it.id == id } ?: return
        update(
            item.copy(
                phase = "stopped",
                address = "",
                error = "Phone port ${item.localPort} is in use.",
            ),
        )
        val owner = items.firstOrNull {
            it.id != id && it.running && it.boundPort == item.localPort
        }
        if (policy == PortConflictPolicy.AUTO) requestStart(context, id, 0, true)
        else if (policy == PortConflictPolicy.REPLACE && owner != null) {
            stop(context, owner.id)
            requestStart(context, id, bypassConflict = true)
        } else conflict = ForwardConflict(id, owner?.id)
    }

    fun dismissLinkNotice() {
        linkNotice?.let { pendingLinks.remove(it.id) }
        linkNotice = null
    }

    fun cancelConflict() {
        conflict?.let {
            pendingLinks.remove(it.id)
            if (linkNotice?.id == it.id) linkNotice = null
        }
        conflict = null
    }

    fun resolve(context: Context, replace: Boolean) {
        val choice = conflict ?: return
        conflict = null
        if (replace && choice.owner != null) {
            stop(context, choice.owner)
            requestStart(context, choice.id, bypassConflict = true)
        } else requestStart(context, choice.id, 0, true)
    }

    fun markStopped(id: String): PortForward? {
        val current = items.firstOrNull { it.id == id }
        pendingLinks.remove(id)
        current?.let {
            update(it.copy(phase = "stopped", error = "", generation = it.generation + 1))
        }
        if (linkNotice?.id == id) linkNotice = null
        persist()
        return current
    }

    fun stop(context: Context, id: String) {
        val current = markStopped(id)
        context.startService(
            Intent(context, PortForwardService::class.java)
                .setAction(PortForwardService.CLOSE)
                .putExtra("id", id)
                .putExtra("generation", current?.generation ?: Long.MAX_VALUE),
        )
    }

    fun remove(context: Context, id: String) {
        stop(context, id)
        items.removeAll { it.id == id }
        persist()
    }

    fun forgetServer(context: Context, serverId: String) {
        items.filter { it.serverId == serverId }.map { it.id }.forEach { remove(context, it) }
    }

    fun ready(item: PortForward) {
        val link = pendingLinks.remove(item.id) ?: return
        linkNotice =
            ForwardLinkNotice(
                item.id,
                "${item.server} · ${item.port} → ${item.boundPort}",
                link.forwarded(item.address),
                autoOpen = true,
            )
    }

    fun failed(item: PortForward) {
        if (pendingLinks.remove(item.id) != null)
            linkNotice = ForwardLinkNotice(item.id, item.server, error = item.error)
    }

    fun openBrowser(context: Context, url: String): Boolean = runCatching {
        require(TerminalLinks.webUrl(url) != null)
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addCategory(Intent.CATEGORY_BROWSABLE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        true
    }
        .getOrElse {
            notice = "No browser is available to open this link."
            android.widget.Toast.makeText(context, notice, android.widget.Toast.LENGTH_SHORT).show()
            false
        }
}

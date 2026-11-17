package dev.lemon.rex

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.URI

internal data class ForwardTarget(val host: String, val explicitPort: Int?, val defaultPort: Int?)

internal data class PortForwardChoice(val port: Int, val address: String, val label: String)

internal data class PortForwardState(
    val handle: Long,
    val key: String,
    val revision: Long,
    val phase: String,
    val outcome: String,
    val reason: String,
    val failure: String,
    val error: String,
    val authority: String,
    val portMode: String,
    val targetHost: String,
    val targetPort: Int,
    val localPort: Int,
    val localUrl: String,
    val tunnelBlock: String,
    val connections: Int,
    val choices: List<PortForwardChoice>
) {
    val active: Boolean get()=phase=="active"
    val awaitingSelection: Boolean get()=phase=="awaiting_selection"
    val terminal: Boolean get()=phase=="closed" || phase=="failed"

    companion object {
        fun idle(handle: Long,key: String)=PortForwardState(handle,key,0,"new","","","","","unknown","disabled","",0,0,"","",0,emptyList())
        fun failed(handle: Long,key: String,message: String)=idle(handle,key).copy(phase="failed",outcome="failed",failure="internal",error=message)
        fun parse(handle: Long,key: String,text: String): PortForwardState? = runCatching {
            val value=JSONObject(text)
            PortForwardState(
                handle,key,value.optLong("revision"),value.optString("phase"),
                if(value.isNull("outcome")) "" else value.optString("outcome"),
                value.optString("reason"),value.optString("failure"),value.optString("error"),
                value.optString("authority"),value.optString("portMode"),
                value.optString("targetHost"),value.optInt("targetPort"),
                value.optInt("localPort"),value.optString("localUrl"),value.optString("tunnelBlock"),
                value.optInt("connections"),
                value.optJSONArray("choices").let {choices->
                    if(choices==null) emptyList() else (0 until choices.length()).mapNotNull {index->
                        val choice=choices.optJSONObject(index) ?: return@mapNotNull null
                        PortForwardChoice(choice.optInt("port"),choice.optString("address"),choice.optString("label"))
                    }
                }
            )
        }.getOrNull()
    }
}

internal data class PortForwardRequest(
    val endpoint: String,
    val session: String,
    val block: String,
    val host: String?,
    val port: Int?,
    val defaultPort: Int?,
    val url: String
) {
    val key: String get()=listOf(endpoint,session,block,host.orEmpty(),port?.toString() ?: "*").joinToString("|")
    fun json(tag: String = ""): String = JSONObject()
        .put("endpoint",endpoint).put("session",session).put("sourceBlock",block)
        .apply {
            host?.let {put("targetHost",it)}
            port?.let {put("targetPort",it)}
            defaultPort?.let {put("defaultPort",it)}
            if(tag.isNotBlank()) put("labelTag",tag)
        }
        .toString()
}

internal data class PortSelection(val state: PortForwardState,val url: String)

internal object PortForwardLinks {
    private val loopbackV4=Regex("""^127(\.\d{1,3}){3}$""")
    fun target(url: String,endpoint: String): ForwardTarget? {
        val uri=runCatching {URI(url)}.getOrNull() ?: return null
        if(!uri.scheme.equals("http",true)) return null
        val host=uri.host?.removeSurrounding("[","]")?.lowercase() ?: return null
        if(!forwardable(host,endpoint)) return null
        val port=uri.port.takeIf {it in 1..65535}
        return ForwardTarget(host,port,if(port==null) 80 else null)
    }
    fun forwardable(host: String,endpoint: String): Boolean {
        val target=host.removeSurrounding("[","]").lowercase()
        if(target=="localhost" || target=="::1" || loopbackV4.matches(target)) return true
        val server=runCatching {URI(endpoint).host}.getOrNull()?.removeSurrounding("[","]")?.lowercase()
        return !server.isNullOrBlank() && target==server
    }
    /** Only the origin changes; path, query, and fragment stay byte-for-byte. */
    fun rewrite(url: String,localPort: Int): String? = runCatching {
        val uri=URI(url)
        buildString {
            append("http://127.0.0.1:").append(localPort)
            append(uri.rawPath?.ifEmpty {"/"} ?: "/")
            uri.rawQuery?.let {append('?').append(it)}
            uri.rawFragment?.let {append('#').append(it)}
        }
    }.getOrNull()
    fun open(context: Context,url: String): Boolean = runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(url))
            .addCategory(Intent.CATEGORY_BROWSABLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    }.getOrDefault(false)
}

/**
 * Owns the native forward handles for the app process.
 *
 * The foreground service keeps the process alive while Chrome is in front; the
 * registry polls the native state machine and publishes it to Compose.
 */
internal object PortForwardRegistry {
    private class Entry(val handle: Long,val request: PortForwardRequest) {
        val state=MutableStateFlow(PortForwardState.idle(handle,request.key))
        var revision=-1L
    }
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
    private val entries=LinkedHashMap<Long,Entry>()
    private var poller: Job?=null
    private var appContext: Context?=null
    private var installTag: String?=null
    private val mutableStates=MutableStateFlow<List<PortForwardState>>(emptyList())
    val states: StateFlow<List<PortForwardState>> = mutableStates.asStateFlow()
    private val mutableSelection=MutableStateFlow<PortSelection?>(null)
    val selection: StateFlow<PortSelection?> = mutableSelection.asStateFlow()

    fun launch(context: Context,request: PortForwardRequest) {
        appContext=context.applicationContext
        // Start the keep-alive service in the tap handler, while the activity is
        // still in the foreground, rather than after a coroutine dispatch.
        PortForwardService.start(context)
        scope.launch {
            val state=ensure(context.applicationContext,request)
            when {
                state.active -> open(state,request)
                state.awaitingSelection -> Unit
                state.failure.isNotBlank() -> notice(message(state))
                else -> Unit
            }
        }
    }
    private suspend fun ensure(context: Context,request: PortForwardRequest): PortForwardState {
        val current=synchronized(entries) {find(request) ?: create(context,request)}
        val state=withTimeoutOrNull(15_000) {
            current.state.first {it.active || it.terminal || it.awaitingSelection}
        } ?: current.state.value
        if(!state.terminal || state.failure.isNotBlank()) return state
        synchronized(entries) {if(entries[current.handle]===current) entries.remove(current.handle)}
        NativeBridge.forwardDestroy(current.handle)
        val fresh=synchronized(entries) {find(request) ?: create(context,request)}
        return withTimeoutOrNull(15_000) {
            fresh.state.first {it.active || it.terminal || it.awaitingSelection}
        } ?: fresh.state.value
    }
    private fun find(request: PortForwardRequest): Entry? = entries.values.firstOrNull {entry->
        !entry.state.value.terminal && (entry.request.key==request.key ||
            (request.port!=null && entry.request.endpoint==request.endpoint &&
                entry.request.session==request.session && entry.request.block==request.block &&
                entry.state.value.targetPort==request.port))
    }
    private fun create(context: Context,request: PortForwardRequest): Entry {
        val handle=runCatching {NativeBridge.forwardCreate(request.json(tag(context)))}.getOrDefault(0L)
        val entry=Entry(handle,request)
        if(handle<=0L) {
            entry.state.value=PortForwardState.failed(0L,request.key,"Could not start port forwarding")
        } else {
            entries[handle]=entry
        }
        ensurePoller()
        return entry
    }
    private fun ensurePoller() {
        if(poller?.isActive==true) return
        poller=scope.launch {
            while(isActive) {
                val snapshot=synchronized(entries) {entries.values.toList()}
                for(entry in snapshot) {
                    if(entry.handle<=0L) continue
                    val state=PortForwardState.parse(entry.handle,entry.request.key,NativeBridge.forwardPoll(entry.handle)) ?: continue
                    if(state.revision==entry.revision) continue
                    entry.revision=state.revision
                    entry.state.value=state
                    if(state.awaitingSelection) {
                        mutableSelection.value=PortSelection(state,entry.request.url)
                    }
                    if(state.terminal) {
                        NativeBridge.forwardDestroy(entry.handle)
                        synchronized(entries) {if(entries[entry.handle]===entry) entries.remove(entry.handle)}
                        if(mutableSelection.value?.state?.handle==entry.handle) mutableSelection.value=null
                        val message=message(state)
                        if(state.failure.isNotBlank()) notice(message)
                    }
                }
                val remaining=synchronized(entries) {entries.values.map {it.state.value}}
                mutableStates.value=remaining
                if(remaining.isEmpty()) {
                    appContext?.let(PortForwardService::stop)
                    break
                }
                delay(150)
            }
        }
    }
    fun select(selection: PortSelection,port: Int) {
        mutableSelection.value=null
        val entry=synchronized(entries) {entries[selection.state.handle]} ?: return
        NativeBridge.forwardSelect(entry.handle,port.toLong())
        scope.launch {
            val state=withTimeoutOrNull(30_000) {entry.state.first {it.active || it.terminal}} ?: entry.state.value
            if(state.active) open(state,entry.request) else notice(message(state))
        }
    }
    fun cancelSelection(selection: PortSelection) {
        mutableSelection.value=null
        synchronized(entries) {entries[selection.state.handle]}?.let {entry->
            NativeBridge.forwardClose(entry.handle)
        }
    }
    fun close(handle: Long) {
        synchronized(entries) {entries[handle]}?.let {NativeBridge.forwardClose(it.handle)}
    }
    fun closeAll() {
        NativeBridge.forwardCloseAll()
        synchronized(entries) {entries.clear()}
        mutableStates.value=emptyList()
        mutableSelection.value=null
        poller?.cancel();poller=null
        appContext?.let(PortForwardService::stop)
    }
    private fun tag(context: Context): String {
        installTag?.let {return it}
        val preferences=context.getSharedPreferences("rex",0)
        val existing=preferences.getString("forward_install_id",null)?.takeIf {it.isNotBlank()}
        val tag=existing ?: java.util.UUID.randomUUID().toString().take(8).also {
            preferences.edit().putString("forward_install_id",it).apply()
        }
        installTag=tag
        return tag
    }
    private fun open(state: PortForwardState,request: PortForwardRequest) {
        val context=appContext
        val url=PortForwardLinks.rewrite(request.url,state.localPort)
        if(context==null || url==null || !PortForwardLinks.open(context,url)) {
            notice("Could not open the forwarded page")
        }
    }
    private fun message(state: PortForwardState): String = when(state.failure) {
        "unsupported_server"->"This Rex server is too old for port forwarding"
        "not_listening"->"Nothing is listening on that port in this terminal"
        "needs_port"->"Open a link with an explicit port; Rex could not list every port"
        "connect_failed"->"Could not reach the Rex server for port forwarding"
        "tunnel_failed"->"Rex could not create the tunnel for this port"
        "bind_failed"->"Could not open a local port on this device"
        "" -> state.error
        else -> state.error.ifBlank {"Port forwarding ${state.outcome.ifBlank {"failed"}}"}
    }
    private fun notice(text: String) {
        if(text.isBlank()) return
        val context=appContext ?: return
        scope.launch(Dispatchers.Main) {Toast.makeText(context,text,Toast.LENGTH_LONG).show()}
    }
}

package dev.lemon.rex

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateListOf
import kotlinx.coroutines.*
import org.json.JSONObject
import java.util.UUID

/** Owns the tunnel handles independently of the terminal view while the browser is open. */
class PortForwardService: Service() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private data class Worker(val handle: Long,val job: Job,val generation: Long)
    private val workers=mutableMapOf<String,Worker>()
    private val notifications by lazy {getSystemService(NotificationManager::class.java)}
    override fun onCreate() {
        super.onCreate()
        notifications.createNotificationChannel(NotificationChannel(CHANNEL,"Port forwarding",NotificationManager.IMPORTANCE_LOW).apply {
            setShowBadge(false);setSound(null,null)
        })
    }
    override fun onStartCommand(intent: Intent?,flags: Int,startId: Int): Int {
        if(intent?.action==STOP) {PortForwards.items.toList().filter {it.running}.forEach {PortForwards.markStopped(it.id)};stopSelf();return START_NOT_STICKY}
        if(intent?.action==CLOSE) {
            val id=intent.getStringExtra("id")
            val generation=intent.getLongExtra("generation",Long.MAX_VALUE)
            workers[id]?.takeIf {it.generation<=generation}?.let {workers.remove(id);it.job.cancel();NativeBridge.forwardDestroy(it.handle)}
            PortForwards.persist()
            if(workers.isEmpty() && PortForwards.items.none {it.phase=="connecting"}) stopSelf() else ongoing()
            return START_NOT_STICKY
        }
        if(intent?.action!=ADD) {stopSelf();return START_NOT_STICKY}
        try {
            val notification=notification()
            if(Build.VERSION.SDK_INT>=34) startForeground(NOTIFICATION,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            else startForeground(NOTIFICATION,notification)
            val id=requireNotNull(intent.getStringExtra("id"))
            val item=PortForwards.items.firstOrNull {it.id==id}
            if(item==null || item.phase!="connecting") {if(workers.isEmpty()) stopSelf();return START_NOT_STICKY}
            workers[id]?.let {
                if(it.generation==item.generation) return START_NOT_STICKY
                workers.remove(id);it.job.cancel();NativeBridge.forwardDestroy(it.handle)
            }
            require(workers.size<8) {"Stop a forward before adding another (maximum 8)"}
            val request=JSONObject().put("id","$id-${UUID.randomUUID().toString().replace("-", "").take(16)}").put("endpoint",item.endpoint)
                .put("session",item.session).put("host",item.host).put("port",item.port).put("local_port",item.localPort)
            val handle=NativeBridge.forwardCreate(request.toString())
            check(handle!=0L) {"Could not start forward"}
            val job=scope.launch {
                while(isActive) {
                    val state=withContext(Dispatchers.IO) {JSONObject(NativeBridge.forwardPoll(handle))}
                    val current=PortForwards.items.firstOrNull {it.id==id} ?: break
                    if(current.generation!=item.generation) break
                    val next=current.copy(phase=state.optString("phase","failed"),address=state.optString("address"),error=state.optString("error"))
                    if(next!=current) {PortForwards.update(next);ongoing()}
                    if(next.phase=="active") {
                        if(next.localPort!=next.boundPort) {PortForwards.update(next.copy(localPort=next.boundPort));PortForwards.persist()};PortForwards.ready(next)
                    }
                    if(next.phase=="failed") {
                        workers.remove(id);NativeBridge.forwardDestroy(handle)
                        if(state.optBoolean("port_unavailable") && current.localPort>0) PortForwards.portBusy(this@PortForwardService,id)
                        else PortForwards.failed(next)
                        if(workers.isEmpty() && PortForwards.items.none {it.phase=="connecting"}) stopSelf()
                        else ongoing()
                        break
                    }
                    delay(250)
                }
            }
            workers[id]=Worker(handle,job,item.generation)
            ongoing()
        } catch(e: Exception) {
            PortForwards.notice=e.message ?: "Could not start port forwarding"
            PortForwards.items.firstOrNull {it.id==intent.getStringExtra("id")}?.let {
                val failed=it.copy(phase="failed",error=PortForwards.notice.orEmpty());PortForwards.update(failed);PortForwards.failed(failed)
            }
            if(workers.isEmpty()) stopSelf()
        }
        return START_NOT_STICKY
    }
    private fun notification(): Notification {
        val open=PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop=PendingIntent.getService(this,1,Intent(this,PortForwardService::class.java).setAction(STOP),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this,CHANNEL).setSmallIcon(R.drawable.ic_notification).setContentTitle("Rexling port forwarding")
            .setContentText("${workers.size} forwards · tap Rexling to manage").setContentIntent(open).setOngoing(true)
            .addAction(Notification.Action.Builder(null,"Stop all",stop).build()).build()
    }
    private fun ongoing() {notifications.notify(NOTIFICATION,notification())}
    override fun onTaskRemoved(rootIntent: Intent?) {PortForwards.items.toList().filter {it.running}.forEach {PortForwards.markStopped(it.id)};stopSelf()}
    override fun onDestroy() {
        val owned=workers.toMap()
        owned.values.forEach {it.job.cancel();NativeBridge.forwardDestroy(it.handle)}
        workers.clear()
        PortForwards.items.toList().filter {it.running && owned[it.id]?.generation==it.generation}.forEach {
            PortForwards.update(it.copy(phase="stopped"));PortForwards.failed(it.copy(error="Forward stopped."))
        }
        PortForwards.persist();scope.cancel();stopForeground(STOP_FOREGROUND_REMOVE);super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder?=null
    companion object {
        const val ADD="dev.lemon.rex.forward.ADD"
        const val CLOSE="dev.lemon.rex.forward.CLOSE"
        const val STOP="dev.lemon.rex.forward.STOP"
        private const val CHANNEL="port_forwards"
        private const val NOTIFICATION=4401
    }
}

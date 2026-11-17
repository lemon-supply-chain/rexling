package dev.shulhi.rex

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Keeps the process alive while a loopback forward serves the browser.
 *
 * This is deliberately separate from [BackgroundMonitorService]: turning off
 * background monitoring must not tear down a page the user is reading.
 */
class PortForwardService: Service() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private val notifications by lazy {getSystemService(NotificationManager::class.java)}
    private var running=false

    override fun onCreate() {
        super.onCreate()
        notifications.createNotificationChannel(NotificationChannel(CHANNEL,"Port forwarding",NotificationManager.IMPORTANCE_LOW).apply {
            description="Keeps forwarded terminal ports reachable from the browser"
            setShowBadge(false)
            setSound(null,null)
            enableVibration(false)
        })
    }
    override fun onStartCommand(intent: Intent?,flags: Int,startId: Int): Int {
        if(intent?.action==CLOSE_ALL) {
            PortForwardRegistry.closeAll()
            stopSelf()
            return START_NOT_STICKY
        }
        if(!running) {
            val notification=notification(emptyList())
            if(Build.VERSION.SDK_INT>=34) startForeground(NOTIFICATION_ID,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            else startForeground(NOTIFICATION_ID,notification)
            running=true
            scope.launch {PortForwardRegistry.states.collectLatest {states->notifications.notify(NOTIFICATION_ID,notification(states))}}
        }
        return START_NOT_STICKY
    }
    private fun notification(states: List<PortForwardState>): Notification {
        val active=states.count {it.active}
        val waiting=states.count {it.awaitingSelection}
        val title=when {
            active>0 && waiting>0 -> "$active ${if(active==1) "forward" else "forwards"} · choose a port"
            active>0 -> "$active ${if(active==1) "port forward" else "port forwards"}"
            waiting>0 -> "Choose a terminal port"
            else -> "Preparing port forward"
        }
        val open=PendingIntent.getActivity(this,1,Intent(this,MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val close=PendingIntent.getService(this,2,Intent(this,PortForwardService::class.java).setAction(CLOSE_ALL),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this,CHANNEL).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Rexling · $title")
            .setContentText("Loopback ports stay reachable while you browse")
            .setContentIntent(open).setOngoing(true).setShowWhen(false)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(Notification.Action.Builder(null,"Close all",close).build())
            .build()
    }
    override fun onTaskRemoved(rootIntent: Intent?) {
        PortForwardRegistry.closeAll()
        stopSelf()
    }
    override fun onDestroy() {
        running=false
        PortForwardRegistry.closeAll()
        scope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder?=null
    companion object {
        const val CHANNEL="port_forward"
        const val NOTIFICATION_ID=7200
        const val CLOSE_ALL="dev.shulhi.rex.CLOSE_FORWARDS"
        fun start(context: Context) {
            runCatching {context.startForegroundService(Intent(context,PortForwardService::class.java))}
        }
        fun stop(context: Context) {
            runCatching {context.stopService(Intent(context,PortForwardService::class.java))}
        }
    }
}

package life.xstar.music.player.together

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import life.xstar.music.player.MainActivity
import life.xstar.music.player.R
import life.xstar.music.player.XstarApplication

class TogetherService : Service() {
    companion object {
        const val ACTION_START = "life.xstar.music.player.together.START"
        const val ACTION_STOP = "life.xstar.music.player.together.STOP"
        private const val CHANNEL_ID = "xstar_together"
        private const val NOTIFICATION_ID = 1301

        fun start(context: Context) {
            val intent = Intent(context, TogetherService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent) else context.startService(intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, TogetherService::class.java).setAction(ACTION_STOP))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val controller: TogetherSessionController
        get() = (application as XstarApplication).togetherController

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                controller.endSession()
                stopForegroundCompat()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                startForegroundCompat(buildNotification(controller.state.value))
                observeState()
                return START_STICKY
            }
        }
    }

    private fun observeState() {
        scope.launch {
            var wasInSession = false
            controller.state.collectLatest { state ->
                val notification = buildNotification(state)
                val manager = getSystemService(NotificationManager::class.java)
                manager?.notify(NOTIFICATION_ID, notification)
                if (state.inSession) {
                    wasInSession = true
                } else if (wasInSession) {
                    wasInSession = false
                    stopForegroundCompat()
                    stopSelf()
                }
            }
        }
    }

    private fun buildNotification(state: TogetherSessionState): Notification {
        val peerName = state.peer?.nickname ?: "好友"
        val title = when {
            state.role == TogetherRole.Host && state.connection == TogetherConnection.Connected -> "一起听 · 与 $peerName"
            state.role == TogetherRole.Member && state.connection == TogetherConnection.Connected -> "一起听 · 房主 $peerName"
            else -> "一起听 · 等待好友加入"
        }
        val text = when {
            state.connection == TogetherConnection.Connected -> "双方对等控制 · 退出请点下方按钮"
            else -> "邀请码 ${state.code ?: "-"} · 正在等待连接"
        }
        val contentIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 0, Intent(this, TogetherService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .addAction(0, "结束一起听", stopIntent)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "一起听", NotificationManager.IMPORTANCE_LOW).apply {
                description = "一起听会话播放状态"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun startForegroundCompat(notification: Notification) {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        )
    }

    private fun stopForegroundCompat() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}

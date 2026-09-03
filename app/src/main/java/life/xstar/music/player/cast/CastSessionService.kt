package life.xstar.music.player.cast

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

class CastSessionService : Service() {
    companion object {
        const val ACTION_START = "life.xstar.music.player.cast.START"
        const val ACTION_STOP = "life.xstar.music.player.cast.STOP"
        private const val CHANNEL_ID = "xstar_cast"
        private const val NOTIFICATION_ID = 1201

        fun start(context: Context) {
            val intent = Intent(context, CastSessionService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent) else context.startService(intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, CastSessionService::class.java).setAction(ACTION_STOP))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val controller: CastSessionController
        get() = (application as XstarApplication).castController

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                controller.disconnect()
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
            controller.state.collectLatest { state ->
                val notification = buildNotification(state)
                val manager = getSystemService(NotificationManager::class.java)
                manager?.notify(NOTIFICATION_ID, notification)
                if (state.connection != CastSessionController.Connection.Connected && state.connection != CastSessionController.Connection.Connecting) {
                    stopForegroundCompat()
                    stopSelf()
                }
            }
        }
    }

    private fun buildNotification(state: CastSessionState): Notification {
        val deviceName = state.device?.friendlyName ?: "投屏设备"
        val title = if (state.connection == CastSessionController.Connection.Connected) state.song?.title ?: "正在投屏到 $deviceName"
        else "正在连接 $deviceName"
        val text = if (state.connection == CastSessionController.Connection.Connected) "投到 $deviceName · ${state.song?.artist ?: ""}" else "正在建立连接"
        val contentIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 0, Intent(this, CastSessionService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .addAction(0, "断开投屏", stopIntent)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "投屏播放", NotificationManager.IMPORTANCE_LOW).apply {
                description = "投屏到局域网设备的播放状态"
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

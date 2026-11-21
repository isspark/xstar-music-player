package life.xstar.music.player.data.nas

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import life.xstar.music.player.MainActivity
import life.xstar.music.player.XstarApplication

/** Owns synchronization independently of the Activity and its ViewModel. */
class NasSyncService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var syncJob: Job? = null
    private var progressJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val repository get() = (application as XstarApplication).nasRepository
    private val notifications get() = getSystemService(NotificationManager::class.java)

    override fun onCreate() {
        super.onCreate()
        notifications.createNotificationChannel(NotificationChannel(CHANNEL, "NAS 曲库同步", NotificationManager.IMPORTANCE_LOW))
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification("正在准备同步 NAS 曲库…", true),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_PAUSE) {
            scope.launch {
                repository.pauseSync()
                syncJob?.cancelAndJoin()
                stopSelf()
            }
            return START_NOT_STICKY
        }
        if (syncJob?.isActive == true) return START_NOT_STICKY
        val detailed = intent?.getBooleanExtra("detailed", false) ?: false
        wakeLock = getSystemService(PowerManager::class.java).newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK, "$packageName:NasSync"
        ).apply { acquire(6 * 60 * 60 * 1000L) }
        progressJob = scope.launch {
            // StateFlow conflates fast directory updates; avoid flooding NotificationManager.
            while (isActive) {
                val status = repository.status.value
                notifications.notify(NOTIFICATION_ID, notification(status.message.ifBlank { "正在同步 NAS 曲库…" }, true))
                delay(750)
            }
        }
        syncJob = scope.launch {
            try {
                repository.prepareResume()
                repository.sync(detailed)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                repository.reportStatus(false, "同步失败，请检查网络后重试")
            } finally {
                progressJob?.cancel()
                releaseWakeLock()
                ServiceCompat.stopForeground(this@NasSyncService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                notifications.notify(NOTIFICATION_ID, notification(repository.status.value.message, false))
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun notification(message: String, running: Boolean): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val pause = PendingIntent.getService(this, 1, Intent(this, NasSyncService::class.java).setAction(ACTION_PAUSE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(if (running) "正在同步 NAS 曲库" else "NAS 曲库同步")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setContentIntent(open).setOnlyAlertOnce(true).setOngoing(running).setAutoCancel(!running)
            .apply { if (running) { setProgress(0, 0, true); addAction(0, "暂停", pause) } }
            .build()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        syncJob?.cancel()
        releaseWakeLock()
        stopSelf()
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    override fun onDestroy() {
        scope.cancel()
        releaseWakeLock()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL = "nas_library_sync"
        private const val NOTIFICATION_ID = 4103
        private const val ACTION_PAUSE = "life.xstar.music.player.PAUSE_NAS_SYNC"

        fun pause(context: Context) {
            context.startService(Intent(context, NasSyncService::class.java).setAction(ACTION_PAUSE))
        }

        fun start(context: Context, detailed: Boolean = false) {
            ContextCompat.startForegroundService(context,
                Intent(context, NasSyncService::class.java).putExtra("detailed", detailed))
        }
    }
}

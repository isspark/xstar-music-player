package life.xstar.music.player.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import life.xstar.music.player.R
import life.xstar.music.player.data.local.MusicDatabase
import life.xstar.music.player.data.lyrics.LyricLine
import life.xstar.music.player.data.lyrics.LyricsRepository
import life.xstar.music.player.data.lyrics.LyricsState
import life.xstar.music.player.data.preferences.LyricsMode
import life.xstar.music.player.data.preferences.PlaybackPreferences
import life.xstar.music.player.player.PlayerController
import kotlin.math.roundToInt

class LyricsWidgetProvider : AppWidgetProvider() {
    companion object {
        private const val ACTION_PREFIX = "life.xstar.music.player.widget."
        const val ACTION_REFRESH = ACTION_PREFIX + "REFRESH"
        const val ACTION_PLAY_PAUSE = ACTION_PREFIX + "PLAY_PAUSE"
        const val ACTION_PREVIOUS = ACTION_PREFIX + "PREVIOUS"
        const val ACTION_NEXT = ACTION_PREFIX + "NEXT"
        const val ACTION_SEEK = ACTION_PREFIX + "SEEK"
        const val EXTRA_FRACTION = "fraction"

        fun refresh(context: Context) {
            context.sendBroadcast(Intent(context, LyricsWidgetProvider::class.java).setAction(ACTION_REFRESH))
        }
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = update(context, ids)

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH || intent.action in setOf(ACTION_PLAY_PAUSE, ACTION_PREVIOUS, ACTION_NEXT, ACTION_SEEK)) {
            val pending = goAsync()
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                val controller = PlayerController(context.applicationContext)
                when (intent.action) {
                    ACTION_PLAY_PAUSE -> controller.toggle()
                    ACTION_PREVIOUS -> controller.previous()
                    ACTION_NEXT -> controller.next()
                    ACTION_SEEK -> {
                        val fraction = intent.getFloatExtra(EXTRA_FRACTION, 0f)
                        val state = controller.state.value
                        controller.seek((state.duration * fraction).roundToInt().toLong())
                    }
                }
                kotlinx.coroutines.delay(220)
                update(context, currentIds(context))
                controller.release()
                pending.finish()
            }
        }
    }

    private fun update(context: Context, ids: IntArray) {
        if (ids.isEmpty()) return
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            val data = WidgetData.load(context.applicationContext)
            val manager = AppWidgetManager.getInstance(context)
            ids.forEach { id -> manager.updateAppWidget(id, data.views(context, id)) }
        }
    }

    private fun currentIds(context: Context): IntArray =
        AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, LyricsWidgetProvider::class.java))

    override fun onDisabled(context: Context) = Unit
}

private data class WidgetData(
    val title: String,
    val previous: String,
    val current: String,
    val next: String,
    val position: Long,
    val duration: Long,
    val playing: Boolean,
    val mode: LyricsMode
) {
    fun views(context: Context, widgetId: Int): RemoteViews = RemoteViews(context.packageName, R.layout.widget_lyrics).apply {
        setTextViewText(R.id.widget_song_title, "$title · ${mode.title}")
        setTextViewText(R.id.widget_lyrics_previous, previous)
        setTextViewText(R.id.widget_lyrics_current, current)
        setTextViewText(R.id.widget_lyrics_next, next)
        setProgressBar(R.id.widget_progress, 1000, if (duration > 0) (position * 1000 / duration).toInt() else 0, false)
        setTextViewText(R.id.widget_time, "${formatTime(position)} / ${formatTime(duration)}")
        setImageViewResource(R.id.widget_play_pause, if (playing) R.drawable.ic_notification_pause else R.drawable.ic_notification_play)
        setOnClickPendingIntent(R.id.widget_previous, action(context, LyricsWidgetProvider.ACTION_PREVIOUS, widgetId))
        setOnClickPendingIntent(R.id.widget_play_pause, action(context, LyricsWidgetProvider.ACTION_PLAY_PAUSE, widgetId))
        setOnClickPendingIntent(R.id.widget_next, action(context, LyricsWidgetProvider.ACTION_NEXT, widgetId))
        // The standard AppWidget API cannot receive drag events. Each progress bar tap maps to a seek point.
        for (index in 0 until 10) {
            val seek = Intent(context, LyricsWidgetProvider::class.java).setAction(LyricsWidgetProvider.ACTION_SEEK)
                .putExtra(LyricsWidgetProvider.EXTRA_FRACTION, (index + 0.5f) / 10f)
            setOnClickPendingIntent(R.id.widget_progress, PendingIntent.getBroadcast(context, widgetId * 100 + index, seek, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        }
    }

    private fun action(context: Context, action: String, widgetId: Int) = PendingIntent.getBroadcast(
        context, widgetId + action.hashCode(), Intent(context, LyricsWidgetProvider::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    companion object {
        suspend fun load(context: Context): WidgetData {
            val prefs = PlaybackPreferences(context)
            val saved = prefs.read()
            val realtime = context.getSharedPreferences("lyrics_widget_state", Context.MODE_PRIVATE)
            val songId = realtime.getString("media_id", null)?.toLongOrNull()
                ?: saved.queue.getOrNull(saved.index)
            val song = songId?.let { MusicDatabase.create(context).songDao().findById(it) }
            val mode = prefs.lyricsMode.first()
            val position = realtime.getLong("position", saved.position)
            val duration = realtime.getLong("duration", song?.durationMs ?: 0L).coerceAtLeast(song?.durationMs ?: 0L)
            val playing = realtime.getBoolean("playing", saved.playing)
            if (song == null) return WidgetData("XSTAR 音乐", "", "播放歌曲后显示歌词", "", position, duration, playing, mode)
            val document = LyricsRepository(context).loadLocalOrEmbedded(song)
            val lines = document?.lines.orEmpty().filter { it.text.isNotBlank() }
            val currentIndex = lines.indexOfLast { it.timeMs != null && it.timeMs <= position }.coerceAtLeast(0)
            return WidgetData(song.title, lines.getOrNull(currentIndex - 1)?.text.orEmpty(), lines.getOrNull(currentIndex)?.text ?: "暂无歌词", lines.getOrNull(currentIndex + 1)?.text.orEmpty(), position, duration, playing, mode)
        }

        private fun formatTime(ms: Long): String = "%d:%02d".format(ms.coerceAtLeast(0) / 60_000, (ms / 1_000) % 60)
    }
}

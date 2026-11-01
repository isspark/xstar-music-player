package life.xstar.music.player.player

import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList
import life.xstar.music.player.R

class PlaybackNotificationProvider(
    private val context: android.content.Context
) : MediaNotification.Provider {
    private val fallback = DefaultMediaNotificationProvider.Builder(context)
        .setChannelId(PlaybackService.NOTIFICATION_CHANNEL_ID)
        .setChannelName(R.string.notification_channel_playback)
        .setNotificationId(PlaybackService.NOTIFICATION_ID)
        .build()

    override fun createNotification(
        mediaSession: MediaSession,
        customLayout: ImmutableList<CommandButton>,
        actionFactory: MediaNotification.ActionFactory,
        callback: MediaNotification.Provider.Callback
    ): MediaNotification {
        return try {
            createCustomNotification(mediaSession, actionFactory)
        } catch (_: Throwable) {
            fallback.createNotification(mediaSession, customLayout, actionFactory, callback)
        }
    }

    private fun createCustomNotification(
        mediaSession: MediaSession,
        actionFactory: MediaNotification.ActionFactory
    ): MediaNotification {
        val player = mediaSession.player
        val metadata = player.mediaMetadata
        val views = RemoteViews(context.packageName, R.layout.notification_playback)
        views.setImageViewResource(R.id.notification_artwork, R.drawable.ic_notification_music)
        views.setTextViewText(
            R.id.notification_title,
            metadata.title ?: context.getString(R.string.notification_playback_unknown_title)
        )
        views.setTextViewText(
            R.id.notification_artist,
            metadata.artist ?: metadata.albumArtist
                ?: context.getString(R.string.notification_playback_unknown_artist)
        )
        val artwork = metadata.artworkData?.let { data ->
            BitmapFactory.decodeByteArray(data, 0, data.size)
        } ?: metadata.artworkUri?.let(::loadArtwork)
        artwork?.let { views.setImageViewBitmap(R.id.notification_artwork, it) }

        val viewIds = intArrayOf(
            R.id.notification_previous,
            R.id.notification_play_pause,
            R.id.notification_next
        )
        val commands = intArrayOf(
            Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_PLAY_PAUSE,
            Player.COMMAND_SEEK_TO_NEXT
        )
        val iconIds = intArrayOf(
            CommandButton.getIconResIdForIconConstant(CommandButton.ICON_PREVIOUS),
            CommandButton.getIconResIdForIconConstant(
                if (player.isPlaying) CommandButton.ICON_PAUSE else CommandButton.ICON_PLAY
            ),
            CommandButton.getIconResIdForIconConstant(CommandButton.ICON_NEXT)
        )
        viewIds.forEachIndexed { index, viewId ->
            views.setImageViewResource(viewId, when (index) {
                0 -> R.drawable.ic_notification_previous
                1 -> if (player.isPlaying) R.drawable.ic_notification_pause else R.drawable.ic_notification_play
                else -> R.drawable.ic_notification_next
            })
            val action = actionFactory.createMediaAction(
                mediaSession,
                IconCompat.createWithResource(context, iconIds[index]),
                when (index) { 0 -> "上一首"; 1 -> "播放或暂停"; else -> "下一首" },
                commands[index]
            )
            views.setOnClickPendingIntent(viewId, action.actionIntent)
        }

        val notification = NotificationCompat.Builder(context, PlaybackService.NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_music)
            .setColor(ContextCompat.getColor(context, R.color.notification_accent))
            .setContentTitle(metadata.title)
            .setContentText(metadata.artist)
            .setContentIntent(mediaSession.sessionActivity)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setOngoing(true)
            .setCustomContentView(views)
            .setCustomBigContentView(views)
            .build()
        return MediaNotification(PlaybackService.NOTIFICATION_ID, notification)
    }

    private fun loadArtwork(uri: Uri) = runCatching {
        when (uri.scheme?.lowercase()) {
            "content", "file" -> context.contentResolver.openInputStream(uri)?.use(BitmapFactory::decodeStream)
            else -> null
        }
    }.getOrNull()

    override fun handleCustomCommand(mediaSession: MediaSession, action: String, extras: Bundle): Boolean = false
}

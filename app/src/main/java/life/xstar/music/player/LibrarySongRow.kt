package life.xstar.music.player

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.PlayArrow
import coil.compose.SubcomposeAsyncImage
import life.xstar.music.player.data.local.SongEntity

/** Shares the search-result layout so both library implementations stay visually identical. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun LibrarySongRow(
    song: SongEntity,
    number: Int,
    selected: Boolean,
    playing: Boolean,
    onPlay: () -> Unit,
    onFavorite: () -> Unit,
    onLongClick: () -> Unit = {}
) {
    OnlineSongRow(
        title = song.title, artist = song.artist, album = song.album,
        source = if (song.uri.startsWith("nas://") || song.sourceFolderUri.startsWith("nas://")) "NAS" else "本地",
        quality = songQualityLabel(song), number = number, selected = selected, playing = playing,
        onPlay = onPlay,
        leading = { LibraryArtwork(song.albumArtUri, selected && playing) }, onLongClick = onLongClick
    ) {
        IconButton(onFavorite) {
            Icon(if (song.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                if (song.isFavorite) "取消收藏" else "收藏",
                tint = if (song.isFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun LibraryArtwork(artwork: String?, playing: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(colors.primary.copy(alpha = .09f)),
        contentAlignment = Alignment.Center) {
        val fallback: @Composable () -> Unit = {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Album, null, Modifier.size(24.dp), tint = colors.primary.copy(alpha = .7f))
            }
        }
        if (artwork.isNullOrBlank()) fallback() else SubcomposeAsyncImage(
            model = if (artwork.contains("://")) artwork else java.io.File(artwork),
            contentDescription = "专辑封面", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop,
            loading = { fallback() }, error = { fallback() })
        if (playing) Box(Modifier.fillMaxSize().background(colors.background.copy(alpha = .65f)), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.GraphicEq, "正在播放", Modifier.size(22.dp), tint = colors.primary)
        }
    }
}

@Composable
internal fun LibraryGroupRow(name: String, subtitle: String, artwork: String?, onOpen: () -> Unit, onPlay: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column {
        HorizontalDivider(color = colors.outlineVariant.copy(alpha = .6f))
        Row(Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            LibraryArtwork(artwork)
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(name, color = colors.onBackground, fontSize = 16.sp, fontWeight = FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
            }
            IconButton(onPlay) { Icon(Icons.Default.PlayArrow, "播放$name", tint = colors.primary) }
        }
    }
}

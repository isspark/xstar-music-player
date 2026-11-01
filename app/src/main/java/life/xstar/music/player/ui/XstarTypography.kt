package life.xstar.music.player.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.runtime.remember
import java.io.File
import android.content.Context
import life.xstar.music.player.R
import life.xstar.music.player.data.preferences.AppFont

val LocalXstarFontFamily = compositionLocalOf<FontFamily> { FontFamily.Default }

@Composable
fun AppFont.toFontFamily(): FontFamily = when (this) {
    AppFont.Default -> FontFamily(Font(R.font.xstar_sans_sc))
    AppFont.Serif -> FontFamily(Font(R.font.xstar_serif_sc))
    AppFont.Monospace -> FontFamily(Font(R.font.xstar_sans_sc))
    AppFont.Cursive -> FontFamily(Font(R.font.xstar_round))
    AppFont.Rounded -> FontFamily(Font(R.font.xstar_round))
    AppFont.Lishu -> FontFamily(Font(R.font.xstar_calligraphy))
}

@Composable
fun downloadedFontFamily(context: Context, path: String, fallback: FontFamily): FontFamily {
    if (path.isBlank()) return fallback
    return remember(path) {
        runCatching { FontFamily(Font(File(path))) }.getOrDefault(fallback)
    }
}

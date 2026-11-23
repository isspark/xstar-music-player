package life.xstar.music.player.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.runtime.remember
import java.io.File
import android.content.Context
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.collectAsState
import life.xstar.music.player.XstarApplication
import life.xstar.music.player.data.font.nasFont
import life.xstar.music.player.data.preferences.AppFont

val LocalXstarFontFamily = compositionLocalOf<FontFamily> { FontFamily.Default }

@Composable
fun AppFont.toFontFamily(): FontFamily {
    val context = LocalContext.current
    val catalog = (context.applicationContext as XstarApplication).fontCatalog
    val version = catalog.version.collectAsState().value
    val font = nasFont()
    val fallback = when (this) {
        AppFont.Default -> FontFamily.Default
        AppFont.Serif -> FontFamily.Serif
        AppFont.Monospace -> FontFamily.Monospace
        AppFont.Cursive, AppFont.Rounded, AppFont.Lishu -> FontFamily.Cursive
    }
    return remember(this, version) {
        if (catalog.isDownloaded(font)) runCatching { FontFamily(Font(catalog.file(font))) }.getOrDefault(fallback)
        else fallback
    }
}

@Composable
fun downloadedFontFamily(context: Context, path: String, fallback: FontFamily): FontFamily {
    if (path.isBlank()) return fallback
    return remember(path) {
        runCatching { FontFamily(Font(File(path))) }.getOrDefault(fallback)
    }
}

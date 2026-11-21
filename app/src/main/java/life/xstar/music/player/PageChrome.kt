package life.xstar.music.player

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.DisposableEffect
import androidx.activity.compose.LocalActivity
import androidx.core.view.WindowCompat
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.foundation.background
import life.xstar.music.player.data.preferences.PlayerTheme
import life.xstar.music.player.ui.player.palette
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val LocalPageTheme = staticCompositionLocalOf { PlayerTheme.Classic }

/** Scene controls the material/background; the user's accent remains the source of truth. */
@Composable
internal fun ThemedPageChrome(theme: PlayerTheme, content: @Composable () -> Unit) {
    val palette = theme.palette()
    val original = MaterialTheme.colorScheme
    val light = theme == PlayerTheme.Tape
    val accent = original.primary
    val onAccent = if (accent.luminance() > .179f) Color.Black else Color.White
    val base = palette.background
    val ink = if (theme == PlayerTheme.Classic) original.onBackground else palette.ink
    val scheme = original.copy(
        primary = accent, onPrimary = onAccent,
        secondary = accent, onSecondary = onAccent,
        tertiary = lerp(accent, ink, .22f), onTertiary = onAccent,
        tertiaryContainer = lerp(base, accent, .20f), onTertiaryContainer = ink,
        inversePrimary = accent,
        background = base, onBackground = ink,
        surface = lerp(base, if (light) Color.White else ink, if (light) .32f else .045f), onSurface = ink,
        surfaceVariant = lerp(base, accent, .12f), onSurfaceVariant = lerp(ink, base, .28f),
        primaryContainer = lerp(base, accent, .20f), onPrimaryContainer = ink,
        secondaryContainer = lerp(base, accent, .16f), onSecondaryContainer = ink,
        outline = lerp(lerp(base, ink, .40f), accent, .16f), outlineVariant = lerp(base, accent, .22f),
        surfaceTint = accent,
        surfaceDim = base, surfaceBright = lerp(base, ink, .10f),
        surfaceContainerLowest = base, surfaceContainerLow = lerp(base, ink, .025f),
        surfaceContainer = lerp(base, ink, .045f), surfaceContainerHigh = lerp(base, ink, .07f),
        surfaceContainerHighest = lerp(base, ink, .10f)
    )
    val activity = LocalActivity.current
    DisposableEffect(activity, light) {
        val controller = activity?.window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        val oldStatus = controller?.isAppearanceLightStatusBars ?: false
        val oldNavigation = controller?.isAppearanceLightNavigationBars ?: false
        controller?.isAppearanceLightStatusBars = light
        controller?.isAppearanceLightNavigationBars = light
        onDispose {
            controller?.isAppearanceLightStatusBars = oldStatus
            controller?.isAppearanceLightNavigationBars = oldNavigation
        }
    }
    CompositionLocalProvider(LocalPageTheme provides theme) {
        MaterialTheme(colorScheme = scheme) {
            CompositionLocalProvider(LocalContentColor provides ink) {
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(lerp(base, accent, .10f), base)))) { content() }
            }
        }
    }
}

/** Parent owns system-bar insets; every regular page shares this fixed 64dp bar. */
@Composable
internal fun PageTopBar(
    title: String,
    onNavigate: () -> Unit,
    modifier: Modifier = Modifier,
    back: Boolean = false,
    parentInset: Int = 0,
    immersive: Boolean = false,
    actions: @Composable RowScope.() -> Unit = {}
) {
    val expanded = if (parentInset == 0) modifier else modifier.layout { measurable, constraints ->
        val inset = parentInset.dp.roundToPx()
        val width = constraints.maxWidth + inset * 2
        val child = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
        layout(constraints.maxWidth, child.height) { child.placeRelative(-inset, 0) }
    }
    Surface(expanded.fillMaxWidth(), color = Color.Transparent,
        contentColor = if (immersive) LocalContentColor.current else MaterialTheme.colorScheme.onBackground) {
        Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onNavigate, Modifier.size(48.dp), colors = IconButtonDefaults.iconButtonColors(
                contentColor = if (immersive) LocalContentColor.current else MaterialTheme.colorScheme.primary)) {
                Icon(if (back) Icons.AutoMirrored.Filled.ArrowBack else Icons.Default.Menu,
                    if (back) "返回" else "菜单", Modifier.size(24.dp))
            }
            Text(title, Modifier.weight(1f).padding(start = 4.dp, end = 8.dp),
                color = if (immersive) LocalContentColor.current else MaterialTheme.colorScheme.primary,
                fontSize = 22.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            CompositionLocalProvider(LocalContentColor provides if (immersive) LocalContentColor.current else MaterialTheme.colorScheme.primary) {
                Row(verticalAlignment = Alignment.CenterVertically, content = actions)
            }
            Spacer(Modifier.width(4.dp))
        }
    }
}

@Composable
internal fun PageSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String = "搜索歌曲、歌手或专辑",
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onSearch: (() -> Unit)? = null
) {
    OutlinedTextField(query, onQueryChange,
        modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        enabled = enabled, singleLine = true, shape = RoundedCornerShape(if (LocalPageTheme.current == PlayerTheme.Tape) 6.dp else 16.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = .65f),
            unfocusedContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = .40f),
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
            focusedLeadingIconColor = MaterialTheme.colorScheme.primary,
            unfocusedLeadingIconColor = MaterialTheme.colorScheme.primary,
            focusedTrailingIconColor = MaterialTheme.colorScheme.primary,
            unfocusedTrailingIconColor = MaterialTheme.colorScheme.primary,
            cursorColor = MaterialTheme.colorScheme.primary),
        textStyle = MaterialTheme.typography.bodyLarge,
        placeholder = { Text(placeholder, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = { Icon(Icons.Default.Search, null, Modifier.size(24.dp)) },
        trailingIcon = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (query.isNotEmpty()) IconButton({ onQueryChange("") }, enabled = enabled) {
                    Icon(Icons.Default.Close, "清空搜索", Modifier.size(24.dp))
                }
                if (onSearch != null) IconButton(onSearch, enabled = enabled && query.isNotBlank()) {
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, "搜索", Modifier.size(24.dp))
                }
            }
        },
        keyboardOptions = KeyboardOptions(imeAction = if (onSearch != null) ImeAction.Search else ImeAction.Done),
        keyboardActions = KeyboardActions(onSearch = { onSearch?.invoke() })
    )
}

@Composable
internal fun PageSubtitle(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
}

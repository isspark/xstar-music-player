package life.xstar.music.player.ui.player

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import life.xstar.music.player.data.preferences.PlayerTheme
import org.junit.Assert.*
import org.junit.Test

class SceneGeometryTest {
    @Test fun missingOrUnknownPreferenceKeepsClassicPlayer() {
        assertEquals(PlayerTheme.Classic, PlayerTheme.fromStored(null))
        assertEquals(PlayerTheme.Classic, PlayerTheme.fromStored("removed-theme"))
        PlayerTheme.entries.forEach { assertEquals(it, PlayerTheme.fromStored(it.name)) }
    }

    @Test fun everySceneHasFiniteSeekGeometryAndAccurateHitTesting() {
        for (size in listOf(Size(360f, 440f), Size(1080f, 1350f), Size(640f, 230f))) {
            PlayerTheme.entries.filter { it != PlayerTheme.Classic }.forEach { theme ->
                val points = sceneContour(theme, size)
                assertEquals(241, points.size)
                points.forEach { assertTrue("$theme has invalid geometry", it.x.isFinite() && it.y.isFinite()) }
                for (fraction in listOf(.15f, .35f, .6f, .85f)) {
                    val index = (fraction * points.lastIndex).toInt()
                    val result = nearestOnContour(points, points[index])
                    assertEquals("$theme seek fraction", index.toFloat() / points.lastIndex, result.first, .005f)
                    assertEquals(0f, result.second, .001f)
                }
            }
        }
    }

    @Test fun cdContourStaysCenteredAtAllPlaybackPositions() {
        val size = Size(1080f, 1350f)
        val points = sceneContour(PlayerTheme.Liquid, size)
        val center = Offset(size.width * .5f, size.height * .50f)
        val radius = (points.first() - center).getDistance()
        points.forEach { assertEquals(radius, (it - center).getDistance(), .01f) }
    }
}

package life.xstar.music.player.ui.player

import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.abs

class ShortcutLayoutTest {
    @Test fun defaultPositionUsesUniformCompactOrbit() {
        for ((width, height) in listOf(320f to 568f, 412f to 915f)) {
            val points = shortcutOffsets(width, height, width / 2, height - 153f)
            val radiusSquared = points.first().let { it.x * it.x + it.y * it.y }
            assertTrue(radiusSquared <= 76f * 76f + .01f)
            points.forEach { assertEquals(radiusSquared, it.x * it.x + it.y * it.y, .01f) }
            val distances = points.indices.map { i ->
                val next = points[(i + 1) % points.size]
                val dx = points[i].x - next.x
                val dy = points[i].y - next.y
                dx * dx + dy * dy
            }
            distances.forEach { assertEquals(distances.first(), it, .02f) }
        }
    }

    @Test fun targetsStayVisibleAndSeparateAtEdgesAndCorners() {
        for ((width, height) in listOf(320f to 568f, 412f to 915f, 800f to 360f)) {
            for (x in listOf(23f, 39f, width / 2, width - 39, width - 23)) {
                for (y in listOf(39f, height / 2, height - 39)) {
                    val points = shortcutOffsets(width, height, x, y)
                    assertEquals("all five actions remain available", 5, points.size)
                    points.forEach {
                        assertTrue("expanded shortcuts too far from handle: $it", it.x * it.x + it.y * it.y <= 150f * 150f)
                    }
                    points.forEach {
                        assertTrue("horizontal bounds at $x,$y: $it", x + it.x in 27.9f..(width - 27.9f))
                        assertTrue("vertical bounds at $x,$y: $it", y + it.y in 29.9f..(height - 29.9f))
                        assertTrue("overlaps handle $width,$height at $x,$y: $it", abs(it.x) >= 52 || abs(it.y) >= 54)
                    }
                    points.indices.forEach { i ->
                        (0 until i).forEach { j ->
                             assertTrue("overlapping targets", abs(points[i].x - points[j].x) >= 52 || abs(points[i].y - points[j].y) >= 56)
                        }
                    }
                }
            }
        }
    }
}

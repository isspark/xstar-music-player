package life.xstar.music.player.ui.player

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

internal data class ShortcutPoint(val x: Float, val y: Float)

/** All dimensions are dp. Coordinates are relative to the handle centre. */
internal fun shortcutOffsets(width: Float, height: Float, centerX: Float, centerY: Float): List<ShortcutPoint> {
    // Preserve the regular five-petal orbit whenever there is room. Compactness
    // alone favours asymmetric grid cells even at the default, unobstructed position.
    for (radius in listOf(60f, 64f, 68f, 72f, 76f)) {
        for (start in -90 until 270 step 5) {
            val points = List(5) { index ->
                val angle = Math.toRadians((start - 72f * index).toDouble())
                ShortcutPoint(cos(angle).toFloat() * radius, sin(angle).toFloat() * radius)
            }
            val fits = points.all {
                centerX + it.x >= 28 && centerX + it.x <= width - 28 &&
                    centerY + it.y >= 30 && centerY + it.y <= height - 30
            }
            val separates = points.indices.all { i ->
                (0 until i).all { j -> abs(points[i].x - points[j].x) >= 52 || abs(points[i].y - points[j].y) >= 56 }
            }
            if (fits && separates && points.all { abs(it.x) >= 54 || abs(it.y) >= 56 }) return points
        }
    }
    // At corners a single-radius arc needs a very large radius to fit five targets.
    // Compare it with compact, nearby two-row/grid clusters instead of expanding
    // the orbit until every button fits on the same circle.
    val clusters = mutableListOf<List<ShortcutPoint>>()
    val xStarts = listOf(28f) + listOf(-54f, 0f, 54f).map { 28f + ((centerX + it - 28f) % 52f + 52f) % 52f }
    val yStarts = listOf(30f) + listOf(-56f, 0f, 56f).map { 30f + ((centerY + it - 30f) % 56f + 56f) % 56f }
    for (startX in xStarts) {
        for (startY in yStarts) {
            val candidates = mutableListOf<ShortcutPoint>()
            var y = startY
            while (y <= height - 30) {
                var x = startX
                while (x <= width - 28) {
                    val point = ShortcutPoint(x - centerX, y - centerY)
                    if (abs(point.x) >= 54 || abs(point.y) >= 56) candidates += point
                    x += 52
                }
                y += 56
            }
            val nearest = candidates.sortedBy { it.x * it.x + it.y * it.y }.take(5)
            if (nearest.size == 5) clusters += nearest
        }
    }
    fun compactness(points: List<ShortcutPoint>): Float =
        points.maxOf { it.x * it.x + it.y * it.y } + points.sumOf { (it.x * it.x + it.y * it.y).toDouble() }.toFloat() * .01f
    val compact = clusters.minByOrNull(::compactness)
    for (radius in listOf(60f, 68f, 76f, 84f, 92f, 100f, 112f, 124f, 140f, 170f, 200f, 230f, 260f)) {
        for (sweep in listOf(288f, 180f, 140f, 120f, 90f)) {
            for (start in -90 until 270 step 5) {
                val points = List(5) { index ->
                    val angle = Math.toRadians((start - sweep * index / 4).toDouble())
                    ShortcutPoint(cos(angle).toFloat() * radius, sin(angle).toFloat() * radius)
                }
                val fits = points.all {
                    centerX + it.x >= 28 && centerX + it.x <= width - 28 &&
                        centerY + it.y >= 30 && centerY + it.y <= height - 30
                }
                val separate = points.indices.all { i ->
                    (0 until i).all { j ->
                        abs(points[i].x - points[j].x) >= 52 || abs(points[i].y - points[j].y) >= 56
                    }
                }
                val clearsHandle = points.all { abs(it.x) >= 54 || abs(it.y) >= 56 }
                if (fits && separate && clearsHandle) {
                    return if (compact != null && compactness(compact) < compactness(points)) compact else points
                }
            }
        }
    }
    // Compact windows: distribute targets inside the viewport rather than outside it.
    val candidates = mutableListOf<ShortcutPoint>()
    var y = 30f
    while (y <= height - 30) {
        var x = 28f
        while (x <= width - 28) {
            val point = ShortcutPoint(x - centerX, y - centerY)
            if (abs(point.x) >= 54 || abs(point.y) >= 56) candidates.add(point)
            x += 52
        }
        y += 56
    }
    return compact ?: candidates.sortedBy { it.x * it.x + it.y * it.y }.take(5)
}

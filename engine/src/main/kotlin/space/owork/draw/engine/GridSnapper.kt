package space.owork.draw.engine

import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.round
import kotlin.math.sqrt

/**
 * Snaps world coordinates onto the exact analytical grid intersections rendered by
 * Drawspace's GLES 3.0 background pattern shader.
 */
object GridSnapper {
    private val SQRT3 = sqrt(3.0).toFloat()

    fun snapPoint(point: StrokePoint, background: CanvasBackground): StrokePoint {
        val snapped = snapWorld(point.x, point.y, background)
        return point.copy(x = snapped.x, y = snapped.y)
    }

    fun snapWorld(wx: Float, wy: Float, background: CanvasBackground): WorldPoint {
        val s = background.spacingPx.coerceIn(12f, 240f)
        return when (background.pattern) {
            BackgroundPattern.BLANK -> WorldPoint(wx, wy)

            BackgroundPattern.DOT_GRID,
            BackgroundPattern.SQUARE_GRID,
            BackgroundPattern.CROSS_GRID -> WorldPoint(
                x = round(wx / s) * s,
                y = round(wy / s) * s,
            )

            BackgroundPattern.LINED -> WorldPoint(
                x = wx,
                y = round(wy / s) * s,
            )

            BackgroundPattern.ISOMETRIC -> snapIsometric(wx, wy, s)
        }
    }

    /**
     * Finds the nearest exact 3-way intersection of the isometric grid:
     * - Vertical family: `x = k1 * s`
     * - Diagonal families: `±0.5*x + (sqrt(3)/2)*y = k * s`
     *
     * Intersections lie at `(col * s, (2 * row + parity(col)) * (s / sqrt(3)))`.
     */
    private fun snapIsometric(wx: Float, wy: Float, s: Float): WorldPoint {
        val yUnit = s / SQRT3
        val c0 = floor(wx / s).toInt()
        val c1 = c0 + 1

        var bestX = wx
        var bestY = wy
        var bestDist = Float.POSITIVE_INFINITY

        for (col in intArrayOf(c0, c1)) {
            val parity = Math.floorMod(col, 2)
            val cx = col * s
            val row = round((wy / yUnit - parity) * 0.5f).toInt()
            val cy = (2 * row + parity) * yUnit
            val d = hypot(wx - cx, wy - cy)
            if (d < bestDist) {
                bestDist = d
                bestX = cx
                bestY = cy
            }
        }
        return WorldPoint(bestX, bestY)
    }
}

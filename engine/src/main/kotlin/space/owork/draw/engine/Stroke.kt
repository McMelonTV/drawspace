package space.owork.draw.engine

import kotlinx.serialization.Serializable
import kotlin.math.max
import kotlin.math.min

/**
 * A single stylus sample in world coordinates.
 * Tilt is in radians [0, pi/2] matching Android MotionEvent.AXIS_TILT.
 */
@Serializable
data class StrokePoint(
    val x: Float,
    val y: Float,
    val pressure: Float = 0.5f,
    val tilt: Float = 0f,
    val orientation: Float = 0f,
    val tMs: Long = 0L,
)

@Serializable
data class Stroke(
    val id: String = Ulid.next(),
    val brush: BrushSpec,
    val points: List<StrokePoint>,
    val bounds: WorldRect = computeBounds(points, brush.sizeWorldPx),
) {
    /**
     * Checks whether a world-space point (with an eraser radius) intersects this stroke.
     */
    fun hitTest(wx: Float, wy: Float, eraserRadius: Float): Boolean {
        if (brush.tool == ToolType.FILL) {
            if (!bounds.expanded(eraserRadius).contains(wx, wy)) return false
            var i = 0
            while (i + 1 < points.size) {
                val tl = points[i]
                val br = points[i + 1]
                if (wx >= tl.x - eraserRadius && wx <= br.x + eraserRadius &&
                    wy >= tl.y - eraserRadius && wy <= br.y + eraserRadius
                ) {
                    return true
                }
                i += 2
            }
            return false
        }
        val hitRadius = eraserRadius + brush.sizeWorldPx * 0.6f
        if (!bounds.expanded(eraserRadius).contains(wx, wy)) return false
        if (points.isEmpty()) return false
        if (points.size == 1) {
            val p = points[0]
            return distancePointToSegment(wx, wy, p.x, p.y, p.x, p.y) <= hitRadius
        }
        for (i in 0 until points.lastIndex) {
            val a = points[i]
            val b = points[i + 1]
            if (distancePointToSegment(wx, wy, a.x, a.y, b.x, b.y) <= hitRadius) {
                return true
            }
        }
        return false
    }

    companion object {
        fun computeBounds(points: List<StrokePoint>, brushSizeWorldPx: Float): WorldRect {
            if (points.isEmpty()) return WorldRect(0f, 0f, 0f, 0f)
            var minX = Float.POSITIVE_INFINITY
            var minY = Float.POSITIVE_INFINITY
            var maxX = Float.NEGATIVE_INFINITY
            var maxY = Float.NEGATIVE_INFINITY
            for (p in points) {
                minX = min(minX, p.x)
                minY = min(minY, p.y)
                maxX = max(maxX, p.x)
                maxY = max(maxY, p.y)
            }
            val pad = max(brushSizeWorldPx * 1.25f, 2f)
            return WorldRect(minX - pad, minY - pad, maxX + pad, maxY + pad)
        }
    }
}

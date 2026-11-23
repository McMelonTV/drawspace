package space.owork.draw.engine

import kotlin.math.acos
import kotlin.math.hypot
import kotlin.math.min

/**
 * Post-stroke geometric refinement that cleans up stylus touchdown/liftoff micro-hooks,
 * smooths pressure jitter, and rounds sharp angular kinks using Chaikin corner-cutting
 * while keeping the stroke faithful to the artist's path.
 */
object StrokeSmoother {

    fun refine(
        rawPoints: List<StrokePoint>,
        level: StrokeSmoothingLevel,
        roundTurnsAndEnds: Boolean,
    ): List<StrokePoint> {
        if (rawPoints.size <= 2) return rawPoints
        var pts = rawPoints

        if (roundTurnsAndEnds) {
            pts = trimEndpointHooks(pts)
        }
        if (pts.size <= 2) return pts

        val chaikinPasses = when (level) {
            StrokeSmoothingLevel.OFF -> if (roundTurnsAndEnds) 1 else 0
            StrokeSmoothingLevel.LOW -> if (roundTurnsAndEnds) 1 else 0
            StrokeSmoothingLevel.MEDIUM -> if (roundTurnsAndEnds) 2 else 1
            StrokeSmoothingLevel.HIGH -> if (roundTurnsAndEnds) 3 else 2
        }

        if (chaikinPasses > 0) {
            // Resample slightly before Chaikin so closely bunched samples don't stall corner rounding.
            pts = deduplicateClosePoints(pts, minDist = 0.6f)
            repeat(chaikinPasses) {
                if (pts.size >= 3) {
                    pts = chaikinPass(pts)
                }
            }
        }

        if (roundTurnsAndEnds && pts.size >= 3) {
            pts = smoothPressure(pts)
        }

        return pts
    }

    /**
     * Removes tiny 1-2 sample touchdown/liftoff micro-hooks caused by nib impact/lift jitter
     * when the angle sharply reverses over a sub-3px distance.
     */
    private fun trimEndpointHooks(points: List<StrokePoint>): List<StrokePoint> {
        if (points.size < 4) return points
        var start = 0
        var end = points.lastIndex

        // Check start hook
        if (end - start >= 3) {
            val d01 = dist(points[start], points[start + 1])
            if (d01 < 3.5f && turnAngle(points[start], points[start + 1], points[start + 2]) > 1.75f) {
                start++
            }
        }
        // Check end hook
        if (end - start >= 3) {
            val dLast = dist(points[end - 1], points[end])
            if (dLast < 3.5f && turnAngle(points[end - 2], points[end - 1], points[end]) > 1.75f) {
                end--
            }
        }
        return if (start == 0 && end == points.lastIndex) points else points.subList(start, end + 1)
    }

    private fun deduplicateClosePoints(points: List<StrokePoint>, minDist: Float): List<StrokePoint> {
        if (points.size <= 2) return points
        val out = ArrayList<StrokePoint>(points.size)
        out.add(points.first())
        for (i in 1 until points.lastIndex) {
            if (dist(out.last(), points[i]) >= minDist) {
                out.add(points[i])
            }
        }
        out.add(points.last())
        return out
    }

    /**
     * Endpoint-preserving Chaikin corner-cutting pass (0.25 / 0.75 blend) that rounds sharp turns
     * smoothly while keeping start/end positions anchored.
     */
    private fun chaikinPass(points: List<StrokePoint>): List<StrokePoint> {
        val out = ArrayList<StrokePoint>(points.size * 2)
        out.add(points.first())
        for (i in 0 until points.lastIndex) {
            val a = points[i]
            val b = points[i + 1]
            if (i > 0) {
                out.add(lerp(a, b, 0.25f))
            }
            if (i < points.lastIndex - 1) {
                out.add(lerp(a, b, 0.75f))
            }
        }
        out.add(points.last())
        return out
    }

    /**
     * Gentle 3-tap pressure filter to prevent staircase width jumps when zoomed far in.
     */
    private fun smoothPressure(points: List<StrokePoint>): List<StrokePoint> {
        val out = ArrayList<StrokePoint>(points.size)
        out.add(points.first())
        for (i in 1 until points.lastIndex) {
            val p = points[i]
            val avgP = points[i - 1].pressure * 0.25f + p.pressure * 0.5f + points[i + 1].pressure * 0.25f
            val avgT = points[i - 1].tilt * 0.25f + p.tilt * 0.5f + points[i + 1].tilt * 0.25f
            out.add(p.copy(pressure = avgP, tilt = avgT))
        }
        out.add(points.last())
        return out
    }

    private fun lerp(a: StrokePoint, b: StrokePoint, t: Float): StrokePoint {
        val inv = 1f - t
        return StrokePoint(
            x = a.x * inv + b.x * t,
            y = a.y * inv + b.y * t,
            pressure = a.pressure * inv + b.pressure * t,
            tilt = a.tilt * inv + b.tilt * t,
            orientation = a.orientation,
            tMs = a.tMs + ((b.tMs - a.tMs) * t).toLong(),
        )
    }

    private fun dist(a: StrokePoint, b: StrokePoint): Float = hypot(b.x - a.x, b.y - a.y)

    private fun turnAngle(a: StrokePoint, b: StrokePoint, c: StrokePoint): Float {
        val v1x = b.x - a.x
        val v1y = b.y - a.y
        val v2x = c.x - b.x
        val v2y = c.y - b.y
        val l1 = hypot(v1x, v1y)
        val l2 = hypot(v2x, v2y)
        if (l1 < 1e-4f || l2 < 1e-4f) return 0f
        val dot = ((v1x * v2x + v1y * v2y) / (l1 * l2)).coerceIn(-1f, 1f)
        return acos(dot)
    }
}

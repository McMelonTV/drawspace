package space.owork.draw.engine

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Pure-Kotlin geometric partial eraser for vector strokes and bucket-fill regions.
 *
 * Cuts strokes against a swept eraser capsule (`(ax, ay) -> (bx, by)` with radius [eraserRadius]),
 * producing 0, 1, or more freshly-ID'd surviving fragments while preserving exact interpolated
 * coordinates, pressure, tilt, orientation, and brush attributes.
 */
object PartialEraser {

    private const val MIN_FRAGMENT_LENGTH_WORLD = 0.5f
    private const val EPS = 1e-4f

    /**
     * Erases the portion of [stroke] intersecting the swept capsule `(ax, ay) -> (bx, by)` of radius [eraserRadius].
     *
     * @return `null` if [stroke] is completely untouched, `emptyList()` if completely erased,
     *         or a non-empty list of surviving fragments (each assigned a fresh ULID) if partially carved.
     */
    fun eraseCapsule(
        stroke: Stroke,
        ax: Float,
        ay: Float,
        bx: Float,
        by: Float,
        eraserRadius: Float,
    ): List<Stroke>? {
        if (stroke.points.isEmpty() || eraserRadius <= 0f) return null
        if (stroke.brush.tool == ToolType.FILL) {
            return eraseFillStroke(stroke, ax, ay, bx, by, eraserRadius)
        }
        return erasePolylineStroke(stroke, ax, ay, bx, by, eraserRadius)
    }

    private fun erasePolylineStroke(
        stroke: Stroke,
        ax: Float,
        ay: Float,
        bx: Float,
        by: Float,
        eraserRadius: Float,
    ): List<Stroke>? {
        val hw = stroke.brush.sizeWorldPx * 0.5f
        val cutRadius = eraserRadius + min(hw * 0.5f, eraserRadius * 0.35f)
        val capBounds = WorldRect(
            left = min(ax, bx) - cutRadius,
            top = min(ay, by) - cutRadius,
            right = max(ax, bx) + cutRadius,
            bottom = max(ay, by) + cutRadius,
        )
        if (!stroke.bounds.intersects(capBounds)) return null

        val pts = stroke.points
        if (pts.size == 1) {
            val p = pts[0]
            return if (distancePointToSegment(p.x, p.y, ax, ay, bx, by) <= cutRadius) {
                emptyList()
            } else {
                null
            }
        }

        val fragments = ArrayList<ArrayList<StrokePoint>>(2)
        var current: ArrayList<StrokePoint>? = null
        var modified = false

        fun flushCurrent() {
            val cur = current ?: return
            if (cur.size >= 2 && arcLength(cur) >= MIN_FRAGMENT_LENGTH_WORLD) {
                fragments.add(cur)
            }
            current = null
        }

        fun appendToCurrent(point: StrokePoint) {
            val cur = current
            if (cur == null) {
                val list = ArrayList<StrokePoint>()
                list.add(point)
                current = list
            } else {
                val last = cur.last()
                if (hypot(point.x - last.x, point.y - last.y) > EPS) {
                    cur.add(point)
                }
            }
        }

        for (i in 0 until pts.lastIndex) {
            val p0 = pts[i]
            val p1 = pts[i + 1]
            val hit = segmentCapsuleInterval(p0.x, p0.y, p1.x, p1.y, ax, ay, bx, by, cutRadius)
            if (hit == null || hit.second - hit.first <= EPS) {
                // Segment is completely outside the capsule
                if (current == null) {
                    current = ArrayList<StrokePoint>().apply { add(p0) }
                }
                appendToCurrent(p1)
                continue
            }

            modified = true
            val tEnter = hit.first
            val tExit = hit.second

            if (tEnter > EPS) {
                // Prefix [0, tEnter] survives before entering the capsule
                if (current == null) {
                    current = ArrayList<StrokePoint>().apply { add(p0) }
                }
                appendToCurrent(lerpPoint(p0, p1, tEnter))
                flushCurrent()
            } else {
                // Segment starts inside the capsule -> close any previous fragment
                flushCurrent()
            }

            if (tExit < 1f - EPS) {
                // Suffix [tExit, 1] survives after exiting the capsule
                val exitPt = lerpPoint(p0, p1, tExit)
                current = ArrayList<StrokePoint>().apply {
                    add(exitPt)
                    if (hypot(p1.x - exitPt.x, p1.y - exitPt.y) > EPS) {
                        add(p1)
                    }
                }
            }
        }
        flushCurrent()

        if (!modified) return null
        if (fragments.isEmpty()) return emptyList()

        return fragments.map { fragPts ->
            Stroke(
                id = Ulid.next(),
                brush = stroke.brush,
                points = fragPts,
            )
        }
    }

    private fun eraseFillStroke(
        stroke: Stroke,
        ax: Float,
        ay: Float,
        bx: Float,
        by: Float,
        eraserRadius: Float,
    ): List<Stroke>? {
        val capBounds = WorldRect(
            left = min(ax, bx) - eraserRadius,
            top = min(ay, by) - eraserRadius,
            right = max(ax, bx) + eraserRadius,
            bottom = max(ay, by) + eraserRadius,
        )
        if (!stroke.bounds.intersects(capBounds)) return null

        val pts = stroke.points
        val minCell = max(eraserRadius * 0.22f, 1.5f)
        val surviving = ArrayList<WorldRect>(pts.size / 2 + 16)
        var modified = false

        var i = 0
        while (i + 1 < pts.size) {
            val l = pts[i].x
            val t = pts[i].y
            val r = pts[i + 1].x
            val b = pts[i + 1].y
            if (r <= l || b <= t) {
                i += 2
                continue
            }
            val rect = WorldRect(l, t, r, b)
            if (!rect.intersects(capBounds)) {
                surviving.add(rect)
                i += 2
                continue
            }
            if (distanceRectToSegment(l, t, r, b, ax, ay, bx, by) > eraserRadius) {
                surviving.add(rect)
                i += 2
                continue
            }

            modified = true
            if (allCornersInsideCapsule(l, t, r, b, ax, ay, bx, by, eraserRadius)) {
                // Completely inside the eraser capsule -> dropped
                i += 2
                continue
            }

            // Preserve up to 4 untouched outer strips outside the capsule AABB
            val ovL = max(l, capBounds.left)
            val ovT = max(t, capBounds.top)
            val ovR = min(r, capBounds.right)
            val ovB = min(b, capBounds.bottom)

            if (ovL > l + EPS) surviving.add(WorldRect(l, t, ovL, b))
            if (r > ovR + EPS) surviving.add(WorldRect(ovR, t, r, b))
            if (ovT > t + EPS && ovR > ovL + EPS) surviving.add(WorldRect(ovL, t, ovR, ovT))
            if (b > ovB + EPS && ovR > ovL + EPS) surviving.add(WorldRect(ovL, ovB, ovR, b))

            // Subdivide only the overlap region against the exact capsule
            if (ovR > ovL + EPS && ovB > ovT + EPS) {
                carveRectRecursive(ovL, ovT, ovR, ovB, ax, ay, bx, by, eraserRadius, minCell, 0, surviving)
            }
            i += 2
        }

        if (!modified) return null
        if (surviving.isEmpty()) return emptyList()

        val outPts = ArrayList<StrokePoint>(surviving.size * 2)
        var minX = Float.POSITIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        for (rect in surviving) {
            outPts.add(StrokePoint(rect.left, rect.top, pressure = 1f))
            outPts.add(StrokePoint(rect.right, rect.bottom, pressure = 1f))
            minX = min(minX, rect.left)
            minY = min(minY, rect.top)
            maxX = max(maxX, rect.right)
            maxY = max(maxY, rect.bottom)
        }
        return listOf(
            Stroke(
                id = Ulid.next(),
                brush = stroke.brush,
                points = outPts,
                bounds = WorldRect(minX, minY, maxX, maxY),
            ),
        )
    }

    private fun carveRectRecursive(
        l: Float,
        t: Float,
        r: Float,
        b: Float,
        ax: Float,
        ay: Float,
        bx: Float,
        by: Float,
        radius: Float,
        minCell: Float,
        depth: Int,
        out: MutableList<WorldRect>,
    ) {
        if (allCornersInsideCapsule(l, t, r, b, ax, ay, bx, by, radius)) return
        if (distanceRectToSegment(l, t, r, b, ax, ay, bx, by) > radius) {
            out.add(WorldRect(l, t, r, b))
            return
        }
        val w = r - l
        val h = b - t
        if (max(w, h) <= minCell || depth >= 10) {
            val cx = (l + r) * 0.5f
            val cy = (t + b) * 0.5f
            if (distancePointToSegment(cx, cy, ax, ay, bx, by) > radius) {
                out.add(WorldRect(l, t, r, b))
            }
            return
        }
        if (w >= h) {
            val mid = (l + r) * 0.5f
            carveRectRecursive(l, t, mid, b, ax, ay, bx, by, radius, minCell, depth + 1, out)
            carveRectRecursive(mid, t, r, b, ax, ay, bx, by, radius, minCell, depth + 1, out)
        } else {
            val mid = (t + b) * 0.5f
            carveRectRecursive(l, t, r, mid, ax, ay, bx, by, radius, minCell, depth + 1, out)
            carveRectRecursive(l, mid, r, b, ax, ay, bx, by, radius, minCell, depth + 1, out)
        }
    }

    private fun allCornersInsideCapsule(
        l: Float,
        t: Float,
        r: Float,
        b: Float,
        ax: Float,
        ay: Float,
        bx: Float,
        by: Float,
        radius: Float,
    ): Boolean =
        distancePointToSegment(l, t, ax, ay, bx, by) <= radius &&
            distancePointToSegment(r, t, ax, ay, bx, by) <= radius &&
            distancePointToSegment(l, b, ax, ay, bx, by) <= radius &&
            distancePointToSegment(r, b, ax, ay, bx, by) <= radius

    /**
     * Exact analytical intersection of line segment `P(t) = (p0x, p0y) + t * (p1 - p0)` (`t in [0, 1]`)
     * with the convex capsule around segment `(ax, ay) -> (bx, by)` of radius [radius].
     *
     * Because a capsule is convex and equals `Disk(A) U Disk(B) U OrientedRect(A, B)`,
     * the intersection is always either empty (`null`) or a single closed interval `[tEnter, tExit]`.
     */
    internal fun segmentCapsuleInterval(
        p0x: Float,
        p0y: Float,
        p1x: Float,
        p1y: Float,
        ax: Float,
        ay: Float,
        bx: Float,
        by: Float,
        radius: Float,
    ): Pair<Float, Float>? {
        val dx = p1x - p0x
        val dy = p1y - p0y
        val segLenSq = dx * dx + dy * dy
        if (segLenSq <= 1e-8f) {
            return if (distancePointToSegment(p0x, p0y, ax, ay, bx, by) <= radius) 0f to 1f else null
        }

        var tMin = Float.POSITIVE_INFINITY
        var tMax = Float.NEGATIVE_INFINITY

        fun includeInterval(lo: Float, hi: Float) {
            val cLo = lo.coerceIn(0f, 1f)
            val cHi = hi.coerceIn(0f, 1f)
            if (cLo <= cHi) {
                if (cLo < tMin) tMin = cLo
                if (cHi > tMax) tMax = cHi
            }
        }

        // 1. Disk at A
        segmentDiskInterval(p0x, p0y, dx, dy, segLenSq, ax, ay, radius)?.let {
            includeInterval(it.first, it.second)
        }

        val ux = bx - ax
        val uy = by - ay
        val capLen = hypot(ux, uy)
        if (capLen > 1e-5f) {
            // 2. Disk at B
            segmentDiskInterval(p0x, p0y, dx, dy, segLenSq, bx, by, radius)?.let {
                includeInterval(it.first, it.second)
            }

            // 3. Oriented rectangle along A -> B of half-width `radius`
            val invLen = 1f / capLen
            val tx = ux * invLen
            val ty = uy * invLen
            val nx = -ty
            val ny = tx

            val rx = p0x - ax
            val ry = p0y - ay
            // Longitudinal: s0 + t * sd in [0, capLen]
            val s0 = rx * tx + ry * ty
            val sd = dx * tx + dy * ty
            // Lateral: w0 + t * wd in [-radius, radius]
            val w0 = rx * nx + ry * ny
            val wd = dx * nx + dy * ny

            val sInterval = linearRangeInterval(s0, sd, 0f, capLen)
            val wInterval = if (sInterval != null) linearRangeInterval(w0, wd, -radius, radius) else null
            if (sInterval != null && wInterval != null) {
                val lo = max(sInterval.first, wInterval.first)
                val hi = min(sInterval.second, wInterval.second)
                if (lo <= hi) includeInterval(lo, hi)
            }
        }

        return if (tMin <= tMax) tMin to tMax else null
    }

    private fun segmentDiskInterval(
        p0x: Float,
        p0y: Float,
        dx: Float,
        dy: Float,
        segLenSq: Float,
        cx: Float,
        cy: Float,
        radius: Float,
    ): Pair<Float, Float>? {
        val fx = p0x - cx
        val fy = p0y - cy
        val b = 2f * (fx * dx + fy * dy)
        val c = fx * fx + fy * fy - radius * radius
        val disc = b * b - 4f * segLenSq * c
        if (disc < 0f) return null
        val sqrtDisc = sqrt(disc)
        val inv2a = 0.5f / segLenSq
        val t0 = (-b - sqrtDisc) * inv2a
        val t1 = (-b + sqrtDisc) * inv2a
        if (t1 < 0f || t0 > 1f) return null
        return t0.coerceIn(0f, 1f) to t1.coerceIn(0f, 1f)
    }

    /** Solves `v0 + t * vd in [lo, hi]` for `t in [0, 1]`. */
    private fun linearRangeInterval(
        v0: Float,
        vd: Float,
        lo: Float,
        hi: Float,
    ): Pair<Float, Float>? {
        if (abs(vd) <= 1e-6f) {
            return if (v0 in lo..hi) 0f to 1f else null
        }
        val tA = (lo - v0) / vd
        val tB = (hi - v0) / vd
        val t0 = min(tA, tB)
        val t1 = max(tA, tB)
        if (t1 < 0f || t0 > 1f) return null
        return t0.coerceIn(0f, 1f) to t1.coerceIn(0f, 1f)
    }

    private fun lerpPoint(a: StrokePoint, b: StrokePoint, t: Float): StrokePoint {
        val inv = 1f - t
        return StrokePoint(
            x = a.x * inv + b.x * t,
            y = a.y * inv + b.y * t,
            pressure = a.pressure * inv + b.pressure * t,
            tilt = a.tilt * inv + b.tilt * t,
            orientation = a.orientation * inv + b.orientation * t,
            tMs = a.tMs + ((b.tMs - a.tMs) * t).toLong(),
        )
    }

    private fun arcLength(points: List<StrokePoint>): Float {
        var total = 0f
        for (i in 0 until points.lastIndex) {
            total += hypot(points[i + 1].x - points[i].x, points[i + 1].y - points[i].y)
        }
        return total
    }
}

package space.owork.draw.renderer

import space.owork.draw.engine.BrushSpec
import space.owork.draw.engine.Stroke
import space.owork.draw.engine.StrokePoint
import space.owork.draw.engine.ToolType
import kotlin.math.acos
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

/**
 * Builds zoom-independent GPU triangle-strip ribbons for analytical screen-space SDF anti-aliasing.
 *
 * Vertex layout (8 floats per vertex):
 * - `[0..1]` aCenterPos: world spine position `(cx, cy)`
 * - `[2..3]` aOffsetDir: outward direction in world space (expanded in vertex shader by `halfWidth + aaFeather`)
 * - `[4..5]` aEdgeCap: `(edgeSign [-1, +1], capSign [-1, 0, +1])` for exact circular/chisel cap SDF
 * - `[6..7]` aMeta: `(halfWidthWorld, alpha)`
 */
object StrokeMeshBuilder {
    const val FLOATS_PER_VERTEX = 8
    private const val HALF_PI = 1.5707964f

    fun buildRibbon(stroke: Stroke): FloatArray = buildRibbon(stroke.brush, stroke.points)

    fun buildRibbon(brush: BrushSpec, rawPoints: List<StrokePoint>): FloatArray {
        if (rawPoints.isEmpty()) return FloatArray(0)
        if (rawPoints.size == 1) {
            return buildDotQuad(brush, rawPoints[0])
        }

        val minDistStep = max(2.2f, brush.sizeWorldPx * 0.28f)
        val smoothed = subdividePoints(rawPoints, minDistStep = minDistStep)
        val n = smoothed.size
        val out = FloatArray((n + 2) * 2 * FLOATS_PER_VERTEX)
        var cursor = 0

        fun emit(
            cx: Float,
            cy: Float,
            dirX: Float,
            dirY: Float,
            edgeSign: Float,
            capSign: Float,
            halfWidthWorld: Float,
            alpha: Float,
        ) {
            out[cursor++] = cx
            out[cursor++] = cy
            out[cursor++] = dirX
            out[cursor++] = dirY
            out[cursor++] = edgeSign
            out[cursor++] = capSign
            out[cursor++] = halfWidthWorld
            out[cursor++] = alpha
        }

        val capLookahead = max(brush.sizeWorldPx * 0.45f, 2.5f)
        val startTan = stabilizedTangent(smoothed, fromStart = true, targetArcLen = capLookahead)
        val endTan = stabilizedTangent(smoothed, fromStart = false, targetArcLen = capLookahead)

        var prevTx = startTan.first
        var prevTy = startTan.second

        for (i in 0 until n) {
            val p = smoothed[i]
            var tx: Float
            var ty: Float
            when (i) {
                0 -> {
                    tx = startTan.first
                    ty = startTan.second
                }

                n - 1 -> {
                    tx = endTan.first
                    ty = endTan.second
                }

                else -> {
                    val prev = smoothed[i - 1]
                    val next = smoothed[i + 1]
                    tx = next.x - prev.x
                    ty = next.y - prev.y
                    val len = hypot(tx, ty)
                    if (len > 1e-4f) {
                        tx /= len
                        ty /= len
                        if (tx * prevTx + ty * prevTy < -0.5f) {
                            tx = prevTx
                            ty = prevTy
                        }
                    } else {
                        tx = prevTx
                        ty = prevTy
                    }
                }
            }
            prevTx = tx
            prevTy = ty

            val (nx, ny) = computeNormal(brush, p, tx, ty)
            val halfWidth = computeHalfWidth(brush, p)
            val alpha = computeAlpha(brush, p)

            if (i == 0) {
                emit(p.x, p.y, nx - tx, ny - ty, 1f, -1f, halfWidth, alpha)
                emit(p.x, p.y, -nx - tx, -ny - ty, -1f, -1f, halfWidth, alpha)
            }

            emit(p.x, p.y, nx, ny, 1f, 0f, halfWidth, alpha)
            emit(p.x, p.y, -nx, -ny, -1f, 0f, halfWidth, alpha)

            if (i == n - 1) {
                emit(p.x, p.y, nx + tx, ny + ty, 1f, 1f, halfWidth, alpha)
                emit(p.x, p.y, -nx + tx, -ny + ty, -1f, 1f, halfWidth, alpha)
            }
        }

        return out
    }

    internal fun computeNormal(brush: BrushSpec, p: StrokePoint, tx: Float, ty: Float): Pair<Float, Float> =
        if (brush.tool == ToolType.MARKER) {
            val angle = if (p.tilt > 0.15f) p.orientation else 0.61f
            cos(angle) to sin(angle)
        } else {
            -ty to tx
        }

    internal fun computeHalfWidth(brush: BrushSpec, p: StrokePoint): Float {
        val pressure = p.pressure.coerceIn(0.05f, 1.5f)
        val pFactor = (1f - brush.pressureSizeSensitivity) + brush.pressureSizeSensitivity * pressure
        val tiltNorm = (p.tilt / HALF_PI).coerceIn(0f, 1f)
        val tiltBoost = when (brush.tool) {
            ToolType.PENCIL -> 1f + brush.tiltSensitivity * tiltNorm * 1.6f
            ToolType.MARKER -> 1f + brush.tiltSensitivity * tiltNorm * 0.45f
            else -> 1f
        }
        return max(brush.sizeWorldPx * pFactor * tiltBoost * 0.5f, 0.35f)
    }

    internal fun computeAlpha(brush: BrushSpec, p: StrokePoint): Float {
        val pressure = p.pressure.coerceIn(0.08f, 1.2f)
        val pAlpha = (1f - brush.pressureOpacitySensitivity) + brush.pressureOpacitySensitivity * pressure
        val tiltNorm = (p.tilt / HALF_PI).coerceIn(0f, 1f)
        val tiltShade = if (brush.tool == ToolType.PENCIL) 1f - tiltNorm * 0.35f else 1f
        return (brush.opacity * pAlpha * tiltShade).coerceIn(0.04f, 1f)
    }

    internal fun buildDotQuad(brush: BrushSpec, p: StrokePoint): FloatArray {
        val r = computeHalfWidth(brush, p)
        val a = computeAlpha(brush, p)
        return floatArrayOf(
            p.x, p.y, -1f, -1f, -1f, -1f, r, a,
            p.x, p.y, 1f, -1f, 1f, -1f, r, a,
            p.x, p.y, -1f, 1f, -1f, 1f, r, a,
            p.x, p.y, 1f, 1f, 1f, 1f, r, a,
        )
    }

    private fun stabilizedTangent(
        points: List<StrokePoint>,
        fromStart: Boolean,
        targetArcLen: Float,
    ): Pair<Float, Float> {
        if (points.size < 2) return 1f to 0f
        if (fromStart) {
            val p0 = points[0]
            var acc = 0f
            var target = points[1]
            for (i in 1 until points.size) {
                acc += hypot(points[i].x - points[i - 1].x, points[i].y - points[i - 1].y)
                target = points[i]
                if (acc >= targetArcLen) break
            }
            val dx = target.x - p0.x
            val dy = target.y - p0.y
            val len = hypot(dx, dy)
            return if (len > 1e-4f) (dx / len) to (dy / len) else 1f to 0f
        } else {
            val pEnd = points.last()
            var acc = 0f
            var target = points[points.lastIndex - 1]
            for (i in points.lastIndex - 1 downTo 0) {
                acc += hypot(points[i + 1].x - points[i].x, points[i + 1].y - points[i].y)
                target = points[i]
                if (acc >= targetArcLen) break
            }
            val dx = pEnd.x - target.x
            val dy = pEnd.y - target.y
            val len = hypot(dx, dy)
            return if (len > 1e-4f) (dx / len) to (dy / len) else 1f to 0f
        }
    }

    private fun subdividePoints(
        points: List<StrokePoint>,
        minDistStep: Float,
    ): List<StrokePoint> {
        if (points.size <= 2) return points
        val result = ArrayList<StrokePoint>(points.size * 3)
        result.add(points[0])

        for (i in 1 until points.lastIndex) {
            val prev = points[i - 1]
            val curr = points[i]
            val next = points[i + 1]

            val m0x = (prev.x + curr.x) * 0.5f
            val m0y = (prev.y + curr.y) * 0.5f
            val m1x = (curr.x + next.x) * 0.5f
            val m1y = (curr.y + next.y) * 0.5f

            val dist = hypot(m1x - m0x, m1y - m0y)
            val v1x = curr.x - prev.x
            val v1y = curr.y - prev.y
            val v2x = next.x - curr.x
            val v2y = next.y - curr.y
            val l1 = hypot(v1x, v1y)
            val l2 = hypot(v2x, v2y)
            val turnRad = if (l1 > 1e-4f && l2 > 1e-4f) {
                acos(((v1x * v2x + v1y * v2y) / (l1 * l2)).coerceIn(-1f, 1f))
            } else {
                0f
            }

            val stepsByDist = if (dist > minDistStep) ceil(dist / minDistStep).toInt() else 1
            val stepsByAngle = if (turnRad > 0.16f) ceil(turnRad / 0.16f).toInt() else 1
            val steps = max(stepsByDist, stepsByAngle).coerceIn(1, 10)

            for (s in 1..steps) {
                val t = s.toFloat() / steps
                val inv = 1f - t
                val x = inv * inv * m0x + 2f * inv * t * curr.x + t * t * m1x
                val y = inv * inv * m0y + 2f * inv * t * curr.y + t * t * m1y
                val pressure = inv * prev.pressure + t * curr.pressure
                val tilt = inv * prev.tilt + t * curr.tilt
                result.add(StrokePoint(x, y, pressure, tilt, curr.orientation, curr.tMs))
            }
        }
        result.add(points.last())
        return result
    }
}

/**
 * O(1) amortized incremental mesh builder for an active live stroke.
 *
 * Only meshes newly arrived points and tracks the dirty float range so the GPU thread can
 * update the active VBO via `glBufferSubData` (~few hundred bytes/frame) with zero allocations,
 * eliminating O(N^2) lag on arbitrarily long continuous strokes at any zoom level.
 */
internal class IncrementalLiveMesh {
    private val lock = Any()
    private var brush: BrushSpec = BrushSpec.defaultFor(ToolType.PEN)
    private var generation: Int = 0

    // Reusable vertex float storage (exponentially grown, never reallocated per move)
    private var data = FloatArray(4096)
    private var committedFloats = 0 // stable prefix for points[0 .. meshedPointCount - 2]
    private var totalFloats = 0     // committedFloats + dynamic tail/cap/predicted tip
    private var dirtyFromFloat = 0  // earliest float modified since last GPU upload
    private var meshedPointCount = 0
    private var lastTx = 1f
    private var lastTy = 0f

    fun reset() = synchronized(lock) {
        generation++
        committedFloats = 0
        totalFloats = 0
        dirtyFromFloat = 0
        meshedPointCount = 0
        lastTx = 1f
        lastTy = 0f
    }

    fun update(
        brush: BrushSpec,
        points: List<StrokePoint>,
        predictedTip: StrokePoint?,
        zoom: Float,
    ) {
        synchronized(lock) {
            if (points.isEmpty()) {
                reset()
                return
            }
            this.brush = brush

            if (brush.tool == ToolType.LINE && points.size == 2) {
                val verts = StrokeMeshBuilder.buildRibbon(brush, points)
                ensureCapacity(verts.size)
                verts.copyInto(data, 0)
                committedFloats = 0
                totalFloats = verts.size
                dirtyFromFloat = 0
                meshedPointCount = 2
                return
            }

            // Start of a brand-new stroke
            if (points.size < meshedPointCount || meshedPointCount == 0) {
                generation++
                committedFloats = 0
                totalFloats = 0
                dirtyFromFloat = 0
                meshedPointCount = 0
                lastTx = 1f
                lastTy = 0f
            }

            if (points.size == 1) {
                val dot = StrokeMeshBuilder.buildDotQuad(brush, points[0])
                ensureCapacity(dot.size)
                dot.copyInto(data, 0)
                committedFloats = 0
                totalFloats = dot.size
                dirtyFromFloat = 0
                meshedPointCount = 1
                return
            }

        // Only append newly finalized spine points into the permanent committed prefix.
        val screenStepWorld = (2.5f / zoom.coerceAtLeast(0.05f)).coerceAtLeast(1.5f)
        var cursor = committedFloats
        if (dirtyFromFloat > cursor) dirtyFromFloat = cursor

        // Emit start cap + point[0] once we have at least 2 points
        if (committedFloats == 0 && points.size >= 2) {
            val p0 = points[0]
            val p1 = points[1]
            var tx = p1.x - p0.x
            var ty = p1.y - p0.y
            val len = hypot(tx, ty)
            if (len > 1e-4f) {
                tx /= len
                ty /= len
            } else {
                tx = 1f
                ty = 0f
            }
            lastTx = tx
            lastTy = ty
            val (nx, ny) = StrokeMeshBuilder.computeNormal(brush, p0, tx, ty)
            val hw = StrokeMeshBuilder.computeHalfWidth(brush, p0)
            val a = StrokeMeshBuilder.computeAlpha(brush, p0)
            cursor = emitPairCap(cursor, p0.x, p0.y, nx, ny, -tx, -ty, -1f, hw, a)
            cursor = emitPairBody(cursor, p0.x, p0.y, nx, ny, hw, a)
            committedFloats = cursor
            meshedPointCount = 1
        }

        // Incrementally append interior points [meshedPointCount .. points.lastIndex - 1]
        val finalizableEnd = points.lastIndex // exclusive; last point waits for next point to know outgoing tangent
        while (meshedPointCount < finalizableEnd) {
            val i = meshedPointCount
            val prev = points[i - 1]
            val curr = points[i]
            val next = points[i + 1]

            // Quadratic midpoint curve between m0 and m1, step size scaled to current screen zoom!
            val m0x = (prev.x + curr.x) * 0.5f
            val m0y = (prev.y + curr.y) * 0.5f
            val m1x = (curr.x + next.x) * 0.5f
            val m1y = (curr.y + next.y) * 0.5f
            val dist = hypot(m1x - m0x, m1y - m0y)
            val steps = if (dist > screenStepWorld) {
                ceil(dist / screenStepWorld).toInt().coerceIn(2, 6)
            } else {
                1
            }

            var prevX = m0x
            var prevY = m0y
            for (s in 1..steps) {
                val t = s.toFloat() / steps
                val inv = 1f - t
                val x = inv * inv * m0x + 2f * inv * t * curr.x + t * t * m1x
                val y = inv * inv * m0y + 2f * inv * t * curr.y + t * t * m1y
                val pressure = inv * prev.pressure + t * curr.pressure
                val tilt = inv * prev.tilt + t * curr.tilt
                val sample = StrokePoint(x, y, pressure, tilt, curr.orientation, curr.tMs)

                var tx = x - prevX
                var ty = y - prevY
                val len = hypot(tx, ty)
                if (len > 1e-4f) {
                    tx /= len
                    ty /= len
                    if (tx * lastTx + ty * lastTy < -0.5f) {
                        tx = lastTx
                        ty = lastTy
                    }
                    lastTx = tx
                    lastTy = ty
                } else {
                    tx = lastTx
                    ty = lastTy
                }
                prevX = x
                prevY = y

                val (nx, ny) = StrokeMeshBuilder.computeNormal(brush, sample, tx, ty)
                val hw = StrokeMeshBuilder.computeHalfWidth(brush, sample)
                val a = StrokeMeshBuilder.computeAlpha(brush, sample)
                cursor = emitPairBody(cursor, x, y, nx, ny, hw, a)
            }
            meshedPointCount++
        }
        committedFloats = cursor

        // Append transient tail (last real point + optional predicted tip + end cap)
        var tailCursor = committedFloats
        val lastPt = points.last()
        val prevPt = if (points.size >= 2) points[points.lastIndex - 1] else lastPt
        var tx = lastPt.x - prevPt.x
        var ty = lastPt.y - prevPt.y
        val tLen = hypot(tx, ty)
        if (tLen > 1e-4f) {
            tx /= tLen
            ty /= tLen
        } else {
            tx = lastTx
            ty = lastTy
        }
        val (lnx, lny) = StrokeMeshBuilder.computeNormal(brush, lastPt, tx, ty)
        val lhw = StrokeMeshBuilder.computeHalfWidth(brush, lastPt)
        val la = StrokeMeshBuilder.computeAlpha(brush, lastPt)
        tailCursor = emitPairBody(tailCursor, lastPt.x, lastPt.y, lnx, lny, lhw, la)

        if (predictedTip != null && hypot(predictedTip.x - lastPt.x, predictedTip.y - lastPt.y) > 0.5f) {
            var ptx = predictedTip.x - lastPt.x
            var pty = predictedTip.y - lastPt.y
            val pLen = hypot(ptx, pty)
            if (pLen > 1e-4f) {
                ptx /= pLen
                pty /= pLen
            } else {
                ptx = tx
                pty = ty
            }
            val (pnx, pny) = StrokeMeshBuilder.computeNormal(brush, predictedTip, ptx, pty)
            val phw = StrokeMeshBuilder.computeHalfWidth(brush, predictedTip)
            val pa = StrokeMeshBuilder.computeAlpha(brush, predictedTip)
            tailCursor = emitPairBody(tailCursor, predictedTip.x, predictedTip.y, pnx, pny, phw, pa)
            tailCursor = emitPairCap(tailCursor, predictedTip.x, predictedTip.y, pnx, pny, ptx, pty, 1f, phw, pa)
        } else {
            tailCursor = emitPairCap(tailCursor, lastPt.x, lastPt.y, lnx, lny, tx, ty, 1f, lhw, la)
        }

        totalFloats = tailCursor
        }
    }

    /**
     * Executes [consumer] under lock on the GL thread so zero float array copies happen during rendering.
     */
    fun readForGpu(
        consumer: (
            brush: BrushSpec,
            generation: Int,
            data: FloatArray,
            totalFloats: Int,
            dirtyFromFloat: Int,
        ) -> Unit,
    ) = synchronized(lock) {
        consumer(brush, generation, data, totalFloats, dirtyFromFloat)
        dirtyFromFloat = committedFloats
    }

    private fun emitPairBody(
        cursor: Int,
        cx: Float,
        cy: Float,
        nx: Float,
        ny: Float,
        hw: Float,
        alpha: Float,
    ): Int {
        ensureCapacity(cursor + 16)
        var c = cursor
        data[c++] = cx; data[c++] = cy; data[c++] = nx; data[c++] = ny; data[c++] = 1f; data[c++] = 0f; data[c++] = hw; data[c++] = alpha
        data[c++] = cx; data[c++] = cy; data[c++] = -nx; data[c++] = -ny; data[c++] = -1f; data[c++] = 0f; data[c++] = hw; data[c++] = alpha
        return c
    }

    private fun emitPairCap(
        cursor: Int,
        cx: Float,
        cy: Float,
        nx: Float,
        ny: Float,
        capDirX: Float,
        capDirY: Float,
        capSign: Float,
        hw: Float,
        alpha: Float,
    ): Int {
        ensureCapacity(cursor + 16)
        var c = cursor
        data[c++] = cx; data[c++] = cy; data[c++] = nx + capDirX; data[c++] = ny + capDirY; data[c++] = 1f; data[c++] = capSign; data[c++] = hw; data[c++] = alpha
        data[c++] = cx; data[c++] = cy; data[c++] = -nx + capDirX; data[c++] = -ny + capDirY; data[c++] = -1f; data[c++] = capSign; data[c++] = hw; data[c++] = alpha
        return c
    }

    private fun ensureCapacity(needed: Int) {
        if (needed <= data.size) return
        var nextSize = data.size * 2
        while (nextSize < needed) nextSize *= 2
        data = data.copyOf(nextSize)
    }
}

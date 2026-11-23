package space.owork.draw.engine

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

sealed interface BucketFillResult {
    data class Success(val stroke: Stroke) : BucketFillResult
    data object OpenRegion : BucketFillResult
    data object AlreadyFilled : BucketFillResult
    data object OutsideCanvas : BucketFillResult
}

/**
 * High-precision, zero-seam scanline flood-fill engine for hybrid stroke canvases.
 *
 * Architecture (Centerline-First Two-Tier Flood):
 * - **Tier 1 (Centerline Barrier):** Rasterizes only the exact 1-cell 4-connected supercover
 *   centerline of line/brush strokes (ignoring neighboring `FILL` strokes so adjacent fills can
 *   never carve holes out of each other). Flood-filling against the centerline flows cleanly all
 *   the way into razor-sharp 30°/60° acute corners and tucks directly behind the rendered outlines
 *   with zero sawtooth gaps, zero corner wedges, and zero bleed-through.
 * - **Tier 2 (Visual Body Fallback):** If a loose freehand shape only closes via overlapping wide
 *   brush edges (centerlines don't quite meet), automatically retries against the rendered stroke
 *   body (`0.42×` stroke width) with safe interior barrier tucking so loose sketches still seal.
 */
object BucketFillEngine {

    private const val MAX_LOCAL_GRID_DIM = 1024
    private const val MAX_FALLBACK_GRID_DIM = 1920

    private const val CENTERLINE_BIT = 1
    private const val STROKE_BODY_BIT = 2

    fun floodFill(
        document: Document,
        worldX: Float,
        worldY: Float,
        fillBrush: BrushSpec,
        visibleWorldBounds: WorldRect? = null,
    ): BucketFillResult {
        val fixedBounds = document.meta.canvasMode.bounds
        if (fixedBounds != null && !fixedBounds.contains(worldX, worldY)) {
            return BucketFillResult.OutsideCanvas
        }

        val strokes = document.visibleStrokes()
        val lineStrokes = strokes.filter {
            it.brush.tool != ToolType.ERASER && it.brush.tool != ToolType.FILL && it.points.isNotEmpty()
        }

        // Ignore accidental taps directly on top of an outline stroke so outlines never get filled.
        for (s in lineStrokes) {
            if (s.hitTest(worldX, worldY, eraserRadius = 0f)) {
                return BucketFillResult.AlreadyFilled
            }
        }

        // Avoid stacking identical duplicate fills on top of an already identically colored region.
        val topExistingFill = strokes.lastOrNull { s ->
            s.brush.tool == ToolType.FILL && s.hitTest(worldX, worldY, eraserRadius = 0f)
        }
        if (topExistingFill != null &&
            topExistingFill.brush.colorArgb == fillBrush.colorArgb &&
            fillBrush.opacity >= 0.99f &&
            topExistingFill.brush.opacity >= 0.99f
        ) {
            return BucketFillResult.AlreadyFilled
        }

        // Try high-res viewport-local window first for zoomed-in precision.
        if (visibleWorldBounds != null && !visibleWorldBounds.isEmpty) {
            val localRect = if (fixedBounds != null) {
                intersectRect(visibleWorldBounds.expanded(16f), fixedBounds)
            } else {
                visibleWorldBounds.expanded(16f)
            }
            if (localRect != null && localRect.contains(worldX, worldY)) {
                val localAttempt = runTwoTierFill(
                    lineStrokes = lineStrokes,
                    workRect = localRect,
                    seedWorldX = worldX,
                    seedWorldY = worldY,
                    fillBrush = fillBrush,
                    maxGridDim = MAX_LOCAL_GRID_DIM,
                    allowBoundaryTouch = fixedBounds != null && rectMatches(localRect, fixedBounds),
                )
                if (localAttempt !is BucketFillResult.OpenRegion) {
                    return localAttempt
                }
            }
        }

        // Fall back to full artboard (Fixed-Size, 1:1 up to 1920px) or full stroke bounds (Infinite).
        val fallbackRect = if (fixedBounds != null) {
            fixedBounds
        } else {
            val content = document.contentBounds() ?: return BucketFillResult.OpenRegion
            content.union(WorldRect.fromPoint(worldX, worldY, 24f)).expanded(24f)
        }
        return runTwoTierFill(
            lineStrokes = lineStrokes,
            workRect = fallbackRect,
            seedWorldX = worldX,
            seedWorldY = worldY,
            fillBrush = fillBrush,
            maxGridDim = if (fixedBounds != null) MAX_FALLBACK_GRID_DIM else MAX_LOCAL_GRID_DIM,
            allowBoundaryTouch = fixedBounds != null,
        )
    }

    private fun runTwoTierFill(
        lineStrokes: List<Stroke>,
        workRect: WorldRect,
        seedWorldX: Float,
        seedWorldY: Float,
        fillBrush: BrushSpec,
        maxGridDim: Int,
        allowBoundaryTouch: Boolean,
    ): BucketFillResult {
        val worldW = workRect.width.coerceAtLeast(1f)
        val worldH = workRect.height.coerceAtLeast(1f)
        val cellSize = max(worldW, worldH) / maxGridDim.toFloat()
        val gridW = ceil(worldW / cellSize).toInt().coerceIn(8, maxGridDim)
        val gridH = ceil(worldH / cellSize).toInt().coerceIn(8, maxGridDim)

        val seedX = floor((seedWorldX - workRect.left) / cellSize).toInt().coerceIn(0, gridW - 1)
        val seedY = floor((seedWorldY - workRect.top) / cellSize).toInt().coerceIn(0, gridH - 1)

        // Single pass rasterizes BOTH exact 1-cell CENTERLINE_BIT and visual STROKE_BODY_BIT
        val mask = ByteArray(gridW * gridH)
        var maxBodyRadiusCells = 1f
        for (stroke in lineStrokes) {
            if (!stroke.bounds.intersects(workRect)) continue
            val r = rasterizeStrokeMask(stroke, workRect, cellSize, mask, gridW, gridH)
            if (r > maxBodyRadiusCells) maxBodyRadiusCells = r
        }

        val seedIdx = seedY * gridW + seedX
        if ((mask[seedIdx].toInt() and CENTERLINE_BIT) != 0) {
            return BucketFillResult.AlreadyFilled
        }

        // Tier 1: Flood strictly against exact 1-cell centerlines -> zero corner wedges, zero sawtooths!
        val tier1 = scanlineFlood(
            mask = mask,
            barrierBit = CENTERLINE_BIT,
            gridW = gridW,
            gridH = gridH,
            seedX = seedX,
            seedY = seedY,
            allowBoundaryTouch = allowBoundaryTouch,
        )
        if (tier1 != null) {
            // Exact 0.5-cell tuck meets the stroke centerline seamlessly without ever crossing it.
            return buildFillStroke(tier1, gridW, gridH, workRect, cellSize, fillBrush, tuckCells = 0.5f)
        }

        // Tier 2: Loose freehand strokes whose centerlines didn't quite meet, but visual edges closed.
        // Only try Tier 2 when not allowed to touch boundary (i.e. looking for an enclosed shape).
        if (!allowBoundaryTouch && (mask[seedIdx].toInt() and STROKE_BODY_BIT) == 0) {
            val tier2 = scanlineFlood(
                mask = mask,
                barrierBit = STROKE_BODY_BIT,
                gridW = gridW,
                gridH = gridH,
                seedX = seedX,
                seedY = seedY,
                allowBoundaryTouch = false,
            )
            if (tier2 != null) {
                val maxDepth = ceil(maxBodyRadiusCells * 1.5f).toInt().coerceIn(2, 24)
                val tucked = tuckIntoBodyBarrier(tier2, mask, gridW, gridH, maxDepth)
                return buildFillStroke(tucked, gridW, gridH, workRect, cellSize, fillBrush, tuckCells = 0.35f)
            }
        }

        return BucketFillResult.OpenRegion
    }

    /**
     * Iterative 4-connected scanline BFS. Returns `null` if the flood leaks to the boundary
     * when [allowBoundaryTouch] is false.
     */
    private fun scanlineFlood(
        mask: ByteArray,
        barrierBit: Int,
        gridW: Int,
        gridH: Int,
        seedX: Int,
        seedY: Int,
        allowBoundaryTouch: Boolean,
    ): BooleanArray? {
        val total = gridW * gridH
        val filled = BooleanArray(total)
        val stackX = IntArray(total)
        val stackY = IntArray(total)
        var sp = 0
        stackX[sp] = seedX
        stackY[sp] = seedY
        sp++

        var filledCount = 0
        while (sp > 0) {
            sp--
            val cx = stackX[sp]
            val cy = stackY[sp]
            val rowOff = cy * gridW
            if (filled[rowOff + cx] || (mask[rowOff + cx].toInt() and barrierBit) != 0) continue

            var lx = cx
            while (lx > 0 && !filled[rowOff + lx - 1] && (mask[rowOff + lx - 1].toInt() and barrierBit) == 0) {
                lx--
            }
            var rx = cx
            while (rx < gridW - 1 && !filled[rowOff + rx + 1] && (mask[rowOff + rx + 1].toInt() and barrierBit) == 0) {
                rx++
            }

            if (!allowBoundaryTouch && (lx == 0 || rx == gridW - 1 || cy == 0 || cy == gridH - 1)) {
                return null
            }

            var spanAbove = false
            var spanBelow = false
            for (x in lx..rx) {
                filled[rowOff + x] = true
                filledCount++

                if (cy > 0) {
                    val idxUp = (cy - 1) * gridW + x
                    val openUp = !filled[idxUp] && (mask[idxUp].toInt() and barrierBit) == 0
                    if (!spanAbove && openUp) {
                        stackX[sp] = x
                        stackY[sp] = cy - 1
                        sp++
                        spanAbove = true
                    } else if (spanAbove && !openUp) {
                        spanAbove = false
                    }
                }
                if (cy < gridH - 1) {
                    val idxDown = (cy + 1) * gridW + x
                    val openDown = !filled[idxDown] && (mask[idxDown].toInt() and barrierBit) == 0
                    if (!spanBelow && openDown) {
                        stackX[sp] = x
                        stackY[sp] = cy + 1
                        sp++
                        spanBelow = true
                    } else if (spanBelow && !openDown) {
                        spanBelow = false
                    }
                }
            }
        }
        return if (filledCount > 0) filled else null
    }

    /**
     * Used only for Tier 2 (loose freehand edge-closed strokes): expands strictly into stroke-body
     * pixels up to the stroke centerline, stopping before crossing `CENTERLINE_BIT` or outside space.
     */
    private fun tuckIntoBodyBarrier(
        origFilled: BooleanArray,
        mask: ByteArray,
        gridW: Int,
        gridH: Int,
        maxDepth: Int,
    ): BooleanArray {
        val out = origFilled.copyOf()
        val depth = IntArray(gridW * gridH) { -1 }

        fun isSafeBody(x: Int, y: Int): Boolean {
            val m = mask[y * gridW + x].toInt()
            if ((m and STROKE_BODY_BIT) == 0) return false
            if ((m and CENTERLINE_BIT) != 0) return false
            val minNy = (y - 1).coerceAtLeast(0)
            val maxNy = (y + 1).coerceAtMost(gridH - 1)
            val minNx = (x - 1).coerceAtLeast(0)
            val maxNx = (x + 1).coerceAtMost(gridW - 1)
            for (ny in minNy..maxNy) {
                val nRow = ny * gridW
                for (nx in minNx..maxNx) {
                    val nIdx = nRow + nx
                    if (!origFilled[nIdx] && (mask[nIdx].toInt() and STROKE_BODY_BIT) == 0) {
                        return false
                    }
                }
            }
            return true
        }

        var queue = IntArray(2048)
        var head = 0
        var tail = 0
        fun enqueue(idx: Int) {
            if (tail >= queue.size) queue = queue.copyOf(queue.size * 2)
            queue[tail++] = idx
        }

        for (y in 0 until gridH) {
            val rowOff = y * gridW
            for (x in 0 until gridW) {
                val idx = rowOff + x
                if (origFilled[idx] || (mask[idx].toInt() and STROKE_BODY_BIT) == 0) continue
                var touchesFill = false
                val minNy = (y - 1).coerceAtLeast(0)
                val maxNy = (y + 1).coerceAtMost(gridH - 1)
                val minNx = (x - 1).coerceAtLeast(0)
                val maxNx = (x + 1).coerceAtMost(gridW - 1)
                outer@ for (ny in minNy..maxNy) {
                    val nRow = ny * gridW
                    for (nx in minNx..maxNx) {
                        if (origFilled[nRow + nx]) {
                            touchesFill = true
                            break@outer
                        }
                    }
                }
                if (touchesFill) {
                    out[idx] = true
                    depth[idx] = 1
                    if (maxDepth > 1 && isSafeBody(x, y)) enqueue(idx)
                }
            }
        }

        while (head < tail) {
            val idx = queue[head++]
            val nextD = depth[idx] + 1
            if (nextD > maxDepth) continue
            val x = idx % gridW
            val y = idx / gridW
            val minNy = (y - 1).coerceAtLeast(0)
            val maxNy = (y + 1).coerceAtMost(gridH - 1)
            val minNx = (x - 1).coerceAtLeast(0)
            val maxNx = (x + 1).coerceAtMost(gridW - 1)
            for (ny in minNy..maxNy) {
                val nRow = ny * gridW
                for (nx in minNx..maxNx) {
                    val nIdx = nRow + nx
                    if (!out[nIdx] && isSafeBody(nx, ny)) {
                        out[nIdx] = true
                        depth[nIdx] = nextD
                        if (nextD < maxDepth) enqueue(nIdx)
                    }
                }
            }
        }
        return out
    }

    private fun buildFillStroke(
        filled: BooleanArray,
        gridW: Int,
        gridH: Int,
        workRect: WorldRect,
        cellSize: Float,
        fillBrush: BrushSpec,
        tuckCells: Float,
    ): BucketFillResult {
        val rects = extractMergedRects(filled, gridW, gridH, workRect, cellSize, tuckCells)
        if (rects.isEmpty()) return BucketFillResult.OpenRegion

        val pts = ArrayList<StrokePoint>(rects.size * 2)
        var minX = Float.POSITIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        for (r in rects) {
            pts.add(StrokePoint(r.left, r.top, pressure = 1f))
            pts.add(StrokePoint(r.right, r.bottom, pressure = 1f))
            minX = min(minX, r.left)
            minY = min(minY, r.top)
            maxX = max(maxX, r.right)
            maxY = max(maxY, r.bottom)
        }

        return BucketFillResult.Success(
            Stroke(
                id = Ulid.next(),
                brush = fillBrush.copy(tool = ToolType.FILL),
                points = pts,
                bounds = WorldRect(minX, minY, maxX, maxY),
            ),
        )
    }

    private fun extractMergedRects(
        filled: BooleanArray,
        gridW: Int,
        gridH: Int,
        workRect: WorldRect,
        cellSize: Float,
        tuckCells: Float,
    ): List<WorldRect> {
        val tuck = cellSize * tuckCells
        data class ActiveSpan(val x0: Int, val x1: Int, val y0: Int, var y1: Int)
        var prevActive = HashMap<Int, ActiveSpan>()
        val finished = ArrayList<ActiveSpan>(256)

        for (y in 0 until gridH) {
            val rowOff = y * gridW
            val nextActive = HashMap<Int, ActiveSpan>()
            var x = 0
            while (x < gridW) {
                if (!filled[rowOff + x]) {
                    x++
                    continue
                }
                val startX = x
                while (x < gridW && filled[rowOff + x]) x++
                val endX = x
                val key = (startX shl 16) or endX
                val continued = prevActive.remove(key)
                if (continued != null) {
                    continued.y1 = y + 1
                    nextActive[key] = continued
                } else {
                    nextActive[key] = ActiveSpan(startX, endX, y, y + 1)
                }
            }
            finished.addAll(prevActive.values)
            prevActive = nextActive
        }
        finished.addAll(prevActive.values)

        return finished.map { s ->
            val left = (workRect.left + s.x0 * cellSize - tuck).coerceAtLeast(workRect.left)
            val top = (workRect.top + s.y0 * cellSize - tuck).coerceAtLeast(workRect.top)
            val right = (workRect.left + s.x1 * cellSize + tuck).coerceAtMost(workRect.right)
            val bottom = (workRect.top + s.y1 * cellSize + tuck).coerceAtMost(workRect.bottom)
            WorldRect(left, top, right, bottom)
        }
    }

    /**
     * Marks both the exact 1-cell 4-connected supercover centerline ([CENTERLINE_BIT]) and
     * the visual stroke body ([STROKE_BODY_BIT]) in a single pass.
     */
    private fun rasterizeStrokeMask(
        stroke: Stroke,
        workRect: WorldRect,
        cellSize: Float,
        mask: ByteArray,
        gridW: Int,
        gridH: Int,
    ): Float {
        val pts = stroke.points
        val brush = stroke.brush
        var maxR = 0.5f

        fun setBit(x: Int, y: Int, bit: Int) {
            if (x in 0 until gridW && y in 0 until gridH) {
                val idx = y * gridW + x
                mask[idx] = (mask[idx].toInt() or bit).toByte()
            }
        }

        fun stampBodyDisc(gx: Float, gy: Float, rCells: Float) {
            if (rCells <= 0.65f) {
                setBit(floor(gx).toInt(), floor(gy).toInt(), STROKE_BODY_BIT)
                return
            }
            val rSq = rCells * rCells
            val minY = floor(gy - rCells).toInt().coerceAtLeast(0)
            val maxY = floor(gy + rCells).toInt().coerceAtMost(gridH - 1)
            val minX = floor(gx - rCells).toInt().coerceAtLeast(0)
            val maxX = floor(gx + rCells).toInt().coerceAtMost(gridW - 1)
            for (y in minY..maxY) {
                val dy = (y + 0.5f) - gy
                val rowOff = y * gridW
                for (x in minX..maxX) {
                    val dx = (x + 0.5f) - gx
                    if (dx * dx + dy * dy <= rSq) {
                        mask[rowOff + x] = (mask[rowOff + x].toInt() or STROKE_BODY_BIT).toByte()
                    }
                }
            }
        }

        if (pts.size == 1) {
            val gx = (pts[0].x - workRect.left) / cellSize
            val gy = (pts[0].y - workRect.top) / cellSize
            val r = brush.sizeWorldPx * 0.42f / cellSize
            setBit(floor(gx).toInt(), floor(gy).toInt(), CENTERLINE_BIT or STROKE_BODY_BIT)
            stampBodyDisc(gx, gy, r)
            return max(maxR, r)
        }

        for (i in 0 until pts.lastIndex) {
            val a = pts[i]
            val b = pts[i + 1]
            val ax = (a.x - workRect.left) / cellSize
            val ay = (a.y - workRect.top) / cellSize
            val bx = (b.x - workRect.left) / cellSize
            val by = (b.y - workRect.top) / cellSize
            val dist = hypot(bx - ax, by - ay)
            val avgP = (a.pressure + b.pressure) * 0.5f
            val pFactor = (1f - brush.pressureSizeSensitivity) + brush.pressureSizeSensitivity * avgP.coerceIn(0.1f, 1.3f)
            val rBody = brush.sizeWorldPx * pFactor * 0.42f / cellSize
            if (rBody > maxR) maxR = rBody

            // 1. Exact 4-connected supercover centerline (always 1-cell thin, zero corner wedges!)
            val centerSteps = ceil(dist * 2.5f).toInt().coerceAtLeast(1)
            var prevCx = floor(ax).toInt()
            var prevCy = floor(ay).toInt()
            setBit(prevCx, prevCy, CENTERLINE_BIT or STROKE_BODY_BIT)
            for (s in 1..centerSteps) {
                val t = s.toFloat() / centerSteps
                val gx = ax + (bx - ax) * t
                val gy = ay + (by - ay) * t
                val cx = floor(gx).toInt()
                val cy = floor(gy).toInt()
                if (cx != prevCx && cy != prevCy) {
                    setBit(prevCx, cy, CENTERLINE_BIT or STROKE_BODY_BIT)
                }
                setBit(cx, cy, CENTERLINE_BIT or STROKE_BODY_BIT)
                prevCx = cx
                prevCy = cy
            }

            // 2. Visual stroke body (only stamped at coarse stride for Tier-2 fallback & tap rejection)
            if (rBody > 0.65f) {
                val bodyStep = max(rBody * 0.55f, 0.75f)
                val bodySteps = ceil(dist / bodyStep).toInt().coerceAtLeast(1)
                for (s in 0..bodySteps) {
                    val t = s.toFloat() / bodySteps
                    stampBodyDisc(ax + (bx - ax) * t, ay + (by - ay) * t, rBody)
                }
            }
        }
        return maxR
    }

    private fun intersectRect(a: WorldRect, b: WorldRect): WorldRect? {
        val l = max(a.left, b.left)
        val t = max(a.top, b.top)
        val r = min(a.right, b.right)
        val bt = min(a.bottom, b.bottom)
        return if (r > l && bt > t) WorldRect(l, t, r, bt) else null
    }

    private fun rectMatches(a: WorldRect, b: WorldRect): Boolean =
        kotlin.math.abs(a.left - b.left) < 1f &&
            kotlin.math.abs(a.top - b.top) < 1f &&
            kotlin.math.abs(a.right - b.right) < 1f &&
            kotlin.math.abs(a.bottom - b.bottom) < 1f
}

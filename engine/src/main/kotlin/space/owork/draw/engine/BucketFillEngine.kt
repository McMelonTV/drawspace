package space.owork.draw.engine

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

sealed interface BucketFillResult {
    data class Success(val stroke: Stroke) : BucketFillResult
    data object OpenRegion : BucketFillResult
    data object AlreadyFilled : BucketFillResult
    data object OutsideCanvas : BucketFillResult
}

/**
 * Pure-Kotlin scanline flood-fill engine for hybrid stroke canvases.
 *
 * Works seamlessly on both Fixed-Size and Infinite canvases (including arbitrary concave shapes
 * and interior holes) by rasterizing nearby visible strokes onto an adaptive high-resolution grid,
 * running a fast scanline BFS, tucking slightly under anti-aliased stroke edges to eliminate halo
 * seams, and merging filled runs into compact axis-aligned rectangles stored as a [ToolType.FILL]
 * stroke (pairs of top-left & bottom-right [StrokePoint]s).
 */
object BucketFillEngine {

    private const val MAX_GRID_DIM = 1024
    private const val FILL_TAG_BIT = 0x01000000
    private const val LINE_BARRIER_BIT = 0x02000000

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
        // For zoomed-in accuracy, try filling inside the visible viewport first (clamped to artboard).
        if (visibleWorldBounds != null && !visibleWorldBounds.isEmpty) {
            val localRect = if (fixedBounds != null) {
                intersectRect(visibleWorldBounds.expanded(16f), fixedBounds)
            } else {
                visibleWorldBounds.expanded(16f)
            }
            if (localRect != null && localRect.contains(worldX, worldY)) {
                val localAttempt = runGridFill(
                    strokes = strokes,
                    workRect = localRect,
                    seedWorldX = worldX,
                    seedWorldY = worldY,
                    fillBrush = fillBrush,
                    allowBoundaryTouch = fixedBounds != null && rectMatches(localRect, fixedBounds),
                )
                if (localAttempt !is BucketFillResult.OpenRegion) {
                    return localAttempt
                }
            }
        }

        // Fall back to full artboard (Fixed-Size) or full stroke-content bounds (Infinite).
        val fallbackRect = if (fixedBounds != null) {
            fixedBounds
        } else {
            val content = document.contentBounds() ?: return BucketFillResult.OpenRegion
            content.union(WorldRect.fromPoint(worldX, worldY, 24f)).expanded(24f)
        }
        return runGridFill(
            strokes = strokes,
            workRect = fallbackRect,
            seedWorldX = worldX,
            seedWorldY = worldY,
            fillBrush = fillBrush,
            allowBoundaryTouch = fixedBounds != null,
        )
    }

    private fun runGridFill(
        strokes: List<Stroke>,
        workRect: WorldRect,
        seedWorldX: Float,
        seedWorldY: Float,
        fillBrush: BrushSpec,
        allowBoundaryTouch: Boolean,
    ): BucketFillResult {
        val worldW = workRect.width.coerceAtLeast(1f)
        val worldH = workRect.height.coerceAtLeast(1f)
        val cellSize = max(worldW, worldH) / MAX_GRID_DIM.toFloat()
        val gridW = ceil(worldW / cellSize).toInt().coerceIn(8, MAX_GRID_DIM)
        val gridH = ceil(worldH / cellSize).toInt().coerceIn(8, MAX_GRID_DIM)

        val seedX = ((seedWorldX - workRect.left) / cellSize).toInt().coerceIn(0, gridW - 1)
        val seedY = ((seedWorldY - workRect.top) / cellSize).toInt().coerceIn(0, gridH - 1)

        // Rasterize existing strokes & fills onto grid:
        // 0 = empty, FILL_TAG_BIT | rgb = filled region, LINE_BARRIER_BIT | rgb = line/brush stroke
        val grid = IntArray(gridW * gridH)
        // Rasterize existing fills first, then line strokes on top (matching visual render order)
        for (stroke in strokes) {
            if (stroke.brush.tool != ToolType.FILL || stroke.points.isEmpty()) continue
            if (!stroke.bounds.intersects(workRect)) continue
            val tag = (stroke.brush.colorArgb and 0x00FFFFFF) or FILL_TAG_BIT
            rasterizeFillStroke(stroke, workRect, cellSize, grid, gridW, gridH, tag)
        }
        for (stroke in strokes) {
            if (stroke.brush.tool == ToolType.ERASER || stroke.brush.tool == ToolType.FILL || stroke.points.isEmpty()) continue
            if (!stroke.bounds.intersects(workRect)) continue
            val tag = (stroke.brush.colorArgb and 0x00FFFFFF) or LINE_BARRIER_BIT
            rasterizeLineStroke(stroke, workRect, cellSize, grid, gridW, gridH, tag)
        }

        val targetTag = grid[seedY * gridW + seedX]
        val desiredTag = (fillBrush.colorArgb and 0x00FFFFFF) or FILL_TAG_BIT
        if (targetTag == desiredTag && fillBrush.opacity >= 0.99f) {
            return BucketFillResult.AlreadyFilled
        }

        // Fast iterative scanline flood-fill
        val filled = BooleanArray(gridW * gridH)
        val stackX = IntArray(gridW * gridH)
        val stackY = IntArray(gridW * gridH)
        var sp = 0
        stackX[sp] = seedX
        stackY[sp] = seedY
        sp++

        var touchedBoundary = false
        var filledCount = 0

        while (sp > 0) {
            sp--
            val cx = stackX[sp]
            val cy = stackY[sp]
            val rowOffset = cy * gridW
            if (filled[rowOffset + cx] || grid[rowOffset + cx] != targetTag) continue

            var lx = cx
            while (lx > 0 && !filled[rowOffset + lx - 1] && grid[rowOffset + lx - 1] == targetTag) {
                lx--
            }
            var rx = cx
            while (rx < gridW - 1 && !filled[rowOffset + rx + 1] && grid[rowOffset + rx + 1] == targetTag) {
                rx++
            }

            if (!allowBoundaryTouch && (lx == 0 || rx == gridW - 1 || cy == 0 || cy == gridH - 1)) {
                touchedBoundary = true
                break
            }

            var spanAbove = false
            var spanBelow = false
            for (x in lx..rx) {
                filled[rowOffset + x] = true
                filledCount++

                if (cy > 0) {
                    val idxUp = (cy - 1) * gridW + x
                    val matchUp = !filled[idxUp] && grid[idxUp] == targetTag
                    if (!spanAbove && matchUp) {
                        stackX[sp] = x
                        stackY[sp] = cy - 1
                        sp++
                        spanAbove = true
                    } else if (spanAbove && !matchUp) {
                        spanAbove = false
                    }
                }
                if (cy < gridH - 1) {
                    val idxDown = (cy + 1) * gridW + x
                    val matchDown = !filled[idxDown] && grid[idxDown] == targetTag
                    if (!spanBelow && matchDown) {
                        stackX[sp] = x
                        stackY[sp] = cy + 1
                        sp++
                        spanBelow = true
                    } else if (spanBelow && !matchDown) {
                        spanBelow = false
                    }
                }
            }
        }

        if (touchedBoundary || filledCount == 0) {
            return BucketFillResult.OpenRegion
        }

        // Tuck 1 cell strictly into surrounding LINE_BARRIER pixels so the fill sits cleanly
        // underneath the anti-aliased stroke edge with zero gaps and zero bleed-through.
        val tucked = dilateIntoLineBarriers(filled, grid, gridW, gridH)

        // Convert filled boolean mask into vertically merged world-space rectangles.
        val rects = extractMergedRects(tucked, gridW, gridH, workRect, cellSize)
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

        val stroke = Stroke(
            id = Ulid.next(),
            brush = fillBrush.copy(tool = ToolType.FILL),
            points = pts,
            bounds = WorldRect(minX, minY, maxX, maxY),
        )
        return BucketFillResult.Success(stroke)
    }

    private fun dilateIntoLineBarriers(
        filled: BooleanArray,
        grid: IntArray,
        gridW: Int,
        gridH: Int,
    ): BooleanArray {
        val out = filled.copyOf()
        for (y in 0 until gridH) {
            val rowOff = y * gridW
            for (x in 0 until gridW) {
                val idx = rowOff + x
                if (filled[idx]) continue
                if ((grid[idx] and LINE_BARRIER_BIT) == 0) continue
                val touchesFill =
                    (x > 0 && filled[idx - 1]) ||
                        (x < gridW - 1 && filled[idx + 1]) ||
                        (y > 0 && filled[idx - gridW]) ||
                        (y < gridH - 1 && filled[idx + gridW])
                if (touchesFill) {
                    out[idx] = true
                }
            }
        }
        return out
    }

    private fun extractMergedRects(
        filled: BooleanArray,
        gridW: Int,
        gridH: Int,
        workRect: WorldRect,
        cellSize: Float,
    ): List<WorldRect> {
        val tuck = cellSize * 0.25f
        // Track active spans from the previous row: key = (startX shl 16) or endX -> ActiveRect
        data class ActiveSpan(val x0: Int, val x1: Int, val y0: Int, var y1: Int)
        var prevActive = HashMap<Int, ActiveSpan>()
        val finished = ArrayList<ActiveSpan>(256)

        for (y in 0 until gridH) {
            val rowOffset = y * gridW
            val nextActive = HashMap<Int, ActiveSpan>()
            var x = 0
            while (x < gridW) {
                if (!filled[rowOffset + x]) {
                    x++
                    continue
                }
                val startX = x
                while (x < gridW && filled[rowOffset + x]) x++
                val endX = x // exclusive
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

    private fun rasterizeFillStroke(
        stroke: Stroke,
        workRect: WorldRect,
        cellSize: Float,
        grid: IntArray,
        gridW: Int,
        gridH: Int,
        tag: Int,
    ) {
        val pts = stroke.points
        var i = 0
        while (i + 1 < pts.size) {
            val tl = pts[i]
            val br = pts[i + 1]
            val x0 = floor((tl.x - workRect.left) / cellSize).toInt().coerceIn(0, gridW - 1)
            val x1 = ceil((br.x - workRect.left) / cellSize).toInt().coerceIn(0, gridW - 1)
            val y0 = floor((tl.y - workRect.top) / cellSize).toInt().coerceIn(0, gridH - 1)
            val y1 = ceil((br.y - workRect.top) / cellSize).toInt().coerceIn(0, gridH - 1)
            for (y in y0..y1) {
                val rowOff = y * gridW
                for (x in x0..x1) {
                    grid[rowOff + x] = tag
                }
            }
            i += 2
        }
    }

    private fun rasterizeLineStroke(
        stroke: Stroke,
        workRect: WorldRect,
        cellSize: Float,
        grid: IntArray,
        gridW: Int,
        gridH: Int,
        tag: Int,
    ) {
        val pts = stroke.points
        val brush = stroke.brush
        val baseRadiusCells = max(brush.sizeWorldPx * 0.48f / cellSize, 1.35f)

        fun stampDisc(gx: Float, gy: Float, rCells: Float) {
            val ri = ceil(rCells).toInt()
            val cx = gx.roundToInt()
            val cy = gy.roundToInt()
            val rSq = rCells * rCells
            val minY = (cy - ri).coerceAtLeast(0)
            val maxY = (cy + ri).coerceAtMost(gridH - 1)
            val minX = (cx - ri).coerceAtLeast(0)
            val maxX = (cx + ri).coerceAtMost(gridW - 1)
            for (y in minY..maxY) {
                val dy = y - gy
                val rowOff = y * gridW
                for (x in minX..maxX) {
                    val dx = x - gx
                    if (dx * dx + dy * dy <= rSq) {
                        grid[rowOff + x] = tag
                    }
                }
            }
        }

        if (pts.size == 1) {
            stampDisc((pts[0].x - workRect.left) / cellSize, (pts[0].y - workRect.top) / cellSize, baseRadiusCells)
            return
        }

        for (i in 0 until pts.lastIndex) {
            val a = pts[i]
            val b = pts[i + 1]
            val ax = (a.x - workRect.left) / cellSize
            val ay = (a.y - workRect.top) / cellSize
            val bx = (b.x - workRect.left) / cellSize
            val by = (b.y - workRect.top) / cellSize
            val dist = hypot(bx - ax, by - ay)
            val steps = ceil(dist / max(baseRadiusCells * 0.45f, 0.6f)).toInt().coerceAtLeast(1)
            val avgP = (a.pressure + b.pressure) * 0.5f
            val pFactor = (1f - brush.pressureSizeSensitivity) + brush.pressureSizeSensitivity * avgP.coerceIn(0.1f, 1.3f)
            val rCells = max(brush.sizeWorldPx * pFactor * 0.48f / cellSize, 1.35f)
            for (s in 0..steps) {
                val t = s.toFloat() / steps
                stampDisc(ax + (bx - ax) * t, ay + (by - ay) * t, rCells)
            }
        }
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

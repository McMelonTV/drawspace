package space.owork.draw.renderer

import android.os.Build
import android.view.MotionEvent
import android.view.View
import androidx.input.motionprediction.MotionEventPredictor
import space.owork.draw.engine.BrushSpec
import space.owork.draw.engine.CameraLocks
import space.owork.draw.engine.Stroke
import space.owork.draw.engine.StrokePoint
import space.owork.draw.engine.StrokeSmoother
import space.owork.draw.engine.StrokeSmoothingLevel
import space.owork.draw.engine.ToolType
import space.owork.draw.engine.Ulid
import space.owork.draw.engine.ViewportCamera
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.round
import kotlin.math.sin

/**
 * Native tablet stylus & multi-touch gesture router.
 *
 * - Stylus (`TOOL_TYPE_STYLUS` / `TOOL_TYPE_ERASER`) always draws or erases with full
 *   historical pressure, tilt, orientation, and `MotionEventPredictor` lookahead.
 * - 2+ fingers always pan, zoom, and rotate the viewport smoothly.
 * - Palm rejection: cancels active strokes cleanly on `FLAG_CANCELED` or when a palm/multi-touch
 *   takes over, never leaving stray dots on the canvas.
 */
internal class StylusInputRouter(
    hostView: View,
    private val callbacks: Callbacks,
) {
    interface Callbacks {
        fun currentCamera(): ViewportCamera
        fun currentCameraLocks(): CameraLocks
        fun currentBrush(): BrushSpec
        fun currentSmoothing(): StrokeSmoothingLevel
        fun roundTurnsAndEnds(): Boolean
        fun snapLineAngles(): Boolean
        fun allowFingerDrawing(): Boolean
        fun onActiveStrokeUpdated(brush: BrushSpec, points: List<StrokePoint>, predictedTip: StrokePoint?)
        fun onActiveStrokeCommitted(stroke: Stroke)
        fun onActiveStrokeCanceled()
        fun onEraseSample(worldX: Float, worldY: Float, eraserRadiusWorld: Float)
        fun onEraseFinished()
        fun onBucketFillTap(worldX: Float, worldY: Float, brush: BrushSpec)
        fun onCameraGesture(newCamera: ViewportCamera)
    }

    private val predictor: MotionEventPredictor = MotionEventPredictor.newInstance(hostView)

    private var activePointerId: Int = MotionEvent.INVALID_POINTER_ID
    private var activeToolIsStylus: Boolean = false
    private var activeBrush: BrushSpec? = null
    private val activePoints = ArrayList<StrokePoint>(128)
    private var isErasing = false

    // 2-finger navigation tracking
    private var isNavigating = false
    private var navPointerA = MotionEvent.INVALID_POINTER_ID
    private var navPointerB = MotionEvent.INVALID_POINTER_ID
    private var lastMidX = 0f
    private var lastMidY = 0f
    private var lastSpan = 0f
    private var lastAngle = 0f

    fun onTouchEvent(event: MotionEvent): Boolean {
        predictor.record(event)
        val action = event.actionMasked

        // Any time 2+ finger touches appear, transition cleanly to 2-finger pan/zoom/rotate
        // (and cancel a stray single-finger touch if palm rejection fired).
        if (event.pointerCount >= 2 && !activeToolIsStylus) {
            handleMultiTouchNavigation(event)
            return true
        }

        if (isNavigating) {
            handleMultiTouchNavigation(event)
            return true
        }

        when (action) {
            MotionEvent.ACTION_DOWN -> {
                val toolType = event.getToolType(0)
                val isStylus = toolType == MotionEvent.TOOL_TYPE_STYLUS || toolType == MotionEvent.TOOL_TYPE_ERASER
                if (!isStylus && !callbacks.allowFingerDrawing()) {
                    // Ignore single finger press when stylus-only mode is active; wait for 2nd finger to pan/zoom.
                    return true
                }
                startStroke(event, pointerIndex = 0, isStylus = isStylus, toolType = toolType)
                return true
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                val idx = event.actionIndex
                val toolType = event.getToolType(idx)
                val isStylus = toolType == MotionEvent.TOOL_TYPE_STYLUS || toolType == MotionEvent.TOOL_TYPE_ERASER
                // If a palm touched first and then the stylus lands, cancel the palm and start stylus stroke.
                if (isStylus && !activeToolIsStylus) {
                    cancelActiveStroke()
                    startStroke(event, pointerIndex = idx, isStylus = true, toolType = toolType)
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val idx = findActivePointerIndex(event)
                if (idx < 0) return true
                val brush = activeBrush
                if (brush?.tool == ToolType.FILL) return true
                if (brush?.tool == ToolType.LINE) {
                    val start = activePoints.firstOrNull() ?: return true
                    val end = snapIfEnabled(start, extractSample(event, idx))
                    if (activePoints.size == 1) activePoints.add(end) else activePoints[1] = end
                    callbacks.onActiveStrokeUpdated(brush, activePoints, null)
                    return true
                }
                appendHistoricalAndCurrent(event, idx)
                if (isErasing) {
                    val last = activePoints.lastOrNull()
                    if (last != null) {
                        val radius = (activeBrush?.sizeWorldPx ?: 24f) * 0.55f / callbacks.currentCamera().zoom.coerceAtLeast(0.25f)
                        callbacks.onEraseSample(last.x, last.y, radius)
                    }
                } else {
                    if (brush == null) return true
                    val predicted = predictTip(event, idx)
                    callbacks.onActiveStrokeUpdated(brush, activePoints, predicted)
                }
                return true
            }

            MotionEvent.ACTION_POINTER_UP -> {
                val idx = event.actionIndex
                if (event.getPointerId(idx) == activePointerId) {
                    val canceled = isCanceledEvent(event)
                    if (canceled) {
                        cancelActiveStroke()
                    } else {
                        finishActiveStroke(event, idx)
                    }
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                val idx = findActivePointerIndex(event)
                if (idx >= 0) {
                    if (isCanceledEvent(event)) {
                        cancelActiveStroke()
                    } else {
                        finishActiveStroke(event, idx)
                    }
                }
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                cancelActiveStroke()
                return true
            }
        }
        return true
    }

    private fun startStroke(event: MotionEvent, pointerIndex: Int, isStylus: Boolean, toolType: Int) {
        activePointerId = event.getPointerId(pointerIndex)
        activeToolIsStylus = isStylus
        activePoints.clear()

        val selected = callbacks.currentBrush()
        val effectiveBrush = if (toolType == MotionEvent.TOOL_TYPE_ERASER) {
            BrushSpec.defaultFor(ToolType.ERASER)
        } else {
            selected
        }
        activeBrush = effectiveBrush
        isErasing = effectiveBrush.tool == ToolType.ERASER

        val sample = extractSample(event, pointerIndex)
        activePoints.add(sample)
        if (effectiveBrush.tool == ToolType.FILL) {
            return
        }
        if (isErasing) {
            val radius = effectiveBrush.sizeWorldPx * 0.55f / callbacks.currentCamera().zoom.coerceAtLeast(0.25f)
            callbacks.onEraseSample(sample.x, sample.y, radius)
        } else {
            callbacks.onActiveStrokeUpdated(effectiveBrush, activePoints, null)
        }
    }

    private fun finishActiveStroke(event: MotionEvent, pointerIndex: Int) {
        val brush = activeBrush
        if (brush?.tool == ToolType.FILL) {
            val tap = extractSample(event, pointerIndex)
            callbacks.onBucketFillTap(tap.x, tap.y, brush)
            resetActiveStroke()
            return
        }
        if (brush?.tool == ToolType.LINE) {
            val start = activePoints.firstOrNull()
            if (start != null) {
                val end = snapIfEnabled(start, extractSample(event, pointerIndex))
                val pts = if (hypot(end.x - start.x, end.y - start.y) < 0.5f) listOf(start) else listOf(start, end)
                callbacks.onActiveStrokeCommitted(Stroke(id = Ulid.next(), brush = brush, points = pts))
            }
            resetActiveStroke()
            return
        }
        appendHistoricalAndCurrent(event, pointerIndex)
        if (isErasing) {
            callbacks.onEraseFinished()
        } else {
            if (brush != null && activePoints.isNotEmpty()) {
                val refined = StrokeSmoother.refine(
                    rawPoints = activePoints,
                    level = callbacks.currentSmoothing(),
                    roundTurnsAndEnds = callbacks.roundTurnsAndEnds(),
                )
                val stroke = Stroke(
                    id = Ulid.next(),
                    brush = brush,
                    points = refined,
                )
                callbacks.onActiveStrokeCommitted(stroke)
            }
        }
        resetActiveStroke()
    }

    private fun snapIfEnabled(start: StrokePoint, rawEnd: StrokePoint): StrokePoint {
        if (!callbacks.snapLineAngles()) return rawEnd
        val dx = rawEnd.x - start.x
        val dy = rawEnd.y - start.y
        val len = hypot(dx, dy)
        if (len < 1f) return rawEnd
        val stepRad = (PI / 12.0).toFloat() // 15 deg increments (covers 0, 30, 45, 60, 90 for square & isometric!)
        val snappedAngle = round(atan2(dy, dx) / stepRad) * stepRad
        return rawEnd.copy(
            x = start.x + cos(snappedAngle) * len,
            y = start.y + sin(snappedAngle) * len,
        )
    }

    private fun cancelActiveStroke() {
        if (activePointerId != MotionEvent.INVALID_POINTER_ID) {
            if (isErasing) {
                callbacks.onEraseFinished()
            } else {
                callbacks.onActiveStrokeCanceled()
            }
        }
        resetActiveStroke()
    }

    private fun resetActiveStroke() {
        activePointerId = MotionEvent.INVALID_POINTER_ID
        activeToolIsStylus = false
        activeBrush = null
        activePoints.clear()
        isErasing = false
    }

    private fun handleMultiTouchNavigation(event: MotionEvent) {
        if (activePointerId != MotionEvent.INVALID_POINTER_ID) {
            cancelActiveStroke()
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (!isNavigating && event.pointerCount >= 2) {
                    navPointerA = event.getPointerId(0)
                    navPointerB = event.getPointerId(1)
                    captureNavMetrics(event)
                    isNavigating = true
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (!isNavigating && event.pointerCount >= 2) {
                    navPointerA = event.getPointerId(0)
                    navPointerB = event.getPointerId(1)
                    captureNavMetrics(event)
                    isNavigating = true
                    return
                }
                val idxA = event.findPointerIndex(navPointerA)
                val idxB = event.findPointerIndex(navPointerB)
                if (idxA < 0 || idxB < 0) return

                val ax = event.getX(idxA)
                val ay = event.getY(idxA)
                val bx = event.getX(idxB)
                val by = event.getY(idxB)

                val midX = (ax + bx) * 0.5f
                val midY = (ay + by) * 0.5f
                val span = hypot(bx - ax, by - ay).coerceAtLeast(10f)
                val angle = atan2(by - ay, bx - ax)

                val locks = callbacks.currentCameraLocks()
                if (!locks.allLocked) {
                    val panX = if (locks.lockPan) 0f else midX - lastMidX
                    val panY = if (locks.lockPan) 0f else midY - lastMidY
                    val zoomFactor = if (locks.lockZoom || lastSpan <= 10f) 1f else span / lastSpan
                    var rotDelta = if (locks.lockRotation) 0f else angle - lastAngle
                    // Normalize rotation delta to [-PI, PI]
                    while (rotDelta > Math.PI) rotDelta -= (2.0 * Math.PI).toFloat()
                    while (rotDelta < -Math.PI) rotDelta += (2.0 * Math.PI).toFloat()

                    val nextCam = callbacks.currentCamera().applyTransform(
                        pivotScreenX = midX,
                        pivotScreenY = midY,
                        panDeltaX = panX,
                        panDeltaY = panY,
                        zoomFactor = zoomFactor,
                        rotationDeltaRad = rotDelta,
                    )
                    callbacks.onCameraGesture(nextCam)
                }

                lastMidX = midX
                lastMidY = midY
                lastSpan = span
                lastAngle = angle
            }

            MotionEvent.ACTION_POINTER_UP,
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                if (event.pointerCount <= 2 || event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                    isNavigating = false
                    navPointerA = MotionEvent.INVALID_POINTER_ID
                    navPointerB = MotionEvent.INVALID_POINTER_ID
                }
            }
        }
    }

    private fun captureNavMetrics(event: MotionEvent) {
        val idxA = event.findPointerIndex(navPointerA)
        val idxB = event.findPointerIndex(navPointerB)
        if (idxA < 0 || idxB < 0) return
        val ax = event.getX(idxA)
        val ay = event.getY(idxA)
        val bx = event.getX(idxB)
        val by = event.getY(idxB)
        lastMidX = (ax + bx) * 0.5f
        lastMidY = (ay + by) * 0.5f
        lastSpan = hypot(bx - ax, by - ay).coerceAtLeast(10f)
        lastAngle = atan2(by - ay, bx - ax)
    }

    private fun appendHistoricalAndCurrent(event: MotionEvent, pointerIndex: Int) {
        val camera = callbacks.currentCamera()
        val historyCount = event.historySize
        for (h in 0 until historyCount) {
            val world = camera.screenToWorld(event.getHistoricalX(pointerIndex, h), event.getHistoricalY(pointerIndex, h))
            val point = StrokePoint(
                x = world.x,
                y = world.y,
                pressure = sanitizePressure(event.getHistoricalPressure(pointerIndex, h)),
                tilt = event.getHistoricalAxisValue(MotionEvent.AXIS_TILT, pointerIndex, h),
                orientation = event.getHistoricalAxisValue(MotionEvent.AXIS_ORIENTATION, pointerIndex, h),
                tMs = event.getHistoricalEventTime(h),
            )
            addPointIfMoved(point)
        }
        addPointIfMoved(extractSample(event, pointerIndex))
    }

    private fun extractSample(event: MotionEvent, pointerIndex: Int): StrokePoint {
        val world = callbacks.currentCamera().screenToWorld(event.getX(pointerIndex), event.getY(pointerIndex))
        return StrokePoint(
            x = world.x,
            y = world.y,
            pressure = sanitizePressure(event.getPressure(pointerIndex)),
            tilt = event.getAxisValue(MotionEvent.AXIS_TILT, pointerIndex),
            orientation = event.getAxisValue(MotionEvent.AXIS_ORIENTATION, pointerIndex),
            tMs = event.eventTime,
        )
    }

    private fun predictTip(event: MotionEvent, pointerIndex: Int): StrokePoint? {
        val predicted = predictor.predict() ?: return null
        return try {
            val pIdx = predicted.findPointerIndex(event.getPointerId(pointerIndex))
            if (pIdx < 0) return null
            val world = callbacks.currentCamera().screenToWorld(predicted.getX(pIdx), predicted.getY(pIdx))
            StrokePoint(
                x = world.x,
                y = world.y,
                pressure = sanitizePressure(predicted.getPressure(pIdx)),
                tilt = predicted.getAxisValue(MotionEvent.AXIS_TILT, pIdx),
                orientation = predicted.getAxisValue(MotionEvent.AXIS_ORIENTATION, pIdx),
                tMs = predicted.eventTime,
            )
        } finally {
            predicted.recycle()
        }
    }

    private fun addPointIfMoved(point: StrokePoint) {
        val last = activePoints.lastOrNull()
        if (last == null) {
            activePoints.add(point)
            return
        }
        val blend = callbacks.currentSmoothing().inputBlend
        val smoothed = if (blend > 0f) {
            val inv = 1f - blend
            point.copy(
                x = last.x * blend + point.x * inv,
                y = last.y * blend + point.y * inv,
                pressure = last.pressure * (blend * 0.5f) + point.pressure * (1f - blend * 0.5f),
            )
        } else {
            point
        }
        // Keep ~1.25 screen px minimum spacing at EVERY zoom level (prevents point floods when zoomed out)
        val minDist = 1.25f / callbacks.currentCamera().zoom.coerceAtLeast(0.05f)
        if (hypot(smoothed.x - last.x, smoothed.y - last.y) >= minDist) {
            activePoints.add(smoothed)
        }
    }

    private fun sanitizePressure(raw: Float): Float =
        if (raw.isFinite() && raw > 0.01f) raw.coerceIn(0.05f, 1.4f) else 0.5f

    private fun findActivePointerIndex(event: MotionEvent): Int =
        if (activePointerId == MotionEvent.INVALID_POINTER_ID) -1 else event.findPointerIndex(activePointerId)

    private fun isCanceledEvent(event: MotionEvent): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            (event.flags and MotionEvent.FLAG_CANCELED) != 0
}

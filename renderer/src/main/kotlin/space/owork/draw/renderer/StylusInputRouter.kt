package space.owork.draw.renderer

import android.os.Build
import android.view.MotionEvent
import android.view.View
import space.owork.draw.engine.BrushSpec
import space.owork.draw.engine.CameraLocks
import space.owork.draw.engine.CanvasBackground
import space.owork.draw.engine.GridSnapper
import space.owork.draw.engine.Stroke
import space.owork.draw.engine.StrokePoint
import space.owork.draw.engine.StrokeSmoother
import space.owork.draw.engine.StrokeSmoothingLevel
import space.owork.draw.engine.StylusButtonAction
import space.owork.draw.engine.ToolType
import space.owork.draw.engine.Ulid
import space.owork.draw.engine.ViewportCamera
import space.owork.draw.engine.WorldPoint
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.round
import kotlin.math.sin

/**
 * Native tablet stylus & multi-touch gesture router.
 *
 * - Stylus (`TOOL_TYPE_STYLUS` / `TOOL_TYPE_ERASER`) always draws or erases with full
 *   historical pressure, tilt, orientation, hover cursor, side-button actions, and motion prediction.
 * - 2+ fingers pan, zoom, and rotate the viewport smoothly; quick taps with 2 / 3 / 4 fingers
 *   trigger Undo, Redo, and Zen mode respectively.
 * - 1-finger press & hold engages the live eyedropper color loupe.
 * - Palm rejection: cancels active strokes cleanly on `FLAG_CANCELED` or when a palm/multi-touch
 *   takes over, never leaving stray dots on the canvas.
 * - Pressure eraser (matches Notein): with a freehand tool, keep the stylus within
 *   [PRESSURE_ERASER_SLOP_PX] of where it landed for [PRESSURE_ERASER_HOLD_MS]; once that timer
 *   fires, pressing harder than the user's threshold switches the rest of the gesture to the eraser.
 */
internal class StylusInputRouter(
    private val hostView: View,
    private val callbacks: Callbacks,
) {
    interface Callbacks {
        fun currentCamera(): ViewportCamera
        fun currentCameraLocks(): CameraLocks
        fun currentBrush(): BrushSpec
        fun currentEraserBrush(): BrushSpec
        fun currentSmoothing(): StrokeSmoothingLevel
        fun roundTurnsAndEnds(): Boolean
        fun pressureEraser(): Boolean
        /** Raw stylus pressure (0..1) that must be exceeded after the hold to engage the eraser. */
        fun pressureEraserThreshold(): Float
        fun stylusButtonAction(): StylusButtonAction
        fun multiFingerTapGestures(): Boolean
        fun longPressEyedropper(): Boolean
        fun snapLineAngles(): Boolean
        fun snapLineToGrid(): Boolean
        fun currentBackground(): CanvasBackground
        fun allowFingerDrawing(): Boolean
        fun onActiveStrokeUpdated(brush: BrushSpec, points: List<StrokePoint>, predictedPoints: List<StrokePoint>)
        fun onActiveStrokeCommitted(stroke: Stroke)
        fun onActiveStrokeCanceled()
        fun onEraseSamples(points: List<WorldPoint>, eraserRadiusWorld: Float)
        fun onEraseFinished()
        fun onBucketFillTap(worldX: Float, worldY: Float, brush: BrushSpec)
        fun onCameraGesture(newCamera: ViewportCamera)
        fun onTwoFingerTapUndo()
        fun onThreeFingerTapRedo()
        fun onFourFingerTapZen()
        fun onStylusButtonUndo()
        fun onEyedropperSample(screenX: Float, screenY: Float, worldX: Float, worldY: Float)
        fun onEyedropperFinished(commit: Boolean)
    }

    private val predictor = StrokePredictor(hostView)
    private val density: Float get() = hostView.resources.displayMetrics.density.coerceAtLeast(1f)

    private var activePointerId: Int = MotionEvent.INVALID_POINTER_ID
    private var activeToolIsStylus: Boolean = false
    private var activeBrush: BrushSpec? = null
    private val activePoints = ArrayList<StrokePoint>(128)
    private var isErasing = false
    private var lastErasePoint: WorldPoint? = null
    private val erasePathBuffer = ArrayList<WorldPoint>(16)

    // Eyedropper state (triggered by 1-finger long-press or stylus side-button hold)
    private var isEyedropping = false
    private var eyedropperPointerId = MotionEvent.INVALID_POINTER_ID
    private var eyedropperHoldArmed = false
    private var eyedropperDownX = 0f
    private var eyedropperDownY = 0f
    private val eyedropperHoldRunnable = Runnable {
        if (eyedropperHoldArmed && eyedropperPointerId != MotionEvent.INVALID_POINTER_ID && !isNavigating) {
            engageEyedropper(eyedropperDownX, eyedropperDownY)
        }
    }

    // Stylus side-button edge tracking (for UNDO action)
    private var lastSideButtonDown = false

    // Pressure eraser tracking: TIMING (pen landed, waiting out the hold) -> ARMED (hold elapsed,
    // waiting for a firm press) -> engaged (isErasing). Moving off the landing spot while TIMING aborts.
    private enum class HoldState { IDLE, TIMING, ARMED }
    private var holdState = HoldState.IDLE
    private var holdDownScreenX = 0f
    private var holdDownScreenY = 0f
    private var lastRawPressure = 0f
    private val holdRunnable = Runnable {
        if (holdState == HoldState.TIMING) {
            holdState = HoldState.ARMED
            maybeEngagePressureEraser()
        }
    }

    // Single-finger landing record so a quick 2nd finger can accurately evaluate multi-finger taps
    private var firstFingerId = MotionEvent.INVALID_POINTER_ID
    private var firstFingerDownX = 0f
    private var firstFingerDownY = 0f
    private var firstFingerDownMs = 0L

    // Multi-touch navigation & multi-finger tap tracking
    private var isNavigating = false
    private var navPointerA = MotionEvent.INVALID_POINTER_ID
    private var navPointerB = MotionEvent.INVALID_POINTER_ID
    private var lastMidX = 0f
    private var lastMidY = 0f
    private var lastSpan = 0f
    private var lastAngle = 0f
    private var multiTapPossible = false
    private var multiTapStartMs = 0L
    private var multiTapMaxFingers = 0
    private var navTransformEngaged = false
    private val multiTapDownX = HashMap<Int, Float>(6)
    private val multiTapDownY = HashMap<Int, Float>(6)

    fun onHoverEvent(event: MotionEvent): Boolean {
        val toolType = event.getToolType(0)
        val isStylus = toolType == MotionEvent.TOOL_TYPE_STYLUS || toolType == MotionEvent.TOOL_TYPE_ERASER
        if (!isStylus) return false

        val sideDown = isStylusSideButtonDown(event)
        val risingEdge = sideDown && !lastSideButtonDown
        lastSideButtonDown = sideDown
        if (risingEdge && callbacks.stylusButtonAction() == StylusButtonAction.UNDO) {
            callbacks.onStylusButtonUndo()
            return true
        }
        return false
    }

    fun onTouchEvent(event: MotionEvent): Boolean {
        val action = event.actionMasked

        // Any time 2+ finger touches appear (and stylus isn't actively drawing), route to navigation/tap
        if (event.pointerCount >= 2 && !activeToolIsStylus && !isEyedropping) {
            handleMultiTouchNavigation(event)
            return true
        }

        if (isNavigating) {
            // If a stylus lands while a palm/finger is resting, cancel nav and start stylus immediately
            if (action == MotionEvent.ACTION_POINTER_DOWN) {
                val idx = event.actionIndex
                val toolType = event.getToolType(idx)
                val isStylus = toolType == MotionEvent.TOOL_TYPE_STYLUS || toolType == MotionEvent.TOOL_TYPE_ERASER
                if (isStylus) {
                    resetNavigationState()
                    startStylusOrFingerDown(event, pointerIndex = idx, isStylus = true, toolType = toolType)
                    return true
                }
            }
            handleMultiTouchNavigation(event)
            return true
        }

        if (isEyedropping) {
            handleEyedropperTouch(event)
            return true
        }

        when (action) {
            MotionEvent.ACTION_DOWN -> {
                val toolType = event.getToolType(0)
                val isStylus = toolType == MotionEvent.TOOL_TYPE_STYLUS || toolType == MotionEvent.TOOL_TYPE_ERASER
                if (!isStylus) {
                    firstFingerId = event.getPointerId(0)
                    firstFingerDownX = event.getX(0)
                    firstFingerDownY = event.getY(0)
                    firstFingerDownMs = event.eventTime
                } else {
                    firstFingerId = MotionEvent.INVALID_POINTER_ID
                }

                if (!isStylus && !callbacks.allowFingerDrawing()) {
                    // Stylus-only mode: still allow 1-finger long-press eyedropper while waiting for 2nd finger!
                    if (callbacks.longPressEyedropper()) {
                        armEyedropperHold(event.getPointerId(0), event.getX(0), event.getY(0))
                    }
                    return true
                }
                startStylusOrFingerDown(event, pointerIndex = 0, isStylus = isStylus, toolType = toolType)
                return true
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                val idx = event.actionIndex
                val toolType = event.getToolType(idx)
                val isStylus = toolType == MotionEvent.TOOL_TYPE_STYLUS || toolType == MotionEvent.TOOL_TYPE_ERASER
                // If a palm touched first and then the stylus lands, cancel the palm and start stylus stroke.
                if (isStylus && !activeToolIsStylus) {
                    disarmEyedropperHold()
                    cancelActiveStroke()
                    startStylusOrFingerDown(event, pointerIndex = idx, isStylus = true, toolType = toolType)
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (eyedropperHoldArmed && eyedropperPointerId != MotionEvent.INVALID_POINTER_ID) {
                    val eIdx = event.findPointerIndex(eyedropperPointerId)
                    if (eIdx >= 0) {
                        val slopPx = EYEDROPPER_HOLD_SLOP_DP * density
                        if (hypot(event.getX(eIdx) - eyedropperDownX, event.getY(eIdx) - eyedropperDownY) > slopPx) {
                            disarmEyedropperHold()
                        }
                    } else {
                        disarmEyedropperHold()
                    }
                }

                val idx = findActivePointerIndex(event)
                if (idx < 0) return true
                val brush = activeBrush
                if (brush?.tool == ToolType.FILL) return true
                if (brush?.tool == ToolType.LINE) {
                    val start = activePoints.firstOrNull() ?: return true
                    val end = snapIfEnabled(start, extractSample(event, idx))
                    if (activePoints.size == 1) activePoints.add(end) else activePoints[1] = end
                    callbacks.onActiveStrokeUpdated(brush, activePoints, emptyList())
                    return true
                }
                if (holdState != HoldState.IDLE) {
                    lastRawPressure = event.getPressure(idx)
                    if (holdState == HoldState.TIMING &&
                        (abs(event.getX(idx) - holdDownScreenX) > PRESSURE_ERASER_SLOP_PX ||
                            abs(event.getY(idx) - holdDownScreenY) > PRESSURE_ERASER_SLOP_PX)
                    ) {
                        disarmPressureEraser()
                    } else {
                        maybeEngagePressureEraser()
                    }
                }
                if (isErasing) {
                    emitEraseMove(event, idx)
                } else {
                    predictor.recordMotionEvent(event)
                    val rawLast = appendHistoricalAndCurrent(event, idx, recordForPrediction = true)
                    if (brush == null) return true
                    val predicted = predictor.predictTail(
                        event = event,
                        pointerIndex = idx,
                        camera = callbacks.currentCamera(),
                        committedPoints = activePoints,
                        rawLastPoint = rawLast,
                        smoothingBlend = callbacks.currentSmoothing().inputBlend,
                    )
                    callbacks.onActiveStrokeUpdated(brush, activePoints, predicted)
                }
                return true
            }

            MotionEvent.ACTION_POINTER_UP -> {
                val idx = event.actionIndex
                val pid = event.getPointerId(idx)
                if (pid == eyedropperPointerId) disarmEyedropperHold()
                if (pid == activePointerId) {
                    val canceled = isCanceledEvent(event)
                    if (canceled) {
                        if (!isErasing) predictor.recordMotionEvent(event)
                        cancelActiveStroke()
                    } else {
                        finishActiveStroke(event, idx)
                    }
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                disarmEyedropperHold()
                firstFingerId = MotionEvent.INVALID_POINTER_ID
                val idx = findActivePointerIndex(event)
                if (idx >= 0) {
                    if (isCanceledEvent(event)) {
                        if (!isErasing) predictor.recordMotionEvent(event)
                        cancelActiveStroke()
                    } else {
                        finishActiveStroke(event, idx)
                    }
                }
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                disarmEyedropperHold()
                firstFingerId = MotionEvent.INVALID_POINTER_ID
                if (activePointerId != MotionEvent.INVALID_POINTER_ID && !isErasing) {
                    predictor.recordMotionEvent(event)
                }
                cancelActiveStroke()
                return true
            }
        }
        return true
    }

    private fun startStylusOrFingerDown(
        event: MotionEvent,
        pointerIndex: Int,
        isStylus: Boolean,
        toolType: Int,
    ) {
        val sideDown = isStylus && isStylusSideButtonDown(event)
        val risingEdge = sideDown && !lastSideButtonDown
        if (isStylus) {
            lastSideButtonDown = sideDown
        }
        val buttonAction = callbacks.stylusButtonAction()

        if (isStylus && risingEdge && buttonAction == StylusButtonAction.UNDO) {
            callbacks.onStylusButtonUndo()
            return
        }

        if (isStylus && sideDown && buttonAction == StylusButtonAction.EYEDROPPER) {
            eyedropperPointerId = event.getPointerId(pointerIndex)
            engageEyedropper(event.getX(pointerIndex), event.getY(pointerIndex))
            return
        }

        val forceEraser = toolType == MotionEvent.TOOL_TYPE_ERASER ||
            (isStylus && sideDown && buttonAction == StylusButtonAction.ERASER)

        startStroke(event, pointerIndex = pointerIndex, isStylus = isStylus, forceEraser = forceEraser)

        if (!isStylus && callbacks.longPressEyedropper() && !isErasing) {
            armEyedropperHold(event.getPointerId(pointerIndex), event.getX(pointerIndex), event.getY(pointerIndex))
        }
    }

    private fun startStroke(event: MotionEvent, pointerIndex: Int, isStylus: Boolean, forceEraser: Boolean) {
        activePointerId = event.getPointerId(pointerIndex)
        activeToolIsStylus = isStylus
        activePoints.clear()
        predictor.reset()

        val selected = callbacks.currentBrush()
        val effectiveBrush = if (forceEraser) {
            callbacks.currentEraserBrush()
        } else {
            selected
        }
        activeBrush = effectiveBrush
        isErasing = effectiveBrush.tool == ToolType.ERASER

        if (effectiveBrush.tool != ToolType.FILL && !isErasing) {
            // Deliver unbatched digitizer samples straight to front-buffered ink for lower latency and steadier prediction.
            hostView.requestUnbufferedDispatch(event)
        }

        val rawSample = extractSample(event, pointerIndex)
        val sample = if (effectiveBrush.tool == ToolType.LINE && callbacks.snapLineToGrid()) {
            GridSnapper.snapPoint(rawSample, callbacks.currentBackground())
        } else {
            rawSample
        }
        activePoints.add(sample)
        if (effectiveBrush.tool == ToolType.FILL) {
            return
        }
        if (isErasing) {
            val radius = effectiveBrush.sizeWorldPx * 0.55f / callbacks.currentCamera().zoom.coerceAtLeast(0.25f)
            val pt = WorldPoint(sample.x, sample.y)
            lastErasePoint = pt
            erasePathBuffer.clear()
            erasePathBuffer.add(pt)
            callbacks.onEraseSamples(erasePathBuffer, radius)
        } else {
            if (effectiveBrush.tool != ToolType.LINE) {
                predictor.recordMotionEvent(event)
                predictor.recordRealSample(rawSample)
            }
            callbacks.onActiveStrokeUpdated(effectiveBrush, activePoints, emptyList())
            if (isStylus && effectiveBrush.tool in PRESSURE_ERASER_TOOLS && callbacks.pressureEraser()) {
                holdState = HoldState.TIMING
                holdDownScreenX = event.getX(pointerIndex)
                holdDownScreenY = event.getY(pointerIndex)
                lastRawPressure = event.getPressure(pointerIndex)
                hostView.postDelayed(holdRunnable, PRESSURE_ERASER_HOLD_MS)
            }
        }
    }

    private fun armEyedropperHold(pointerId: Int, screenX: Float, screenY: Float) {
        disarmEyedropperHold()
        eyedropperPointerId = pointerId
        eyedropperDownX = screenX
        eyedropperDownY = screenY
        eyedropperHoldArmed = true
        hostView.postDelayed(eyedropperHoldRunnable, EYEDROPPER_HOLD_MS)
    }

    private fun disarmEyedropperHold() {
        eyedropperHoldArmed = false
        hostView.removeCallbacks(eyedropperHoldRunnable)
        if (!isEyedropping) {
            eyedropperPointerId = MotionEvent.INVALID_POINTER_ID
        }
    }

    private fun engageEyedropper(screenX: Float, screenY: Float) {
        eyedropperHoldArmed = false
        hostView.removeCallbacks(eyedropperHoldRunnable)
        // Drop any tiny finger dot started before the hold elapsed
        if (activePointerId != MotionEvent.INVALID_POINTER_ID) {
            cancelActiveStroke()
        }
        isEyedropping = true
        val world = callbacks.currentCamera().screenToWorld(screenX, screenY)
        callbacks.onEyedropperSample(screenX, screenY, world.x, world.y)
    }

    private fun handleEyedropperTouch(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                val idx = event.findPointerIndex(eyedropperPointerId).takeIf { it >= 0 } ?: 0
                val sx = event.getX(idx)
                val sy = event.getY(idx)
                val world = callbacks.currentCamera().screenToWorld(sx, sy)
                callbacks.onEyedropperSample(sx, sy, world.x, world.y)
            }

            MotionEvent.ACTION_POINTER_UP -> {
                val idx = event.actionIndex
                if (event.getPointerId(idx) == eyedropperPointerId) {
                    finishEyedropper(commit = !isCanceledEvent(event))
                }
            }

            MotionEvent.ACTION_UP -> {
                finishEyedropper(commit = !isCanceledEvent(event))
            }

            MotionEvent.ACTION_CANCEL -> {
                finishEyedropper(commit = false)
            }
        }
    }

    private fun finishEyedropper(commit: Boolean) {
        isEyedropping = false
        eyedropperHoldArmed = false
        eyedropperPointerId = MotionEvent.INVALID_POINTER_ID
        hostView.removeCallbacks(eyedropperHoldRunnable)
        callbacks.onEyedropperFinished(commit)
    }

    /** Hold elapsed and the pen is pressed firmly: drop the ink so far and erase for the rest of this gesture. */
    private fun maybeEngagePressureEraser() {
        if (holdState != HoldState.ARMED || activePointerId == MotionEvent.INVALID_POINTER_ID) return
        if (lastRawPressure <= callbacks.pressureEraserThreshold()) return
        disarmPressureEraser()
        predictor.reset()
        callbacks.onActiveStrokeCanceled()
        val anchor = activePoints.lastOrNull()
        activePoints.clear()
        val eraser = callbacks.currentEraserBrush()
        activeBrush = eraser
        isErasing = true
        if (anchor != null) {
            activePoints.add(anchor)
            val radius = eraser.sizeWorldPx * 0.55f / callbacks.currentCamera().zoom.coerceAtLeast(0.25f)
            val pt = WorldPoint(anchor.x, anchor.y)
            lastErasePoint = pt
            erasePathBuffer.clear()
            erasePathBuffer.add(pt)
            callbacks.onEraseSamples(erasePathBuffer, radius)
        }
    }

    private fun emitEraseMove(event: MotionEvent, pointerIndex: Int) {
        val camera = callbacks.currentCamera()
        erasePathBuffer.clear()
        lastErasePoint?.let { erasePathBuffer.add(it) }
        val historyCount = event.historySize
        for (h in 0 until historyCount) {
            erasePathBuffer.add(
                camera.screenToWorld(event.getHistoricalX(pointerIndex, h), event.getHistoricalY(pointerIndex, h)),
            )
        }
        val curr = camera.screenToWorld(event.getX(pointerIndex), event.getY(pointerIndex))
        erasePathBuffer.add(curr)
        lastErasePoint = curr
        val radius = (activeBrush?.sizeWorldPx ?: 24f) * 0.55f / camera.zoom.coerceAtLeast(0.25f)
        callbacks.onEraseSamples(erasePathBuffer, radius)
    }

    private fun disarmPressureEraser() {
        holdState = HoldState.IDLE
        hostView.removeCallbacks(holdRunnable)
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
        if (isErasing) {
            emitEraseMove(event, pointerIndex)
            callbacks.onEraseFinished()
        } else {
            predictor.recordMotionEvent(event)
            appendHistoricalAndCurrent(event, pointerIndex, recordForPrediction = false)
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
        if (callbacks.snapLineToGrid()) {
            return GridSnapper.snapPoint(rawEnd, callbacks.currentBackground())
        }
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
        disarmPressureEraser()
        predictor.reset()
        activePointerId = MotionEvent.INVALID_POINTER_ID
        activeToolIsStylus = false
        activeBrush = null
        activePoints.clear()
        isErasing = false
        lastErasePoint = null
        erasePathBuffer.clear()
    }

    private fun handleMultiTouchNavigation(event: MotionEvent) {
        disarmEyedropperHold()
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
                    navTransformEngaged = !callbacks.multiFingerTapGestures()
                    initMultiTapTracking(event)
                } else if (isNavigating && multiTapPossible) {
                    val idx = event.actionIndex
                    val pid = event.getPointerId(idx)
                    val tt = event.getToolType(idx)
                    if (tt == MotionEvent.TOOL_TYPE_STYLUS || tt == MotionEvent.TOOL_TYPE_ERASER) {
                        multiTapPossible = false
                    } else {
                        multiTapDownX[pid] = event.getX(idx)
                        multiTapDownY[pid] = event.getY(idx)
                        multiTapMaxFingers = max(multiTapMaxFingers, max(event.pointerCount, multiTapDownX.size))
                        if (multiTapMaxFingers > 4 || event.eventTime - multiTapStartMs > MULTI_TAP_MAX_DURATION_MS) {
                            multiTapPossible = false
                        }
                    }
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (!isNavigating && event.pointerCount >= 2) {
                    navPointerA = event.getPointerId(0)
                    navPointerB = event.getPointerId(1)
                    captureNavMetrics(event)
                    isNavigating = true
                    navTransformEngaged = !callbacks.multiFingerTapGestures()
                    initMultiTapTracking(event)
                    return
                }

                if (multiTapPossible) {
                    if (event.eventTime - multiTapStartMs > MULTI_TAP_MAX_DURATION_MS) {
                        multiTapPossible = false
                    } else {
                        val tapSlopPx = MULTI_TAP_SLOP_DP * density
                        val navStartSlopPx = NAV_ENGAGE_SLOP_DP * density
                        for (i in 0 until event.pointerCount) {
                            val pid = event.getPointerId(i)
                            val dx = event.getX(i) - (multiTapDownX[pid] ?: event.getX(i))
                            val dy = event.getY(i) - (multiTapDownY[pid] ?: event.getY(i))
                            val dist = hypot(dx, dy)
                            if (dist > navStartSlopPx) {
                                navTransformEngaged = true
                            }
                            if (dist > tapSlopPx) {
                                multiTapPossible = false
                                break
                            }
                        }
                    }
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

                if (!navTransformEngaged) {
                    // Keep anchor metrics fresh until fingers actually start dragging
                    return
                }

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

            MotionEvent.ACTION_POINTER_UP -> {
                if (isCanceledEvent(event)) {
                    multiTapPossible = false
                }
                val liftedId = event.getPointerId(event.actionIndex)
                if (liftedId == navPointerA || liftedId == navPointerB) {
                    navPointerA = MotionEvent.INVALID_POINTER_ID
                    navPointerB = MotionEvent.INVALID_POINTER_ID
                }
                // Stay in isNavigating until all fingers lift so remaining fingers don't draw stray dots
                // and so multi-finger tap fires cleanly once all fingers release.
            }

            MotionEvent.ACTION_UP -> {
                val fireTap = multiTapPossible &&
                    !isCanceledEvent(event) &&
                    (event.eventTime - multiTapStartMs) <= MULTI_TAP_MAX_DURATION_MS
                val fingers = max(multiTapMaxFingers, multiTapDownX.size)
                resetNavigationState()
                if (fireTap) {
                    when (fingers) {
                        2 -> callbacks.onTwoFingerTapUndo()
                        3 -> callbacks.onThreeFingerTapRedo()
                        4 -> callbacks.onFourFingerTapZen()
                    }
                }
            }

            MotionEvent.ACTION_CANCEL -> {
                resetNavigationState()
            }
        }
    }

    private fun initMultiTapTracking(event: MotionEvent) {
        multiTapDownX.clear()
        multiTapDownY.clear()
        if (!callbacks.multiFingerTapGestures()) {
            multiTapPossible = false
            multiTapMaxFingers = 0
            return
        }
        var allNonStylus = true
        for (i in 0 until event.pointerCount) {
            val tt = event.getToolType(i)
            if (tt == MotionEvent.TOOL_TYPE_STYLUS || tt == MotionEvent.TOOL_TYPE_ERASER) {
                allNonStylus = false
                break
            }
            val pid = event.getPointerId(i)
            if (pid == firstFingerId && event.eventTime - firstFingerDownMs <= MULTI_TAP_STAGGER_MS) {
                multiTapDownX[pid] = firstFingerDownX
                multiTapDownY[pid] = firstFingerDownY
            } else {
                multiTapDownX[pid] = event.getX(i)
                multiTapDownY[pid] = event.getY(i)
            }
        }
        multiTapPossible = allNonStylus && event.pointerCount in 2..4
        multiTapMaxFingers = event.pointerCount
        multiTapStartMs = if (firstFingerId != MotionEvent.INVALID_POINTER_ID &&
            event.eventTime - firstFingerDownMs <= MULTI_TAP_STAGGER_MS
        ) {
            firstFingerDownMs
        } else {
            event.eventTime
        }
    }

    private fun resetNavigationState() {
        isNavigating = false
        navPointerA = MotionEvent.INVALID_POINTER_ID
        navPointerB = MotionEvent.INVALID_POINTER_ID
        multiTapPossible = false
        multiTapMaxFingers = 0
        navTransformEngaged = false
        multiTapDownX.clear()
        multiTapDownY.clear()
        firstFingerId = MotionEvent.INVALID_POINTER_ID
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

    private fun appendHistoricalAndCurrent(
        event: MotionEvent,
        pointerIndex: Int,
        recordForPrediction: Boolean,
    ): StrokePoint {
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
            if (recordForPrediction) predictor.recordRealSample(point)
            addPointIfMoved(point)
        }
        val current = extractSample(event, pointerIndex)
        if (recordForPrediction) predictor.recordRealSample(current)
        addPointIfMoved(current)
        return current
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

    private fun isStylusSideButtonDown(event: MotionEvent): Boolean =
        (event.buttonState and (MotionEvent.BUTTON_STYLUS_PRIMARY or MotionEvent.BUTTON_STYLUS_SECONDARY)) != 0

    private fun isCanceledEvent(event: MotionEvent): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            (event.flags and MotionEvent.FLAG_CANCELED) != 0

    private companion object {
        // Values mirror Notein's pressure eraser.
        /** How long the stylus must rest near its landing spot before a firm press can engage the eraser. */
        const val PRESSURE_ERASER_HOLD_MS = 500L

        /** Max raw-pixel drift on either axis during the hold; moving further cancels it. */
        const val PRESSURE_ERASER_SLOP_PX = 10f
        val PRESSURE_ERASER_TOOLS = setOf(ToolType.PEN, ToolType.PENCIL, ToolType.MARKER)

        /** 1-finger press & hold duration to engage the eyedropper color loupe. */
        const val EYEDROPPER_HOLD_MS = 420L
        const val EYEDROPPER_HOLD_SLOP_DP = 10f

        /** Max duration & drift for 2/3/4-finger tap gestures (Undo / Redo / Zen). */
        const val MULTI_TAP_MAX_DURATION_MS = 450L
        const val MULTI_TAP_STAGGER_MS = 250L
        const val MULTI_TAP_SLOP_DP = 30f
        const val NAV_ENGAGE_SLOP_DP = 10f
    }
}

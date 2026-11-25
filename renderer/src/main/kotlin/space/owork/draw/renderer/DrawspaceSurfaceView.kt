package space.owork.draw.renderer

import android.annotation.SuppressLint
import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.SurfaceView
import androidx.graphics.lowlatency.BufferInfo
import androidx.graphics.lowlatency.GLFrontBufferedRenderer
import androidx.graphics.opengl.egl.EGLManager
import space.owork.draw.engine.AntiAliasingMode
import space.owork.draw.engine.AppSettings
import space.owork.draw.engine.AppTheme
import space.owork.draw.engine.BrushSpec
import space.owork.draw.engine.CameraLocks
import space.owork.draw.engine.CanvasBackground
import space.owork.draw.engine.Document
import space.owork.draw.engine.EraserMode
import space.owork.draw.engine.PartialEraser
import space.owork.draw.engine.Stroke
import space.owork.draw.engine.StrokePoint
import space.owork.draw.engine.StrokeSmoothingLevel
import space.owork.draw.engine.ViewportCamera
import space.owork.draw.engine.WorldPoint
import space.owork.draw.engine.WorldRect
import java.util.concurrent.atomic.AtomicReference

/**
 * Low-latency OpenGL ES 3.0 drawing surface built on Jetpack `GLFrontBufferedRenderer`.
 *
 * Active wet strokes render directly into the hardware front buffer (with motion prediction),
 * and committed strokes + backdrop render into the multi-buffered layer with GPU VBO caching.
 */
class DrawspaceSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : SurfaceView(context, attrs) {

    interface Listener {
        fun onStrokeCommitted(stroke: Stroke)
        fun onStrokesErased(removedStrokeIds: List<String>, replacementStrokes: List<Stroke>)
        fun onBucketFillRequested(worldX: Float, worldY: Float, brush: BrushSpec, visibleWorldBounds: WorldRect?)
        fun onCameraChanged(camera: ViewportCamera)
    }

    private val documentRef = AtomicReference<Document?>(null)
    private val pendingStrokesRef = AtomicReference<List<Stroke>>(emptyList())
    private val activePartialStrokesRef = AtomicReference<List<Stroke>>(emptyList())
    private val pendingErasedIdsRef = AtomicReference<Set<String>>(emptySet())
    private val cameraRef = AtomicReference(ViewportCamera())
    private val cameraLocksRef = AtomicReference(CameraLocks())
    private val brushRef = AtomicReference(BrushSpec.defaultFor(space.owork.draw.engine.ToolType.PEN))
    private val eraserBrushRef = AtomicReference(BrushSpec.defaultFor(space.owork.draw.engine.ToolType.ERASER))
    private val eraserModeRef = AtomicReference(EraserMode.STROKE)
    private val themeRef = AtomicReference(AppTheme.DARK)
    private val aaModeRef = AtomicReference(AntiAliasingMode.HIGH)
    private val smoothingRef = AtomicReference(StrokeSmoothingLevel.LOW)
    @Volatile
    private var roundTurnsAndEndsEnabled = true
    @Volatile
    private var pressureEraserEnabled = true
    @Volatile
    private var pressureEraserThresholdValue = AppSettings.DEFAULT_PRESSURE_ERASER_THRESHOLD
    @Volatile
    private var snapLineAnglesEnabled = false
    @Volatile
    private var snapLineToGridEnabled = false
    @Volatile
    private var hidePatternEnabled = false
    @Volatile
    private var allowFingerDraw = true
    @Volatile
    private var listener: Listener? = null

    private val erasedInGesture = LinkedHashSet<String>()
    private val partialWorkingStrokes = LinkedHashMap<String, Stroke>()
    private var gestureEraserMode: EraserMode? = null
    private var hasInitializedCameraForDocId: String? = null

    private val glEngine = DrawspaceGlEngine()
    private val liveMesh = IncrementalLiveMesh()
    private val eraserCursorRef = AtomicReference<EraserCursor?>(null)
    private val density = resources.displayMetrics.density
    private var frontBufferedRenderer: GLFrontBufferedRenderer<Unit>? = null

    private val rendererCallback = object : GLFrontBufferedRenderer.Callback<Unit> {
        override fun onDrawFrontBufferedLayer(
            eglManager: EGLManager,
            width: Int,
            height: Int,
            bufferInfo: BufferInfo,
            transform: FloatArray,
            param: Unit,
        ) {
            val doc = documentRef.get() ?: return
            glEngine.drawLiveMesh(
                bufferWidth = bufferInfo.width,
                bufferHeight = bufferInfo.height,
                bufferTransform = transform,
                camera = cameraRef.get(),
                docBounds = doc.meta.canvasMode.bounds,
                liveMesh = liveMesh,
                antiAliasing = aaModeRef.get(),
            )
            val cursor = eraserCursorRef.get() ?: return
            glEngine.drawEraserCursor(
                bufferWidth = bufferInfo.width,
                bufferHeight = bufferInfo.height,
                bufferTransform = transform,
                camera = cameraRef.get(),
                centerWorldX = cursor.worldX,
                centerWorldY = cursor.worldY,
                radiusWorld = cursor.radiusWorld,
                density = density,
            )
        }

        override fun onDrawMultiBufferedLayer(
            eglManager: EGLManager,
            width: Int,
            height: Int,
            bufferInfo: BufferInfo,
            transform: FloatArray,
            params: Collection<Unit>,
        ) {
            val doc = documentRef.get() ?: return
            val pending = pendingStrokesRef.get()
            val partial = activePartialStrokesRef.get()
            val combinedPending = when {
                partial.isEmpty() -> pending
                pending.isEmpty() -> partial
                else -> pending + partial
            }
            glEngine.drawFullScene(
                logicalWidth = width,
                logicalHeight = height,
                bufferWidth = bufferInfo.width,
                bufferHeight = bufferInfo.height,
                destFrameBufferId = bufferInfo.frameBufferId,
                bufferTransform = transform,
                document = doc,
                pendingStrokes = combinedPending,
                pendingErasedIds = pendingErasedIdsRef.get(),
                camera = cameraRef.get(),
                appTheme = themeRef.get(),
                antiAliasing = aaModeRef.get(),
                hidePattern = hidePatternEnabled,
            )
        }
    }

    private val inputRouter = StylusInputRouter(
        hostView = this,
        callbacks = object : StylusInputRouter.Callbacks {
            override fun currentCamera(): ViewportCamera = cameraRef.get()
            override fun currentCameraLocks(): CameraLocks = cameraLocksRef.get()
            override fun currentBrush(): BrushSpec = brushRef.get()
            override fun currentEraserBrush(): BrushSpec = eraserBrushRef.get()
            override fun currentSmoothing(): StrokeSmoothingLevel = smoothingRef.get()
            override fun roundTurnsAndEnds(): Boolean = roundTurnsAndEndsEnabled
            override fun pressureEraser(): Boolean = pressureEraserEnabled
            override fun pressureEraserThreshold(): Float = pressureEraserThresholdValue
            override fun snapLineAngles(): Boolean = snapLineAnglesEnabled
            override fun snapLineToGrid(): Boolean = snapLineToGridEnabled
            override fun currentBackground(): CanvasBackground = documentRef.get()?.meta?.background ?: CanvasBackground()
            override fun allowFingerDrawing(): Boolean = allowFingerDraw

            override fun onActiveStrokeUpdated(
                brush: BrushSpec,
                points: List<StrokePoint>,
                predictedPoints: List<StrokePoint>,
            ) {
                liveMesh.update(
                    brush = brush,
                    points = points,
                    predictedPoints = predictedPoints,
                    zoom = cameraRef.get().zoom,
                )
                frontBufferedRenderer?.renderFrontBufferedLayer(Unit)
            }

            override fun onActiveStrokeCommitted(stroke: Stroke) {
                liveMesh.reset()
                // Keep stroke immediately visible in multi-buffered redraw even before Compose syncs.
                pendingStrokesRef.updateAndGet { it + stroke }
                listener?.onStrokeCommitted(stroke)
                frontBufferedRenderer?.commit()
                frontBufferedRenderer?.renderMultiBufferedLayer(emptyList())
            }

            override fun onActiveStrokeCanceled() {
                liveMesh.reset()
                frontBufferedRenderer?.cancel()
            }

            override fun onEraseSamples(points: List<WorldPoint>, eraserRadiusWorld: Float) {
                if (points.isEmpty()) return
                val tip = points.last()
                eraserCursorRef.set(EraserCursor(tip.x, tip.y, eraserRadiusWorld))
                frontBufferedRenderer?.renderFrontBufferedLayer(Unit)

                val doc = documentRef.get() ?: return
                val mode = gestureEraserMode ?: eraserModeRef.get().also { gestureEraserMode = it }
                val visible = doc.visibleStrokes()
                val pending = pendingStrokesRef.get()
                val candidates = if (pending.isEmpty()) {
                    visible
                } else {
                    val seen = HashSet<String>(visible.size + pending.size)
                    val combined = ArrayList<Stroke>(visible.size + pending.size)
                    for (s in visible) {
                        if (seen.add(s.id)) combined.add(s)
                    }
                    for (s in pending) {
                        if (seen.add(s.id)) combined.add(s)
                    }
                    combined
                }

                val segmentCount = if (points.size == 1) 1 else points.lastIndex
                var changed = false

                for (segIdx in 0 until segmentCount) {
                    val p0 = points[segIdx]
                    val p1 = if (points.size == 1) p0 else points[segIdx + 1]

                    if (mode == EraserMode.STROKE) {
                        for (s in candidates) {
                            if (s.id !in erasedInGesture &&
                                s.hitTestCapsule(p0.x, p0.y, p1.x, p1.y, eraserRadiusWorld)
                            ) {
                                erasedInGesture.add(s.id)
                                changed = true
                            }
                        }
                    } else {
                        // First carve any fragments already created earlier in this gesture
                        if (partialWorkingStrokes.isNotEmpty()) {
                            val existingFragments = partialWorkingStrokes.entries.toList()
                            for ((fragId, frag) in existingFragments) {
                                val carved = PartialEraser.eraseCapsule(
                                    frag,
                                    p0.x,
                                    p0.y,
                                    p1.x,
                                    p1.y,
                                    eraserRadiusWorld,
                                )
                                if (carved != null) {
                                    partialWorkingStrokes.remove(fragId)
                                    for (nextFrag in carved) {
                                        partialWorkingStrokes[nextFrag.id] = nextFrag
                                    }
                                    changed = true
                                }
                            }
                        }
                        // Then carve any untouched original strokes
                        for (s in candidates) {
                            if (s.id in erasedInGesture) continue
                            val carved = PartialEraser.eraseCapsule(
                                s,
                                p0.x,
                                p0.y,
                                p1.x,
                                p1.y,
                                eraserRadiusWorld,
                            )
                            if (carved != null) {
                                erasedInGesture.add(s.id)
                                for (frag in carved) {
                                    partialWorkingStrokes[frag.id] = frag
                                }
                                changed = true
                            }
                        }
                    }
                }

                if (changed) {
                    // Show erased/carved result immediately on GPU, then emit 1 single undoable batch on lift.
                    val gestureSnapshot = erasedInGesture.toSet()
                    pendingErasedIdsRef.updateAndGet { it + gestureSnapshot }
                    if (mode == EraserMode.PARTIAL) {
                        activePartialStrokesRef.set(partialWorkingStrokes.values.toList())
                    }
                    requestFullRedraw()
                }
            }

            override fun onEraseFinished() {
                if (eraserCursorRef.getAndSet(null) != null) {
                    frontBufferedRenderer?.cancel()
                }
                gestureEraserMode = null
                if (erasedInGesture.isNotEmpty()) {
                    val removedIds = erasedInGesture.toList()
                    val replacements = partialWorkingStrokes.values.toList()
                    erasedInGesture.clear()
                    partialWorkingStrokes.clear()
                    if (replacements.isNotEmpty()) {
                        pendingStrokesRef.updateAndGet { it + replacements }
                    }
                    activePartialStrokesRef.set(emptyList())
                    listener?.onStrokesErased(removedIds, replacements)
                } else {
                    partialWorkingStrokes.clear()
                    activePartialStrokesRef.set(emptyList())
                }
            }

            override fun onBucketFillTap(worldX: Float, worldY: Float, brush: BrushSpec) {
                val visible = if (width > 0 && height > 0) {
                    cameraRef.get().visibleWorldBounds(width.toFloat(), height.toFloat())
                } else {
                    null
                }
                listener?.onBucketFillRequested(worldX, worldY, brush, visible)
            }

            override fun onCameraGesture(newCamera: ViewportCamera) {
                cameraRef.set(newCamera)
                listener?.onCameraChanged(newCamera)
                requestFullRedraw()
            }
        },
    )

    fun setListener(listener: Listener?) {
        this.listener = listener
    }

    fun setDocument(document: Document) {
        val current = documentRef.get()
        if (current === document) return
        documentRef.set(document)

        // Drop pending strokes/erasures that are now reflected in the authoritative Document snapshot.
        if (pendingStrokesRef.get().isNotEmpty()) {
            val committedIds = document.primaryStrokeLayer().strokes.mapTo(HashSet()) { it.id }
            pendingStrokesRef.updateAndGet { list -> list.filterNot { it.id in committedIds } }
        }
        if (pendingErasedIdsRef.get().isNotEmpty() && erasedInGesture.isEmpty()) {
            val tombstones = document.primaryStrokeLayer().tombstones
            pendingErasedIdsRef.updateAndGet { set -> set.filterNotTo(HashSet()) { it in tombstones } }
        }

        val isNewDoc = current?.id != document.id
        if (isNewDoc) {
            erasedInGesture.clear()
            partialWorkingStrokes.clear()
            gestureEraserMode = null
            pendingStrokesRef.set(emptyList())
            activePartialStrokesRef.set(emptyList())
            pendingErasedIdsRef.set(emptySet())
        }
        if (isNewDoc && width > 0 && height > 0) {
            fitDocumentToViewport(document)
        }
        requestFullRedraw()
    }

    fun setBrush(brush: BrushSpec) {
        brushRef.set(brush)
    }

    /** Eraser used by the stylus eraser end and the press-and-hold pressure eraser. */
    fun setEraserBrush(brush: BrushSpec) {
        eraserBrushRef.set(brush)
    }

    fun setEraserMode(mode: EraserMode) {
        eraserModeRef.set(mode)
    }

    fun setAppTheme(theme: AppTheme) {
        val prev = themeRef.getAndSet(theme)
        if (prev != theme) {
            requestFullRedraw()
        }
    }

    fun setAntiAliasing(mode: AntiAliasingMode) {
        val prev = aaModeRef.getAndSet(mode)
        if (prev != mode) {
            requestFullRedraw()
        }
    }

    fun setStrokeSmoothing(level: StrokeSmoothingLevel) {
        smoothingRef.set(level)
    }

    fun setRoundTurnsAndEnds(enabled: Boolean) {
        roundTurnsAndEndsEnabled = enabled
    }

    fun setPressureEraser(enabled: Boolean, threshold: Float) {
        pressureEraserEnabled = enabled
        pressureEraserThresholdValue = threshold
    }

    fun setSnapLineAngles(enabled: Boolean) {
        snapLineAnglesEnabled = enabled
    }

    fun setSnapLineToGrid(enabled: Boolean) {
        snapLineToGridEnabled = enabled
    }

    fun setHidePattern(hidden: Boolean) {
        if (hidePatternEnabled != hidden) {
            hidePatternEnabled = hidden
            requestFullRedraw()
        }
    }

    fun setAllowFingerDrawing(enabled: Boolean) {
        allowFingerDraw = enabled
    }

    fun setCameraLocks(locks: CameraLocks) {
        cameraLocksRef.set(locks)
    }

    fun resetRotation() {
        val current = cameraRef.get()
        if (current.rotationRad == 0f || width <= 0 || height <= 0) return
        val next = current.applyTransform(
            pivotScreenX = width * 0.5f,
            pivotScreenY = height * 0.5f,
            panDeltaX = 0f,
            panDeltaY = 0f,
            zoomFactor = 1f,
            rotationDeltaRad = -current.rotationRad,
        )
        cameraRef.set(next)
        listener?.onCameraChanged(next)
        requestFullRedraw()
    }

    fun fitToView() {
        val doc = documentRef.get() ?: return
        if (width <= 0 || height <= 0) return
        fitDocumentToViewport(doc)
        requestFullRedraw()
    }

    fun requestFullRedraw() {
        frontBufferedRenderer?.renderMultiBufferedLayer(emptyList())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val doc = documentRef.get()
        if (doc != null && w > 0 && h > 0) {
            if (hasInitializedCameraForDocId != doc.id) {
                fitDocumentToViewport(doc)
            }
            requestFullRedraw()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        frontBufferedRenderer = GLFrontBufferedRenderer(this, rendererCallback)
        if (documentRef.get() != null) {
            requestFullRedraw()
        }
    }

    override fun onDetachedFromWindow() {
        val renderer = frontBufferedRenderer
        frontBufferedRenderer = null
        renderer?.execute {
            glEngine.release()
        }
        renderer?.release(cancelPending = false)
        super.onDetachedFromWindow()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean = inputRouter.onTouchEvent(event)

    private fun fitDocumentToViewport(doc: Document) {
        hasInitializedCameraForDocId = doc.id
        val bounds = doc.contentBounds()
        val cam = if (bounds != null && !bounds.isEmpty) {
            ViewportCamera.fitBounds(bounds, width.toFloat(), height.toFloat(), paddingPx = 88f)
        } else {
            ViewportCamera.centeredOnOrigin(width.toFloat(), height.toFloat(), zoom = 1f)
        }
        cameraRef.set(cam)
        listener?.onCameraChanged(cam)
    }
}

private class EraserCursor(val worldX: Float, val worldY: Float, val radiusWorld: Float)

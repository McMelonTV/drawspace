package space.owork.draw.renderer

import android.opengl.GLES30
import android.opengl.Matrix
import space.owork.draw.engine.AntiAliasingMode
import space.owork.draw.engine.AppTheme
import space.owork.draw.engine.BackgroundPattern
import space.owork.draw.engine.BrushSpec
import space.owork.draw.engine.Document
import space.owork.draw.engine.Stroke
import space.owork.draw.engine.ToolType
import space.owork.draw.engine.ViewportCamera
import space.owork.draw.engine.WorldRect
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * OpenGL ES 3.0 renderer featuring:
 * - Analytical screen-pixel SDF anti-aliasing with vertex-shader sub-pixel expansion
 *   (so thin strokes stay razor-smooth and gap-free at every zoom level)
 * - Optional 1.5x supersampled offscreen FBO downsample ([AntiAliasingMode.ULTRA])
 * - Procedural anti-aliased background patterns (Blank, Dot Grid, Grid, Lined, Isometric, Crosses)
 * - Per-stroke GPU VBO caching
 */
internal class DrawspaceGlEngine {
    private var strokeProgram = 0
    private var backdropProgram = 0
    private var blitProgram = 0
    private var initialized = false
    private var maxTextureSize = 4096

    // Stroke shader uniforms
    private var uStrokeMvp = -1
    private var uStrokeColor = -1
    private var uStrokeToolMode = -1
    private var uStrokeHasBounds = -1
    private var uStrokeBounds = -1
    private var uStrokeZoom = -1
    private var uStrokeAaFeatherPx = -1

    // Backdrop shader uniforms
    private var uBackdropInvCamera = -1
    private var uBackdropPaperColor = -1
    private var uBackdropSurroundColor = -1
    private var uBackdropPatternColor = -1
    private var uBackdropHasBounds = -1
    private var uBackdropBounds = -1
    private var uBackdropZoom = -1
    private var uBackdropPatternMode = -1
    private var uBackdropSpacing = -1
    private var uBackdropOpacity = -1
    private var uBackdropShowOrigin = -1
    private var uBackdropIsDarkSurround = -1

    // SSAA downsample blit uniforms
    private var uBlitTexture = -1
    private var uBlitTexelSize = -1

    private var quadVbo = 0
    private var dynamicStrokeVbo = 0
    private var dynamicVboCapacityFloats = 0
    private var dynamicVboGeneration = -1
    private var scratchFloatBuffer: FloatBuffer = ByteBuffer.allocateDirect(8192 * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
    private val strokeCache = LinkedHashMap<String, CachedVbo>()

    // Optional supersampled offscreen FBO (allocated lazily only for ULTRA AA)
    private var ssFbo = 0
    private var ssTex = 0
    private var ssWidth = 0
    private var ssHeight = 0

    private val screenOrtho = FloatArray(16)
    private val cameraMatrix = FloatArray(16)
    private val combinedView = FloatArray(16)
    private val finalMvp = FloatArray(16)

    private class CachedVbo(
        val vboId: Int,
        val vertexCount: Int,
        val brush: BrushSpec,
        val bounds: WorldRect,
    )

    fun ensureInitialized() {
        if (initialized) return
        val maxTex = IntArray(1)
        GLES30.glGetIntegerv(GLES30.GL_MAX_TEXTURE_SIZE, maxTex, 0)
        if (maxTex[0] > 0) maxTextureSize = maxTex[0]

        strokeProgram = linkProgram(STROKE_VERTEX_SHADER, STROKE_FRAGMENT_SHADER)
        backdropProgram = linkProgram(BACKDROP_VERTEX_SHADER, BACKDROP_FRAGMENT_SHADER)
        blitProgram = linkProgram(BLIT_VERTEX_SHADER, BLIT_FRAGMENT_SHADER)

        uStrokeMvp = GLES30.glGetUniformLocation(strokeProgram, "uMvp")
        uStrokeColor = GLES30.glGetUniformLocation(strokeProgram, "uColor")
        uStrokeToolMode = GLES30.glGetUniformLocation(strokeProgram, "uToolMode")
        uStrokeHasBounds = GLES30.glGetUniformLocation(strokeProgram, "uHasBounds")
        uStrokeBounds = GLES30.glGetUniformLocation(strokeProgram, "uBounds")
        uStrokeZoom = GLES30.glGetUniformLocation(strokeProgram, "uZoom")
        uStrokeAaFeatherPx = GLES30.glGetUniformLocation(strokeProgram, "uAaFeatherPx")

        uBackdropInvCamera = GLES30.glGetUniformLocation(backdropProgram, "uInvCamera")
        uBackdropPaperColor = GLES30.glGetUniformLocation(backdropProgram, "uPaperColor")
        uBackdropSurroundColor = GLES30.glGetUniformLocation(backdropProgram, "uSurroundColor")
        uBackdropPatternColor = GLES30.glGetUniformLocation(backdropProgram, "uPatternColor")
        uBackdropHasBounds = GLES30.glGetUniformLocation(backdropProgram, "uHasBounds")
        uBackdropBounds = GLES30.glGetUniformLocation(backdropProgram, "uBounds")
        uBackdropZoom = GLES30.glGetUniformLocation(backdropProgram, "uZoom")
        uBackdropPatternMode = GLES30.glGetUniformLocation(backdropProgram, "uPatternMode")
        uBackdropSpacing = GLES30.glGetUniformLocation(backdropProgram, "uSpacing")
        uBackdropOpacity = GLES30.glGetUniformLocation(backdropProgram, "uPatternOpacity")
        uBackdropShowOrigin = GLES30.glGetUniformLocation(backdropProgram, "uShowOrigin")
        uBackdropIsDarkSurround = GLES30.glGetUniformLocation(backdropProgram, "uIsDarkSurround")

        uBlitTexture = GLES30.glGetUniformLocation(blitProgram, "uTex")
        uBlitTexelSize = GLES30.glGetUniformLocation(blitProgram, "uTexelSize")

        val vbos = IntArray(2)
        GLES30.glGenBuffers(2, vbos, 0)
        quadVbo = vbos[0]
        dynamicStrokeVbo = vbos[1]

        val quad = floatArrayOf(
            -1f, -1f,
            1f, -1f,
            -1f, 1f,
            1f, 1f,
        )
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, quadVbo)
        GLES30.glBufferData(
            GLES30.GL_ARRAY_BUFFER,
            quad.size * 4,
            asFloatBuffer(quad),
            GLES30.GL_STATIC_DRAW,
        )
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)

        initialized = true
    }

    fun drawFullScene(
        logicalWidth: Int,
        logicalHeight: Int,
        bufferWidth: Int,
        bufferHeight: Int,
        destFrameBufferId: Int,
        bufferTransform: FloatArray,
        document: Document,
        pendingStrokes: List<Stroke>,
        pendingErasedIds: Set<String>,
        camera: ViewportCamera,
        appTheme: AppTheme,
        antiAliasing: AntiAliasingMode,
    ) {
        ensureInitialized()

        val useSsaa = antiAliasing.supersampleScale > 1.01f &&
            ensureSupersampleTarget(bufferWidth, bufferHeight, antiAliasing.supersampleScale)

        if (useSsaa) {
            val scaleX = ssWidth.toFloat() / bufferWidth.toFloat()
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, ssFbo)
            renderScenePass(
                logicalWidth = logicalWidth,
                logicalHeight = logicalHeight,
                bufferWidth = bufferWidth,
                bufferHeight = bufferHeight,
                renderWidth = ssWidth,
                renderHeight = ssHeight,
                pixelScale = scaleX,
                bufferTransform = bufferTransform,
                document = document,
                pendingStrokes = pendingStrokes,
                pendingErasedIds = pendingErasedIds,
                camera = camera,
                appTheme = appTheme,
                aaFeatherPx = antiAliasing.featherPx,
            )

            // Downsample supersampled FBO into destination multi-buffered surface
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, destFrameBufferId)
            GLES30.glViewport(0, 0, bufferWidth, bufferHeight)
            GLES30.glDisable(GLES30.GL_BLEND)
            GLES30.glUseProgram(blitProgram)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, ssTex)
            GLES30.glUniform1i(uBlitTexture, 0)
            GLES30.glUniform2f(uBlitTexelSize, 1f / ssWidth.toFloat(), 1f / ssHeight.toFloat())
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, quadVbo)
            GLES30.glEnableVertexAttribArray(0)
            GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 8, 0)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
            GLES30.glDisableVertexAttribArray(0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        } else {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, destFrameBufferId)
            renderScenePass(
                logicalWidth = logicalWidth,
                logicalHeight = logicalHeight,
                bufferWidth = bufferWidth,
                bufferHeight = bufferHeight,
                renderWidth = bufferWidth,
                renderHeight = bufferHeight,
                pixelScale = 1f,
                bufferTransform = bufferTransform,
                document = document,
                pendingStrokes = pendingStrokes,
                pendingErasedIds = pendingErasedIds,
                camera = camera,
                appTheme = appTheme,
                aaFeatherPx = antiAliasing.featherPx,
            )
        }
    }

    private fun renderScenePass(
        logicalWidth: Int,
        logicalHeight: Int,
        bufferWidth: Int,
        bufferHeight: Int,
        renderWidth: Int,
        renderHeight: Int,
        pixelScale: Float,
        bufferTransform: FloatArray,
        document: Document,
        pendingStrokes: List<Stroke>,
        pendingErasedIds: Set<String>,
        camera: ViewportCamera,
        appTheme: AppTheme,
        aaFeatherPx: Float,
    ) {
        GLES30.glViewport(0, 0, renderWidth, renderHeight)
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFuncSeparate(
            GLES30.GL_SRC_ALPHA,
            GLES30.GL_ONE_MINUS_SRC_ALPHA,
            GLES30.GL_ONE,
            GLES30.GL_ONE_MINUS_SRC_ALPHA,
        )

        drawBackdrop(bufferWidth, bufferHeight, pixelScale, bufferTransform, document, camera, appTheme)

        val docStrokes = document.visibleStrokes()
        val strokes = if (pendingStrokes.isEmpty() && pendingErasedIds.isEmpty()) {
            docStrokes
        } else {
            val seen = HashSet<String>(docStrokes.size + pendingStrokes.size)
            val combined = ArrayList<Stroke>(docStrokes.size + pendingStrokes.size)
            for (s in docStrokes) {
                if (s.id !in pendingErasedIds && seen.add(s.id)) combined.add(s)
            }
            for (s in pendingStrokes) {
                if (s.id !in pendingErasedIds && seen.add(s.id)) combined.add(s)
            }
            combined
        }

        val activeIds = HashSet<String>(strokes.size * 2)
        for (s in strokes) activeIds.add(s.id)
        pruneStaleCache(activeIds)

        val mvp = computeWorldMvp(bufferWidth, bufferHeight, bufferTransform, camera)
        val visibleWorld = camera.visibleWorldBounds(logicalWidth.toFloat(), logicalHeight.toFloat())
        val docBounds = document.meta.canvasMode.bounds

        GLES30.glUseProgram(strokeProgram)
        GLES30.glUniformMatrix4fv(uStrokeMvp, 1, false, mvp, 0)
        GLES30.glUniform1f(uStrokeZoom, camera.zoom * pixelScale)
        GLES30.glUniform1f(uStrokeAaFeatherPx, aaFeatherPx)
        bindBoundsUniforms(docBounds)

        for (stroke in strokes) {
            if (stroke.brush.tool == ToolType.ERASER) continue
            if (!stroke.bounds.intersects(visibleWorld)) continue
            val cached = getOrBuildVbo(stroke)
            if (cached.vertexCount == 0) continue
            drawVbo(cached.vboId, cached.vertexCount, cached.brush)
        }

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
    }

    fun drawLiveMesh(
        bufferWidth: Int,
        bufferHeight: Int,
        bufferTransform: FloatArray,
        camera: ViewportCamera,
        docBounds: WorldRect?,
        liveMesh: IncrementalLiveMesh,
        antiAliasing: AntiAliasingMode,
    ) {
        ensureInitialized()
        GLES30.glViewport(0, 0, bufferWidth, bufferHeight)
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glClearColor(0f, 0f, 0f, 0f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)

        var activeBrush: BrushSpec? = null
        var vertexCount = 0

        liveMesh.readForGpu { brush, generation, data, totalFloats, dirtyFromFloat ->
            if (totalFloats <= 0 || brush.tool == ToolType.ERASER) return@readForGpu
            activeBrush = brush
            vertexCount = totalFloats / StrokeMeshBuilder.FLOATS_PER_VERTEX

            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, dynamicStrokeVbo)
            val needsFullUpload = generation != dynamicVboGeneration || totalFloats > dynamicVboCapacityFloats
            if (needsFullUpload) {
                var newCap = maxOf(dynamicVboCapacityFloats, 8192)
                while (newCap < totalFloats) newCap *= 2
                GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, newCap * 4, null, GLES30.GL_STREAM_DRAW)
                dynamicVboCapacityFloats = newCap
                dynamicVboGeneration = generation

                val buf = borrowScratchBuffer(data, 0, totalFloats)
                GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, 0, totalFloats * 4, buf)
            } else {
                val start = dirtyFromFloat.coerceIn(0, totalFloats)
                val count = totalFloats - start
                if (count > 0) {
                    val buf = borrowScratchBuffer(data, start, count)
                    GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, start * 4, count * 4, buf)
                }
            }
        }

        val brush = activeBrush ?: return
        if (vertexCount <= 0) return

        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFuncSeparate(
            GLES30.GL_SRC_ALPHA,
            GLES30.GL_ONE_MINUS_SRC_ALPHA,
            GLES30.GL_ONE,
            GLES30.GL_ONE_MINUS_SRC_ALPHA,
        )

        val mvp = computeWorldMvp(bufferWidth, bufferHeight, bufferTransform, camera)
        GLES30.glUseProgram(strokeProgram)
        GLES30.glUniformMatrix4fv(uStrokeMvp, 1, false, mvp, 0)
        GLES30.glUniform1f(uStrokeZoom, camera.zoom)
        GLES30.glUniform1f(uStrokeAaFeatherPx, antiAliasing.featherPx)
        bindBoundsUniforms(docBounds)

        drawVbo(dynamicStrokeVbo, vertexCount, brush)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
    }

    fun clearCache() {
        if (strokeCache.isEmpty()) return
        val ids = IntArray(strokeCache.size)
        var i = 0
        for (entry in strokeCache.values) ids[i++] = entry.vboId
        GLES30.glDeleteBuffers(ids.size, ids, 0)
        strokeCache.clear()
    }

    fun release() {
        if (!initialized) return
        clearCache()
        releaseSupersampleTarget()
        val vbos = intArrayOf(quadVbo, dynamicStrokeVbo)
        GLES30.glDeleteBuffers(2, vbos, 0)
        if (strokeProgram != 0) GLES30.glDeleteProgram(strokeProgram)
        if (backdropProgram != 0) GLES30.glDeleteProgram(backdropProgram)
        if (blitProgram != 0) GLES30.glDeleteProgram(blitProgram)
        initialized = false
    }

    private fun ensureSupersampleTarget(bufferWidth: Int, bufferHeight: Int, scale: Float): Boolean {
        val targetW = min(maxTextureSize, (bufferWidth * scale).roundToInt())
        val targetH = min(maxTextureSize, (bufferHeight * scale).roundToInt())
        if (targetW <= bufferWidth || targetH <= bufferHeight) return false
        if (ssFbo != 0 && ssWidth == targetW && ssHeight == targetH) return true

        releaseSupersampleTarget()
        val texIds = IntArray(1)
        val fboIds = IntArray(1)
        GLES30.glGenTextures(1, texIds, 0)
        GLES30.glGenFramebuffers(1, fboIds, 0)

        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texIds[0])
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D,
            0,
            GLES30.GL_RGBA8,
            targetW,
            targetH,
            0,
            GLES30.GL_RGBA,
            GLES30.GL_UNSIGNED_BYTE,
            null,
        )
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fboIds[0])
        GLES30.glFramebufferTexture2D(
            GLES30.GL_FRAMEBUFFER,
            GLES30.GL_COLOR_ATTACHMENT0,
            GLES30.GL_TEXTURE_2D,
            texIds[0],
            0,
        )
        val status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)

        if (status != GLES30.GL_FRAMEBUFFER_COMPLETE) {
            GLES30.glDeleteTextures(1, texIds, 0)
            GLES30.glDeleteFramebuffers(1, fboIds, 0)
            return false
        }
        ssTex = texIds[0]
        ssFbo = fboIds[0]
        ssWidth = targetW
        ssHeight = targetH
        return true
    }

    private fun releaseSupersampleTarget() {
        if (ssTex != 0) {
            GLES30.glDeleteTextures(1, intArrayOf(ssTex), 0)
            ssTex = 0
        }
        if (ssFbo != 0) {
            GLES30.glDeleteFramebuffers(1, intArrayOf(ssFbo), 0)
            ssFbo = 0
        }
        ssWidth = 0
        ssHeight = 0
    }

    private fun drawBackdrop(
        bufferWidth: Int,
        bufferHeight: Int,
        pixelScale: Float,
        bufferTransform: FloatArray,
        document: Document,
        camera: ViewportCamera,
        appTheme: AppTheme,
    ) {
        val bg = document.meta.background
        val effectiveTheme = bg.effectiveTheme(appTheme)
        val paperArgb = bg.resolvePaperArgb(appTheme)
        val surroundArgb = bg.resolveSurroundArgb(appTheme)
        val patternArgb = bg.resolvePatternArgb(appTheme)

        GLES30.glUseProgram(backdropProgram)
        val invCamera = computeScreenToWorldMatrix(bufferWidth, bufferHeight, bufferTransform, camera)
        GLES30.glUniformMatrix4fv(uBackdropInvCamera, 1, false, invCamera, 0)
        setColorUniform(uBackdropPaperColor, paperArgb)
        setColorUniform(uBackdropSurroundColor, surroundArgb)
        setColorUniform(uBackdropPatternColor, patternArgb)

        val bounds = document.meta.canvasMode.bounds
        if (bounds != null) {
            GLES30.glUniform1f(uBackdropHasBounds, 1f)
            GLES30.glUniform4f(uBackdropBounds, bounds.left, bounds.top, bounds.right, bounds.bottom)
        } else {
            GLES30.glUniform1f(uBackdropHasBounds, 0f)
            GLES30.glUniform4f(uBackdropBounds, 0f, 0f, 0f, 0f)
        }
        GLES30.glUniform1f(uBackdropZoom, camera.zoom * pixelScale)
        val patternMode = when (bg.pattern) {
            BackgroundPattern.BLANK -> 0f
            BackgroundPattern.DOT_GRID -> 1f
            BackgroundPattern.SQUARE_GRID -> 2f
            BackgroundPattern.LINED -> 3f
            BackgroundPattern.ISOMETRIC -> 4f
            BackgroundPattern.CROSS_GRID -> 5f
        }
        GLES30.glUniform1f(uBackdropPatternMode, patternMode)
        GLES30.glUniform1f(uBackdropSpacing, bg.spacingPx.coerceIn(12f, 240f))
        GLES30.glUniform1f(uBackdropOpacity, bg.opacity.coerceIn(0f, 1f))
        GLES30.glUniform1f(uBackdropShowOrigin, if (bg.showOriginMarker && bounds == null) 1f else 0f)
        GLES30.glUniform1f(uBackdropIsDarkSurround, if (effectiveTheme == AppTheme.LIGHT) 0f else 1f)

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, quadVbo)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 8, 0)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glDisableVertexAttribArray(0)
    }

    private fun drawVbo(vboId: Int, vertexCount: Int, brush: BrushSpec) {
        setColorUniform(uStrokeColor, brush.colorArgb)
        val mode = when (brush.tool) {
            ToolType.PEN, ToolType.LINE -> 0f
            ToolType.PENCIL -> 1f
            ToolType.MARKER -> 2f
            ToolType.ERASER -> 0f
        }
        GLES30.glUniform1f(uStrokeToolMode, mode)

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboId)
        val stride = StrokeMeshBuilder.FLOATS_PER_VERTEX * 4
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, stride, 0)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, stride, 8)
        GLES30.glEnableVertexAttribArray(2)
        GLES30.glVertexAttribPointer(2, 2, GLES30.GL_FLOAT, false, stride, 16)
        GLES30.glEnableVertexAttribArray(3)
        GLES30.glVertexAttribPointer(3, 2, GLES30.GL_FLOAT, false, stride, 24)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, vertexCount)
        GLES30.glDisableVertexAttribArray(0)
        GLES30.glDisableVertexAttribArray(1)
        GLES30.glDisableVertexAttribArray(2)
        GLES30.glDisableVertexAttribArray(3)
    }

    private fun getOrBuildVbo(stroke: Stroke): CachedVbo {
        strokeCache[stroke.id]?.let { return it }
        val vertices = StrokeMeshBuilder.buildRibbon(stroke)
        val ids = IntArray(1)
        GLES30.glGenBuffers(1, ids, 0)
        val vboId = ids[0]
        if (vertices.isNotEmpty()) {
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboId)
            GLES30.glBufferData(
                GLES30.GL_ARRAY_BUFFER,
                vertices.size * 4,
                borrowScratchBuffer(vertices, 0, vertices.size),
                GLES30.GL_STATIC_DRAW,
            )
        }
        val entry = CachedVbo(
            vboId = vboId,
            vertexCount = vertices.size / StrokeMeshBuilder.FLOATS_PER_VERTEX,
            brush = stroke.brush,
            bounds = stroke.bounds,
        )
        strokeCache[stroke.id] = entry
        return entry
    }

    private fun pruneStaleCache(activeIds: Set<String>) {
        val it = strokeCache.entries.iterator()
        while (it.hasNext()) {
            val (id, entry) = it.next()
            if (id !in activeIds) {
                GLES30.glDeleteBuffers(1, intArrayOf(entry.vboId), 0)
                it.remove()
            }
        }
    }

    private fun bindBoundsUniforms(bounds: WorldRect?) {
        if (bounds != null) {
            GLES30.glUniform1f(uStrokeHasBounds, 1f)
            GLES30.glUniform4f(uStrokeBounds, bounds.left, bounds.top, bounds.right, bounds.bottom)
        } else {
            GLES30.glUniform1f(uStrokeHasBounds, 0f)
            GLES30.glUniform4f(uStrokeBounds, 0f, 0f, 0f, 0f)
        }
    }

    private fun computeWorldMvp(
        bufferWidth: Int,
        bufferHeight: Int,
        bufferTransform: FloatArray,
        camera: ViewportCamera,
    ): FloatArray {
        Matrix.orthoM(screenOrtho, 0, 0f, bufferWidth.toFloat(), 0f, bufferHeight.toFloat(), -1f, 1f)
        Matrix.multiplyMM(combinedView, 0, screenOrtho, 0, bufferTransform, 0)
        buildCameraMatrix(camera, cameraMatrix)
        Matrix.multiplyMM(finalMvp, 0, combinedView, 0, cameraMatrix, 0)
        return finalMvp
    }

    private fun computeScreenToWorldMatrix(
        bufferWidth: Int,
        bufferHeight: Int,
        bufferTransform: FloatArray,
        camera: ViewportCamera,
    ): FloatArray {
        val worldToClip = computeWorldMvp(bufferWidth, bufferHeight, bufferTransform, camera)
        val inv = FloatArray(16)
        Matrix.invertM(inv, 0, worldToClip, 0)
        return inv
    }

    private fun buildCameraMatrix(camera: ViewportCamera, out: FloatArray) {
        val c = cos(camera.rotationRad) * camera.zoom
        val s = sin(camera.rotationRad) * camera.zoom
        Matrix.setIdentityM(out, 0)
        out[0] = c
        out[1] = s
        out[4] = -s
        out[5] = c
        out[12] = camera.offsetX
        out[13] = camera.offsetY
    }

    private fun setColorUniform(location: Int, argb: Int) {
        val r = ((argb ushr 16) and 0xFF) / 255f
        val g = ((argb ushr 8) and 0xFF) / 255f
        val b = (argb and 0xFF) / 255f
        GLES30.glUniform3f(location, r, g, b)
    }

    private fun borrowScratchBuffer(data: FloatArray, offset: Int, count: Int): FloatBuffer {
        if (scratchFloatBuffer.capacity() < count) {
            var newCap = scratchFloatBuffer.capacity().coerceAtLeast(4096)
            while (newCap < count) newCap *= 2
            scratchFloatBuffer = ByteBuffer.allocateDirect(newCap * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
        }
        scratchFloatBuffer.clear()
        scratchFloatBuffer.put(data, offset, count)
        scratchFloatBuffer.flip()
        return scratchFloatBuffer
    }

    private fun asFloatBuffer(data: FloatArray): FloatBuffer =
        borrowScratchBuffer(data, 0, data.size)

    private fun linkProgram(vertSrc: String, fragSrc: String): Int {
        val v = compileShader(GLES30.GL_VERTEX_SHADER, vertSrc)
        val f = compileShader(GLES30.GL_FRAGMENT_SHADER, fragSrc)
        val prog = GLES30.glCreateProgram()
        GLES30.glAttachShader(prog, v)
        GLES30.glAttachShader(prog, f)
        GLES30.glLinkProgram(prog)
        val status = IntArray(1)
        GLES30.glGetProgramiv(prog, GLES30.GL_LINK_STATUS, status, 0)
        check(status[0] == GLES30.GL_TRUE) {
            "GL program link failed: ${GLES30.glGetProgramInfoLog(prog)}"
        }
        GLES30.glDeleteShader(v)
        GLES30.glDeleteShader(f)
        return prog
    }

    private fun compileShader(type: Int, src: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, src)
        GLES30.glCompileShader(shader)
        val status = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
        check(status[0] == GLES30.GL_TRUE) {
            "GL shader compile failed: ${GLES30.glGetShaderInfoLog(shader)}"
        }
        return shader
    }

    companion object {
        private val STROKE_VERTEX_SHADER = """
            #version 300 es
            precision highp float;
            layout(location = 0) in vec2 aCenterPos;
            layout(location = 1) in vec2 aOffsetDir;
            layout(location = 2) in vec2 aEdgeCap;
            layout(location = 3) in vec2 aMeta; // x = halfWidthWorld, y = alpha
            uniform mat4 uMvp;
            uniform float uZoom;
            uniform float uAaFeatherPx;
            out vec2 vWorldPos;
            out vec2 vDistPx;
            out float vHalfWidthPx;
            out float vAlpha;
            void main() {
                float zoomSafe = max(uZoom, 0.01);
                float rawHalfPx = aMeta.x * zoomSafe;
                float minHalfPx = 0.65;
                float effectiveHalfPx = max(rawHalfPx, minHalfPx);
                // Preserve perceived thinness via coverage alpha when lines shrink below 1.3px
                float thinCoverage = clamp(rawHalfPx / minHalfPx, 0.25, 1.0);
                float outerRadiusPx = effectiveHalfPx + uAaFeatherPx;
                float outerRadiusWorld = outerRadiusPx / zoomSafe;

                vec2 worldPos = aCenterPos + aOffsetDir * outerRadiusWorld;
                vWorldPos = worldPos;
                vDistPx = aEdgeCap * outerRadiusPx;
                vHalfWidthPx = effectiveHalfPx;
                vAlpha = aMeta.y * thinCoverage;
                gl_Position = uMvp * vec4(worldPos, 0.0, 1.0);
            }
        """.trimIndent()

        private val STROKE_FRAGMENT_SHADER = """
            #version 300 es
            precision highp float;
            in vec2 vWorldPos;
            in vec2 vDistPx;
            in float vHalfWidthPx;
            in float vAlpha;
            uniform vec3 uColor;
            uniform float uToolMode; // 0 = pen, 1 = pencil, 2 = marker
            uniform float uHasBounds;
            uniform vec4 uBounds;
            uniform float uAaFeatherPx;
            out vec4 fragColor;

            float paperNoise(vec2 p) {
                vec2 i = floor(p);
                vec2 f = fract(p);
                vec2 u = f * f * (3.0 - 2.0 * f);
                float a = fract(sin(dot(i, vec2(127.1, 311.7))) * 43758.5453);
                float b = fract(sin(dot(i + vec2(1.0, 0.0), vec2(127.1, 311.7))) * 43758.5453);
                float c = fract(sin(dot(i + vec2(0.0, 1.0), vec2(127.1, 311.7))) * 43758.5453);
                float d = fract(sin(dot(i + vec2(1.0, 1.0), vec2(127.1, 311.7))) * 43758.5453);
                return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
            }

            void main() {
                if (uHasBounds > 0.5) {
                    if (vWorldPos.x < uBounds.x || vWorldPos.x > uBounds.z ||
                        vWorldPos.y < uBounds.y || vWorldPos.y > uBounds.w) {
                        discard;
                    }
                }

                float dPx = (uToolMode >= 1.5)
                    ? max(abs(vDistPx.x), abs(vDistPx.y))
                    : length(vDistPx);

                float edgeMask;
                if (uAaFeatherPx <= 0.01) {
                    edgeMask = step(dPx, vHalfWidthPx);
                } else {
                    float feather = max(uAaFeatherPx * 0.68, 0.6);
                    edgeMask = 1.0 - smoothstep(vHalfWidthPx - feather, vHalfWidthPx + feather, dPx);
                }

                float alpha = vAlpha * edgeMask;

                if (uToolMode > 0.5 && uToolMode < 1.5) {
                    float n1 = paperNoise(vWorldPos * 0.45);
                    float n2 = paperNoise(vWorldPos * 1.35);
                    float grain = mix(0.55, 1.05, n1 * 0.65 + n2 * 0.35);
                    float normD = dPx / max(vHalfWidthPx, 0.5);
                    float softEdge = 1.0 - smoothstep(0.60, 1.05, normD);
                    alpha = clamp(alpha * grain * mix(0.65, 1.0, softEdge), 0.0, 1.0);
                }

                if (alpha <= 0.004) discard;
                fragColor = vec4(uColor, alpha);
            }
        """.trimIndent()

        private val BACKDROP_VERTEX_SHADER = """
            #version 300 es
            precision highp float;
            layout(location = 0) in vec2 aClipPos;
            out vec2 vClipPos;
            void main() {
                vClipPos = aClipPos;
                gl_Position = vec4(aClipPos, 0.0, 1.0);
            }
        """.trimIndent()

        private val BACKDROP_FRAGMENT_SHADER = """
            #version 300 es
            precision highp float;
            in vec2 vClipPos;
            uniform mat4 uInvCamera;
            uniform vec3 uPaperColor;
            uniform vec3 uSurroundColor;
            uniform vec3 uPatternColor;
            uniform float uHasBounds;
            uniform vec4 uBounds;
            uniform float uZoom;
            uniform float uPatternMode; // 0=blank, 1=dots, 2=grid, 3=lined, 4=isometric, 5=crosses
            uniform float uSpacing;
            uniform float uPatternOpacity;
            uniform float uShowOrigin;
            uniform float uIsDarkSurround;
            out vec4 fragColor;

            float aaWidth(float px) {
                return px / max(uZoom, 0.05);
            }

            float dotGrid(vec2 worldPos, float spacing, float dotRadiusWorld) {
                vec2 cell = mod(worldPos + spacing * 0.5, spacing) - spacing * 0.5;
                float dist = length(cell);
                float aa = max(fwidth(dist), aaWidth(0.65));
                return 1.0 - smoothstep(dotRadiusWorld - aa, dotRadiusWorld + aa, dist);
            }

            float lineDist(float coord, float spacing) {
                return abs(mod(coord + spacing * 0.5, spacing) - spacing * 0.5);
            }

            float squareGrid(vec2 w, float spacing) {
                float dx = lineDist(w.x, spacing);
                float dy = lineDist(w.y, spacing);
                float d = min(dx, dy);
                float halfW = aaWidth(0.55);
                float aa = max(fwidth(d), aaWidth(0.5));
                float minor = (1.0 - smoothstep(halfW, halfW + aa, d)) * 0.55;

                float majorSpacing = spacing * 5.0;
                float md = min(lineDist(w.x, majorSpacing), lineDist(w.y, majorSpacing));
                float majorHalfW = aaWidth(0.85);
                float major = 1.0 - smoothstep(majorHalfW, majorHalfW + aa, md);
                return max(minor, major);
            }

            float linedPattern(vec2 w, float spacing) {
                float dy = lineDist(w.y, spacing);
                float halfW = aaWidth(0.6);
                float aa = max(fwidth(dy), aaWidth(0.5));
                return 1.0 - smoothstep(halfW, halfW + aa, dy);
            }

            float isometricGrid(vec2 w, float spacing) {
                vec2 n1 = vec2(1.0, 0.0);
                vec2 n2 = vec2(0.5, 0.8660254);
                vec2 n3 = vec2(-0.5, 0.8660254);
                float d1 = lineDist(dot(w, n1), spacing);
                float d2 = lineDist(dot(w, n2), spacing);
                float d3 = lineDist(dot(w, n3), spacing);
                float d = min(d1, min(d2, d3));
                float halfW = aaWidth(0.55);
                float aa = max(fwidth(d), aaWidth(0.5));
                return 1.0 - smoothstep(halfW, halfW + aa, d);
            }

            float crossGrid(vec2 w, float spacing) {
                vec2 cell = mod(w + spacing * 0.5, spacing) - spacing * 0.5;
                vec2 a = abs(cell);
                float armLen = min(spacing * 0.14, aaWidth(6.5));
                float thick = aaWidth(0.75);
                float aa = aaWidth(0.55);
                float hBar = (1.0 - smoothstep(thick, thick + aa, a.y)) * (1.0 - smoothstep(armLen, armLen + aa, a.x));
                float vBar = (1.0 - smoothstep(thick, thick + aa, a.x)) * (1.0 - smoothstep(armLen, armLen + aa, a.y));
                return max(hBar, vBar);
            }

            float computePattern(vec2 w) {
                float fineFade = smoothstep(0.18, 0.52, uZoom);
                if (uPatternMode < 0.5) {
                    return 0.0;
                } else if (uPatternMode < 1.5) {
                    float fine = dotGrid(w, uSpacing, aaWidth(1.5)) * fineFade * 0.72;
                    float major = dotGrid(w, uSpacing * 5.0, aaWidth(2.1));
                    return max(fine, major);
                } else if (uPatternMode < 2.5) {
                    return squareGrid(w, uSpacing) * mix(0.35, 1.0, fineFade);
                } else if (uPatternMode < 3.5) {
                    return linedPattern(w, uSpacing) * mix(0.35, 1.0, fineFade);
                } else if (uPatternMode < 4.5) {
                    return isometricGrid(w, uSpacing) * mix(0.35, 1.0, fineFade);
                } else {
                    float fine = crossGrid(w, uSpacing) * fineFade;
                    float major = crossGrid(w, uSpacing * 5.0) * (1.0 - fineFade * 0.5);
                    return max(fine, major);
                }
            }

            void main() {
                vec4 world4 = uInvCamera * vec4(vClipPos, 0.0, 1.0);
                vec2 w = world4.xy / world4.w;

                if (uHasBounds > 0.5) {
                    bool inside = w.x >= uBounds.x && w.x <= uBounds.z && w.y >= uBounds.y && w.y <= uBounds.w;
                    if (inside) {
                        float pat = computePattern(w) * uPatternOpacity;
                        vec3 col = mix(uPaperColor, uPatternColor, clamp(pat, 0.0, 0.9));
                        fragColor = vec4(col, 1.0);
                    } else {
                        vec2 clamped = clamp(w, uBounds.xy, uBounds.zw);
                        float distPx = length(w - clamped) * uZoom;
                        float border = (1.0 - smoothstep(0.8, 1.8, distPx)) * 0.35;
                        vec3 borderColor = uIsDarkSurround > 0.5 ? vec3(0.45, 0.5, 0.6) : vec3(0.55, 0.58, 0.64);
                        float shadow = exp(-distPx * 0.065) * (uIsDarkSurround > 0.5 ? 0.35 : 0.14);
                        vec3 col = mix(uSurroundColor, vec3(0.0, 0.0, 0.0), shadow);
                        col = mix(col, borderColor, border);
                        fragColor = vec4(col, 1.0);
                    }
                } else {
                    float pat = computePattern(w) * uPatternOpacity;
                    float originAmt = 0.0;
                    if (uShowOrigin > 0.5) {
                        float originDist = length(w) * uZoom;
                        originAmt = (1.0 - smoothstep(3.5, 5.2, abs(originDist - 8.0))) * 0.22;
                    }
                    float mixAmt = clamp(max(pat, originAmt), 0.0, 0.9);
                    fragColor = vec4(mix(uPaperColor, uPatternColor, mixAmt), 1.0);
                }
            }
        """.trimIndent()

        private val BLIT_VERTEX_SHADER = """
            #version 300 es
            precision highp float;
            layout(location = 0) in vec2 aClipPos;
            out vec2 vUv;
            void main() {
                vUv = aClipPos * 0.5 + 0.5;
                gl_Position = vec4(aClipPos, 0.0, 1.0);
            }
        """.trimIndent()

        private val BLIT_FRAGMENT_SHADER = """
            #version 300 es
            precision highp float;
            in vec2 vUv;
            uniform sampler2D uTex;
            uniform vec2 uTexelSize;
            out vec4 fragColor;
            void main() {
                // 4-tap rotated-grid downsample for smooth SSAA resolve
                vec2 o1 = vec2(0.375, 0.125) * uTexelSize;
                vec2 o2 = vec2(-0.125, 0.375) * uTexelSize;
                vec4 c = (
                    texture(uTex, vUv + o1) +
                    texture(uTex, vUv - o1) +
                    texture(uTex, vUv + o2) +
                    texture(uTex, vUv - o2)
                ) * 0.25;
                fragColor = c;
            }
        """.trimIndent()
    }
}

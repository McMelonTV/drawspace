package space.owork.draw.renderer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import space.owork.draw.engine.AppTheme
import space.owork.draw.engine.Document
import space.owork.draw.engine.Layer
import space.owork.draw.engine.Stroke
import space.owork.draw.engine.ToolType
import space.owork.draw.engine.WorldRect
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Offscreen PNG exporter that works on any background thread without contending for the live GL context.
 */
object DocumentPngExporter {

    fun exportPng(
        document: Document,
        outputFile: File,
        theme: AppTheme = AppTheme.LIGHT,
        maxInfiniteDimensionPx: Int = 2560,
    ): File {
        val fixedBounds = document.meta.canvasMode.bounds
        val exportBounds: WorldRect
        val outWidth: Int
        val outHeight: Int
        val scale: Float

        if (fixedBounds != null) {
            exportBounds = fixedBounds
            outWidth = fixedBounds.width.roundToInt().coerceAtLeast(1)
            outHeight = fixedBounds.height.roundToInt().coerceAtLeast(1)
            scale = 1f
        } else {
            val content = document.contentBounds()?.expanded(64f) ?: WorldRect(-600f, -400f, 600f, 400f)
            exportBounds = content
            val maxDim = max(content.width, content.height).coerceAtLeast(1f)
            scale = (maxInfiniteDimensionPx / maxDim).coerceAtMost(2f)
            outWidth = (content.width * scale).roundToInt().coerceIn(256, maxInfiniteDimensionPx)
            outHeight = (content.height * scale).roundToInt().coerceIn(256, maxInfiniteDimensionPx)
        }

        val bitmap = Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(document.meta.background.resolvePaperArgb(theme))
            canvas.scale(scale, scale)
            canvas.translate(-exportBounds.left, -exportBounds.top)

            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeJoin = Paint.Join.ROUND
            }

            for (layer in document.layers) {
                if (!layer.visible || layer !is Layer.StrokeLayer) continue
                for (stroke in layer.activeStrokes) {
                    if (stroke.brush.tool != ToolType.FILL || stroke.points.isEmpty()) continue
                    drawStroke(canvas, paint, stroke, layer.opacity)
                }
                for (stroke in layer.activeStrokes) {
                    if (stroke.brush.tool == ToolType.ERASER || stroke.brush.tool == ToolType.FILL || stroke.points.isEmpty()) continue
                    drawStroke(canvas, paint, stroke, layer.opacity)
                }
            }

            outputFile.parentFile?.mkdirs()
            FileOutputStream(outputFile).use { fos ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos)
                fos.flush()
            }
            return outputFile
        } finally {
            bitmap.recycle()
        }
    }

    private fun drawStroke(canvas: Canvas, paint: Paint, stroke: Stroke, layerOpacity: Float) {
        val brush = stroke.brush
        val alphaInt = (brush.opacity * layerOpacity * 255f).roundToInt().coerceIn(0, 255)
        paint.color = (brush.colorArgb and 0x00FFFFFF) or (alphaInt shl 24)

        val pts = stroke.points
        if (brush.tool == ToolType.FILL) {
            paint.style = Paint.Style.FILL
            val fillPath = Path()
            var i = 0
            while (i + 1 < pts.size) {
                fillPath.addRect(pts[i].x, pts[i].y, pts[i + 1].x, pts[i + 1].y, Path.Direction.CW)
                i += 2
            }
            canvas.drawPath(fillPath, paint)
            paint.style = Paint.Style.STROKE
            return
        }

        paint.strokeCap = if (brush.tool == ToolType.MARKER) Paint.Cap.SQUARE else Paint.Cap.ROUND
        if (pts.size == 1) {
            paint.style = Paint.Style.FILL
            canvas.drawCircle(pts[0].x, pts[0].y, brush.sizeWorldPx * 0.5f, paint)
            paint.style = Paint.Style.STROKE
            return
        }

        val path = Path()
        path.moveTo(pts[0].x, pts[0].y)
        if (pts.size == 2) {
            path.lineTo(pts[1].x, pts[1].y)
        } else {
            for (i in 1 until pts.lastIndex) {
                val c = pts[i]
                val n = pts[i + 1]
                path.quadTo(c.x, c.y, (c.x + n.x) * 0.5f, (c.y + n.y) * 0.5f)
            }
            path.lineTo(pts.last().x, pts.last().y)
        }
        val avgPressure = pts.map { it.pressure }.average().toFloat().coerceIn(0.1f, 1.4f)
        val factor = 1f - brush.pressureSizeSensitivity + brush.pressureSizeSensitivity * avgPressure
        paint.strokeWidth = max(brush.sizeWorldPx * factor, 1f)
        canvas.drawPath(path, paint)
    }
}

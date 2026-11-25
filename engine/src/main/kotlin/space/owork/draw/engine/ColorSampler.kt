package space.owork.draw.engine

import kotlin.math.roundToInt

/**
 * Pure-Kotlin eyedropper color sampler that composites visible fills and strokes at a world point
 * over the resolved canvas background, avoiding GPU readback stalls while matching render order.
 */
object ColorSampler {

    fun sampleColor(
        document: Document,
        worldX: Float,
        worldY: Float,
        appTheme: AppTheme,
        extraRadiusWorld: Float = 0f,
    ): Int {
        val fixedBounds = document.meta.canvasMode.bounds
        val insideArtboard = fixedBounds == null || fixedBounds.contains(worldX, worldY)
        val baseArgb = if (insideArtboard) {
            document.meta.background.resolvePaperArgb(appTheme)
        } else {
            document.meta.background.resolveSurroundArgb(appTheme)
        }

        if (!insideArtboard) {
            return baseArgb or (0xFF shl 24)
        }

        var r = ((baseArgb ushr 16) and 0xFF) / 255f
        var g = ((baseArgb ushr 8) and 0xFF) / 255f
        var b = (baseArgb and 0xFF) / 255f

        val radius = extraRadiusWorld.coerceAtLeast(0f)

        for (layer in document.layers) {
            if (!layer.visible || layer !is Layer.StrokeLayer) continue
            val layerOpacity = layer.opacity.coerceIn(0f, 1f)
            if (layerOpacity <= 0f) continue

            // Pass 1: bucket fills render behind line strokes (matches DrawspaceGlEngine)
            for (stroke in layer.activeStrokes) {
                if (stroke.brush.tool != ToolType.FILL) continue
                if (!stroke.hitTest(worldX, worldY, radius)) continue
                val a = (stroke.brush.opacity * layerOpacity).coerceIn(0f, 1f)
                val sr = ((stroke.brush.colorArgb ushr 16) and 0xFF) / 255f
                val sg = ((stroke.brush.colorArgb ushr 8) and 0xFF) / 255f
                val sb = (stroke.brush.colorArgb and 0xFF) / 255f
                r = sr * a + r * (1f - a)
                g = sg * a + g * (1f - a)
                b = sb * a + b * (1f - a)
            }

            // Pass 2: brush & line strokes render on top
            for (stroke in layer.activeStrokes) {
                if (stroke.brush.tool == ToolType.ERASER || stroke.brush.tool == ToolType.FILL) continue
                if (!stroke.hitTest(worldX, worldY, radius)) continue
                val a = (stroke.brush.opacity * layerOpacity).coerceIn(0f, 1f)
                val sr = ((stroke.brush.colorArgb ushr 16) and 0xFF) / 255f
                val sg = ((stroke.brush.colorArgb ushr 8) and 0xFF) / 255f
                val sb = (stroke.brush.colorArgb and 0xFF) / 255f
                r = sr * a + r * (1f - a)
                g = sg * a + g * (1f - a)
                b = sb * a + b * (1f - a)
            }
        }

        val ri = (r * 255f).roundToInt().coerceIn(0, 255)
        val gi = (g * 255f).roundToInt().coerceIn(0, 255)
        val bi = (b * 255f).roundToInt().coerceIn(0, 255)
        return (0xFF shl 24) or (ri shl 16) or (gi shl 8) or bi
    }
}

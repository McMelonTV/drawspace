package space.owork.draw.engine

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Generates GitHub-renderable vector previews (`preview.svg`) and navigation Markdown
 * (`docs/<id>/README.md` + workspace root `README.md`) directly from the pure-Kotlin
 * [Document] model. Zero Android or GPU dependencies.
 */
object DocumentPreviewGenerator {

    fun generateSvg(
        document: Document,
        theme: AppTheme = AppTheme.LIGHT,
        maxDimensionPx: Int = 1200,
    ): ByteArray {
        val bounds = resolvePreviewBounds(document)
        val width = bounds.width.coerceAtLeast(1f)
        val height = bounds.height.coerceAtLeast(1f)
        val scale = maxDimensionPx.toFloat() / max(width, height)
        val outW = (width * scale).roundToInt().coerceIn(64, maxDimensionPx)
        val outH = (height * scale).roundToInt().coerceIn(64, maxDimensionPx)
        val paperArgb = document.meta.background.resolvePaperArgb(theme)

        val sb = StringBuilder(4096)
        sb.append(
            """<svg xmlns="http://www.w3.org/2000/svg" viewBox="${fmt(bounds.left)} ${fmt(bounds.top)} ${fmt(width)} ${fmt(height)}" width="$outW" height="$outH">""",
        ).append('\n')
        sb.append(
            """  <rect x="${fmt(bounds.left)}" y="${fmt(bounds.top)}" width="${fmt(width)}" height="${fmt(height)}" fill="${argbToHex(paperArgb)}"/>""",
        ).append('\n')

        val fixedBounds = document.meta.canvasMode.bounds
        if (fixedBounds != null) {
            sb.append(
                """  <defs><clipPath id="artboard"><rect x="${fmt(fixedBounds.left)}" y="${fmt(fixedBounds.top)}" width="${fmt(fixedBounds.width)}" height="${fmt(fixedBounds.height)}"/></clipPath></defs>""",
            ).append('\n')
            sb.append("""  <g clip-path="url(#artboard)">""").append('\n')
        } else {
            sb.append("  <g>").append('\n')
        }

        for (layer in document.layers) {
            if (!layer.visible || layer !is Layer.StrokeLayer) continue
            val layerOpacity = layer.opacity.coerceIn(0f, 1f)
            sb.append("""    <g id="${escapeXml(layer.id)}" opacity="${fmt3(layerOpacity)}">""").append('\n')
            for (stroke in layer.activeStrokes) {
                if (stroke.points.isEmpty() || stroke.brush.tool != ToolType.FILL) continue
                appendStrokeSvg(sb, stroke)
            }
            for (stroke in layer.activeStrokes) {
                if (stroke.points.isEmpty() || stroke.brush.tool == ToolType.ERASER || stroke.brush.tool == ToolType.FILL) continue
                appendStrokeSvg(sb, stroke)
            }
            sb.append("    </g>\n")
        }

        sb.append("  </g>\n</svg>\n")
        return sb.toString().encodeToByteArray()
    }

    fun generateDocumentReadme(document: Document): ByteArray {
        val meta = document.meta
        val modeLabel = when (val mode = meta.canvasMode) {
            CanvasMode.Infinite -> "Infinite Canvas"
            is CanvasMode.Fixed -> "${mode.widthPx} × ${mode.heightPx} px (${mode.dpi} DPI)"
        }
        val strokeCount = document.visibleStrokes().size
        val updatedIso = formatIsoUtc(meta.updatedAtMs)
        val layerRows = document.layers.joinToString("\n") { layer ->
            val kind = when (layer) {
                is Layer.StrokeLayer -> "Stroke (${layer.activeStrokes.size} strokes)"
                is Layer.RasterLayer -> "Raster (${layer.tiles.size} tiles)"
            }
            "| `${layer.name}` | $kind | ${(layer.opacity * 100).roundToInt()}% | ${if (layer.visible) "Yes" else "Hidden"} |"
        }

        val md = buildString {
            appendLine("# ${meta.title}")
            appendLine()
            appendLine("![${meta.title}](./preview.svg)")
            appendLine()
            appendLine("| Property | Value |")
            appendLine("| --- | --- |")
            appendLine("| **Canvas** | $modeLabel |")
            appendLine("| **Strokes** | $strokeCount |")
            appendLine("| **Updated** | `$updatedIso` |")
            appendLine("| **Document ID** | `${meta.id}` |")
            appendLine()
            appendLine("## Layers")
            appendLine()
            appendLine("| Name | Type | Opacity | Visible |")
            appendLine("| --- | --- | --- | --- |")
            appendLine(layerRows)
            appendLine()
            appendLine("_Generated automatically by Drawspace._")
        }
        return md.encodeToByteArray()
    }

    fun generateWorkspaceReadme(documents: List<DocumentMeta>): ByteArray {
        val sorted = documents.sortedByDescending { it.updatedAtMs }
        val md = buildString {
            appendLine("# Drawspace Workspace")
            appendLine()
            if (sorted.isEmpty()) {
                appendLine("_No drawings yet._")
            } else {
                appendLine("| Preview | Document | Canvas | Updated |")
                appendLine("| --- | --- | --- | --- |")
                for (doc in sorted) {
                    val mode = when (val m = doc.canvasMode) {
                        CanvasMode.Infinite -> "Infinite"
                        is CanvasMode.Fixed -> "${m.widthPx}×${m.heightPx}"
                    }
                    val safeTitle = doc.title.replace("|", "\\|")
                    appendLine(
                        "| <img src=\"./docs/${doc.id}/preview.svg\" width=\"160\" alt=\"$safeTitle\"/> " +
                            "| [**$safeTitle**](./docs/${doc.id}/README.md) " +
                            "| `$mode` " +
                            "| `${formatIsoUtc(doc.updatedAtMs)}` |",
                    )
                }
            }
            appendLine()
            appendLine("_Auto-indexed by Drawspace Git Storage._")
        }
        return md.encodeToByteArray()
    }

    private fun appendStrokeSvg(sb: StringBuilder, stroke: Stroke) {
        val color = argbToHex(stroke.brush.colorArgb)
        val opacity = stroke.brush.opacity.coerceIn(0f, 1f)
        val pts = stroke.points
        if (stroke.brush.tool == ToolType.FILL) {
            sb.append("      <g fill=\"").append(color).append("\" fill-opacity=\"")
            appendFixed3(sb, opacity)
            sb.append("\">\n")
            var i = 0
            while (i + 1 < pts.size) {
                val tl = pts[i]
                val br = pts[i + 1]
                val w = (br.x - tl.x).coerceAtLeast(0f)
                val h = (br.y - tl.y).coerceAtLeast(0f)
                sb.append("        <rect x=\"")
                appendFixed2(sb, tl.x)
                sb.append("\" y=\"")
                appendFixed2(sb, tl.y)
                sb.append("\" width=\"")
                appendFixed2(sb, w)
                sb.append("\" height=\"")
                appendFixed2(sb, h)
                sb.append("\"/>\n")
                i += 2
            }
            sb.append("      </g>\n")
            return
        }
        if (pts.size == 1) {
            val p = pts[0]
            val r = (strokeWidthAt(stroke.brush, p) * 0.5f).coerceAtLeast(0.5f)
            sb.append("      <circle cx=\"")
            appendFixed2(sb, p.x)
            sb.append("\" cy=\"")
            appendFixed2(sb, p.y)
            sb.append("\" r=\"")
            appendFixed2(sb, r)
            sb.append("\" fill=\"").append(color).append("\" fill-opacity=\"")
            appendFixed3(sb, opacity)
            sb.append("\"/>\n")
            return
        }

        // Compute average effective stroke width so GitHub SVG stays compact and fast to parse.
        var widthSum = 0f
        for (p in pts) widthSum += strokeWidthAt(stroke.brush, p)
        val avgWidth = (widthSum / pts.size).coerceAtLeast(0.75f)
        val cap = if (stroke.brush.tool == ToolType.MARKER) "square" else "round"

        sb.append("      <path d=\"M ")
        appendFixed2(sb, pts[0].x)
        sb.append(' ')
        appendFixed2(sb, pts[0].y)
        if (pts.size == 2) {
            sb.append(" L ")
            appendFixed2(sb, pts[1].x)
            sb.append(' ')
            appendFixed2(sb, pts[1].y)
        } else {
            for (i in 1 until pts.lastIndex) {
                val curr = pts[i]
                val next = pts[i + 1]
                val midX = (curr.x + next.x) * 0.5f
                val midY = (curr.y + next.y) * 0.5f
                sb.append(" Q ")
                appendFixed2(sb, curr.x)
                sb.append(' ')
                appendFixed2(sb, curr.y)
                sb.append(' ')
                appendFixed2(sb, midX)
                sb.append(' ')
                appendFixed2(sb, midY)
            }
            val last = pts.last()
            sb.append(" L ")
            appendFixed2(sb, last.x)
            sb.append(' ')
            appendFixed2(sb, last.y)
        }
        sb.append("\" fill=\"none\" stroke=\"").append(color).append("\" stroke-opacity=\"")
        appendFixed3(sb, opacity)
        sb.append("\" stroke-width=\"")
        appendFixed2(sb, avgWidth)
        sb.append("\" stroke-linecap=\"").append(cap).append("\" stroke-linejoin=\"round\"/>\n")
    }

    private fun strokeWidthAt(brush: BrushSpec, p: StrokePoint): Float {
        val pressureFactor = 1f - brush.pressureSizeSensitivity + brush.pressureSizeSensitivity * p.pressure.coerceIn(0.05f, 1.5f)
        val tiltFactor = 1f + brush.tiltSensitivity * (p.tilt / 1.5708f).coerceIn(0f, 1f)
        return (brush.sizeWorldPx * pressureFactor * tiltFactor).coerceAtLeast(0.75f)
    }

    private fun resolvePreviewBounds(document: Document): WorldRect {
        document.meta.canvasMode.bounds?.let { return it }
        val content = document.contentBounds()
        if (content != null && !content.isEmpty) {
            val pad = max(max(content.width, content.height) * 0.08f, 48f)
            return content.expanded(pad)
        }
        return WorldRect(-400f, -300f, 400f, 300f)
    }

    private fun argbToHex(argb: Int): String = "#%06X".format(argb and 0xFFFFFF)

    private fun fmt(v: Float): String = buildString(10) { appendFixed2(this, v) }

    private fun fmt3(v: Float): String = buildString(8) { appendFixed3(this, v) }

    /** Zero-allocation 2-decimal formatter (~30x faster than String.format in tight stroke loops). */
    private fun appendFixed2(sb: StringBuilder, v: Float) {
        val scaled = (v * 100f).roundToInt()
        if (scaled < 0) sb.append('-')
        val abs = kotlin.math.abs(scaled)
        val whole = abs / 100
        val frac = abs % 100
        sb.append(whole)
        if (frac != 0) {
            sb.append('.')
            if (frac < 10) sb.append('0')
            sb.append(frac)
        }
    }

    private fun appendFixed3(sb: StringBuilder, v: Float) {
        val scaled = (v * 1000f).roundToInt().coerceIn(0, 1000)
        val whole = scaled / 1000
        val frac = scaled % 1000
        sb.append(whole).append('.')
        if (frac < 100) sb.append('0')
        if (frac < 10) sb.append('0')
        sb.append(frac)
    }

    private fun escapeXml(s: String): String =
        s.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;")

    private fun formatIsoUtc(epochMs: Long): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm 'UTC'", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.format(Date(epochMs))
    }
}

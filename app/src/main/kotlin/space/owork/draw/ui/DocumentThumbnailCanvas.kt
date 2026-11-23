package space.owork.draw.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke as ComposeStroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.unit.dp
import space.owork.draw.engine.AppTheme
import space.owork.draw.engine.BackgroundPattern
import space.owork.draw.engine.ToolType
import space.owork.draw.engine.WorldRect
import space.owork.draw.storage.DocumentSummary
import kotlin.math.max
import kotlin.math.min

/**
 * Fast, theme-aware vector thumbnail renderer shared between Workspace gallery cards
 * and Version History timeline previews.
 */
@Composable
fun DocumentThumbnailCanvas(
    summary: DocumentSummary,
    appTheme: AppTheme,
    modifier: Modifier = Modifier,
    highlightStrokeIds: Set<String> = emptySet(),
    highlightColor: Color = Color(0xFF4FC3F7),
    fixedArtboardPaddingDp: Float = 12f,
    showPattern: Boolean = true,
) {
    val bg = summary.meta.background
    val paperColor = Color(bg.resolvePaperArgb(appTheme))
    val surroundColor = Color(bg.resolveSurroundArgb(appTheme))
    val patternColor = Color(bg.resolvePatternArgb(appTheme)).copy(alpha = (bg.opacity * 0.85f).coerceIn(0.08f, 0.55f))
    val fixedBounds = summary.meta.canvasMode.bounds

    Canvas(modifier = modifier) {
        val canvasW = size.width
        val canvasH = size.height
        if (canvasW <= 1f || canvasH <= 1f) return@Canvas

        val paperLeft: Float
        val paperTop: Float
        val paperWidth: Float
        val paperHeight: Float
        val worldBounds: WorldRect
        val scale: Float
        val originX: Float
        val originY: Float

        if (fixedBounds != null && !fixedBounds.isEmpty) {
            drawRect(color = surroundColor)
            val pad = fixedArtboardPaddingDp.dp.toPx()
            val availW = (canvasW - pad * 2f).coerceAtLeast(12f)
            val availH = (canvasH - pad * 2f).coerceAtLeast(12f)
            scale = min(availW / fixedBounds.width, availH / fixedBounds.height)
            paperWidth = fixedBounds.width * scale
            paperHeight = fixedBounds.height * scale
            paperLeft = (canvasW - paperWidth) * 0.5f
            paperTop = (canvasH - paperHeight) * 0.5f
            worldBounds = fixedBounds
            originX = paperLeft - fixedBounds.left * scale
            originY = paperTop - fixedBounds.top * scale

            drawRect(
                color = paperColor,
                topLeft = Offset(paperLeft, paperTop),
                size = Size(paperWidth, paperHeight),
            )
            drawRect(
                color = patternColor.copy(alpha = 0.45f),
                topLeft = Offset(paperLeft, paperTop),
                size = Size(paperWidth, paperHeight),
                style = ComposeStroke(width = 1.dp.toPx()),
            )
        } else {
            paperLeft = 0f
            paperTop = 0f
            paperWidth = canvasW
            paperHeight = canvasH
            drawRect(color = paperColor)

            val content = summary.contentBounds
            if (content != null && !content.isEmpty) {
                val margin = max(max(content.width, content.height) * 0.10f, 32f)
                worldBounds = content.expanded(margin)
            } else {
                worldBounds = WorldRect(-400f, -240f, 400f, 240f)
            }
            scale = min(canvasW / worldBounds.width, canvasH / worldBounds.height)
            originX = canvasW * 0.5f - worldBounds.centerX * scale
            originY = canvasH * 0.5f - worldBounds.centerY * scale
        }

        clipRect(
            left = paperLeft,
            top = paperTop,
            right = paperLeft + paperWidth,
            bottom = paperTop + paperHeight,
        ) {
            if (showPattern) {
                drawThumbnailPattern(
                    pattern = bg.pattern,
                    left = paperLeft,
                    top = paperTop,
                    width = paperWidth,
                    height = paperHeight,
                    color = patternColor,
                )
            }

            val path = Path()
            for (stroke in summary.previewStrokes) {
                if (stroke.brush.tool == ToolType.ERASER || stroke.points.isEmpty()) continue
                val pts = stroke.points
                val isHighlighted = stroke.id in highlightStrokeIds
                val strokeColor = if (isHighlighted) {
                    highlightColor
                } else {
                    Color(stroke.brush.colorArgb).copy(alpha = stroke.brush.opacity.coerceIn(0.08f, 1f))
                }
                if (stroke.brush.tool == ToolType.FILL) {
                    var i = 0
                    while (i + 1 < pts.size) {
                        val l = originX + pts[i].x * scale
                        val t = originY + pts[i].y * scale
                        val r = originX + pts[i + 1].x * scale
                        val b = originY + pts[i + 1].y * scale
                        drawRect(
                            color = strokeColor,
                            topLeft = Offset(l, t),
                            size = Size((r - l).coerceAtLeast(0.5f), (b - t).coerceAtLeast(0.5f)),
                        )
                        i += 2
                    }
                    continue
                }
                val strokeW = (stroke.brush.sizeWorldPx * scale * if (isHighlighted) 1.35f else 1f).coerceIn(1.1f, 16f)

                if (pts.size == 1) {
                    val x = originX + pts[0].x * scale
                    val y = originY + pts[0].y * scale
                    drawCircle(color = strokeColor, radius = max(strokeW * 0.5f, 1f), center = Offset(x, y))
                    continue
                }

                path.reset()
                path.moveTo(originX + pts[0].x * scale, originY + pts[0].y * scale)
                if (pts.size == 2) {
                    path.lineTo(originX + pts[1].x * scale, originY + pts[1].y * scale)
                } else {
                    val step = if (pts.size > 160) pts.size / 80 else 1
                    var i = step
                    while (i < pts.lastIndex) {
                        val c = pts[i]
                        val nextIdx = (i + step).coerceAtMost(pts.lastIndex)
                        val n = pts[nextIdx]
                        val cx = originX + c.x * scale
                        val cy = originY + c.y * scale
                        val mx = originX + ((c.x + n.x) * 0.5f) * scale
                        val my = originY + ((c.y + n.y) * 0.5f) * scale
                        path.quadraticTo(cx, cy, mx, my)
                        i += step
                    }
                    val last = pts.last()
                    path.lineTo(originX + last.x * scale, originY + last.y * scale)
                }

                drawPath(
                    path = path,
                    color = strokeColor,
                    style = ComposeStroke(
                        width = strokeW,
                        cap = if (stroke.brush.tool == ToolType.MARKER) StrokeCap.Square else StrokeCap.Round,
                        join = StrokeJoin.Round,
                    ),
                )
            }
        }
    }
}

private fun DrawScope.drawThumbnailPattern(
    pattern: BackgroundPattern,
    left: Float,
    top: Float,
    width: Float,
    height: Float,
    color: Color,
) {
    if (pattern == BackgroundPattern.BLANK) return
    val step = 18.dp.toPx()
    when (pattern) {
        BackgroundPattern.DOT_GRID -> {
            var y = top + step * 0.5f
            while (y < top + height) {
                var x = left + step * 0.5f
                while (x < left + width) {
                    drawCircle(color = color, radius = 1.dp.toPx(), center = Offset(x, y))
                    x += step
                }
                y += step
            }
        }

        BackgroundPattern.SQUARE_GRID -> {
            var x = left + step
            while (x < left + width) {
                drawLine(color, Offset(x, top), Offset(x, top + height), strokeWidth = 0.7.dp.toPx())
                x += step
            }
            var y = top + step
            while (y < top + height) {
                drawLine(color, Offset(left, y), Offset(left + width, y), strokeWidth = 0.7.dp.toPx())
                y += step
            }
        }

        BackgroundPattern.LINED -> {
            var y = top + step
            while (y < top + height) {
                drawLine(color, Offset(left, y), Offset(left + width, y), strokeWidth = 0.75.dp.toPx())
                y += step
            }
        }

        BackgroundPattern.ISOMETRIC -> {
            val diagStep = step * 1.15f
            var x = left - height
            while (x < left + width + height) {
                drawLine(color, Offset(x, top), Offset(x + height * 0.577f, top + height), strokeWidth = 0.65.dp.toPx())
                drawLine(color, Offset(x, top), Offset(x - height * 0.577f, top + height), strokeWidth = 0.65.dp.toPx())
                x += diagStep
            }
        }

        BackgroundPattern.CROSS_GRID -> {
            val arm = 2.5.dp.toPx()
            var y = top + step * 0.5f
            while (y < top + height) {
                var x = left + step * 0.5f
                while (x < left + width) {
                    drawLine(color, Offset(x - arm, y), Offset(x + arm, y), strokeWidth = 0.75.dp.toPx())
                    drawLine(color, Offset(x, y - arm), Offset(x, y + arm), strokeWidth = 0.75.dp.toPx())
                    x += step
                }
                y += step
            }
        }

        BackgroundPattern.BLANK -> Unit
    }
}

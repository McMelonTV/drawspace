package space.owork.draw.ui

import android.graphics.Color as AndroidColor
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import space.owork.draw.engine.ColorPalette
import space.owork.draw.engine.ColorSwatch
import space.owork.draw.ui.theme.LocalGlass

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ColorPickerSheet(
    initialColorArgb: Int,
    palette: ColorPalette,
    onDismiss: () -> Unit,
    onSelectColor: (Int) -> Unit,
    onAddSwatch: (Int) -> Unit,
    onRemoveSwatch: (Int) -> Unit,
    onResetPalette: () -> Unit,
) {
    val p = LocalGlass.current
    val initialHsv = remember(initialColorArgb) {
        FloatArray(3).also { AndroidColor.colorToHSV(initialColorArgb, it) }
    }
    var hue by remember(initialColorArgb) { mutableFloatStateOf(initialHsv[0]) }
    var sat by remember(initialColorArgb) { mutableFloatStateOf(initialHsv[1]) }
    var value by remember(initialColorArgb) { mutableFloatStateOf(initialHsv[2]) }
    var hexDraft by remember(initialColorArgb) {
        mutableStateOf("#%06X".format(initialColorArgb and 0xFFFFFF))
    }
    var editMode by remember { mutableStateOf(false) }

    val currentArgb = AndroidColor.HSVToColor(0xFF, floatArrayOf(hue, sat, value))

    fun applyHsv(h: Float, s: Float, v: Float) {
        hue = h.coerceIn(0f, 360f)
        sat = s.coerceIn(0f, 1f)
        value = v.coerceIn(0f, 1f)
        val argb = AndroidColor.HSVToColor(0xFF, floatArrayOf(hue, sat, value))
        hexDraft = "#%06X".format(argb and 0xFFFFFF)
        onSelectColor(argb)
    }

    GlassModal(
        title = "Color & Palette",
        onDismiss = onDismiss,
        maxWidth = 580.dp,
        maxHeight = 620.dp,
        headerActions = {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(Color(currentArgb))
                    .border(2.dp, p.swatchBorder, CircleShape),
            )
            GlassButton(
                label = "Save Swatch",
                icon = Icons.Outlined.Add,
                height = 34.dp,
                onClick = { onAddSwatch(currentArgb) },
            )
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Column(
                    modifier = Modifier.weight(1.25f),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    SvPickerBox(
                        hue = hue,
                        saturation = sat,
                        value = value,
                        onChange = { s, v -> applyHsv(hue, s, v) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(172.dp),
                    )
                    HueSliderBar(
                        hue = hue,
                        onHueChange = { h -> applyHsv(h, sat, value) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(26.dp),
                    )
                }

                Column(
                    modifier = Modifier.weight(0.75f),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    GlassSectionLabel("Hex")
                    GlassTextField(
                        value = hexDraft,
                        onValueChange = { raw ->
                            hexDraft = raw.uppercase()
                            val clean = raw.trim().removePrefix("#")
                            if (clean.length == 6) {
                                clean.toLongOrNull(16)?.let { rgb ->
                                    val argb = (0xFF000000L or rgb).toInt()
                                    val hsv = FloatArray(3)
                                    AndroidColor.colorToHSV(argb, hsv)
                                    hue = hsv[0]
                                    sat = hsv[1]
                                    value = hsv[2]
                                    onSelectColor(argb)
                                }
                            }
                        },
                        placeholder = "#FFFFFF",
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Before", color = p.secondary, fontSize = 11.sp)
                            Spacer(Modifier.height(4.dp))
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(36.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Color(initialColorArgb))
                                    .border(1.dp, p.swatchBorder, RoundedCornerShape(10.dp))
                                    .clickable {
                                        val hsv = FloatArray(3)
                                        AndroidColor.colorToHSV(initialColorArgb, hsv)
                                        applyHsv(hsv[0], hsv[1], hsv[2])
                                    },
                            )
                        }
                        Column(Modifier.weight(1f)) {
                            Text("New", color = p.secondary, fontSize = 11.sp)
                            Spacer(Modifier.height(4.dp))
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(36.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Color(currentArgb))
                                    .border(1.dp, p.swatchBorder, RoundedCornerShape(10.dp)),
                            )
                        }
                    }
                }
            }

            GlassDivider()

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GlassSectionLabel("Workspace Palette · ${palette.swatches.size}")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlassChip(
                        label = if (editMode) "Done" else "Edit",
                        selected = editMode,
                        onClick = { editMode = !editMode },
                    )
                    GlassChip(
                        label = "Reset",
                        icon = Icons.Outlined.RestartAlt,
                        selected = false,
                        onClick = onResetPalette,
                    )
                }
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                palette.swatches.forEachIndexed { idx, swatch: ColorSwatch ->
                    val isSelected = swatch.argb == currentArgb
                    Box(contentAlignment = Alignment.TopEnd) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(Color(swatch.argb))
                                .border(
                                    width = if (isSelected) 3.dp else 1.dp,
                                    color = if (isSelected) p.active else p.swatchBorder,
                                    shape = CircleShape,
                                )
                                .clickable {
                                    if (editMode) {
                                        onRemoveSwatch(idx)
                                    } else {
                                        val hsv = FloatArray(3)
                                        AndroidColor.colorToHSV(swatch.argb, hsv)
                                        applyHsv(hsv[0], hsv[1], hsv[2])
                                    }
                                },
                        )
                        if (editMode && palette.swatches.size > 1) {
                            Box(
                                modifier = Modifier
                                    .size(16.dp)
                                    .clip(CircleShape)
                                    .background(p.destructive),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    Icons.Outlined.Close,
                                    contentDescription = "Remove swatch",
                                    tint = Color.White,
                                    modifier = Modifier.size(11.dp),
                                )
                            }
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                GlassButton(label = "Done", primary = true, onClick = onDismiss)
            }
        }
    }
}

@Composable
private fun SvPickerBox(
    hue: Float,
    saturation: Float,
    value: Float,
    onChange: (Float, Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = LocalGlass.current
    val hueColor = remember(hue) {
        Color(AndroidColor.HSVToColor(0xFF, floatArrayOf(hue, 1f, 1f)))
    }
    Canvas(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .border(1.dp, p.separator, RoundedCornerShape(14.dp))
            .pointerInput(hue) {
                detectTapGestures { offset ->
                    val s = (offset.x / size.width).coerceIn(0f, 1f)
                    val v = (1f - offset.y / size.height).coerceIn(0f, 1f)
                    onChange(s, v)
                }
            }
            .pointerInput(hue) {
                detectDragGestures { change, _ ->
                    change.consume()
                    val s = (change.position.x / size.width).coerceIn(0f, 1f)
                    val v = (1f - change.position.y / size.height).coerceIn(0f, 1f)
                    onChange(s, v)
                }
            },
    ) {
        drawRect(brush = Brush.horizontalGradient(listOf(Color.White, hueColor)))
        drawRect(brush = Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))

        val cx = saturation * size.width
        val cy = (1f - value) * size.height
        drawCircle(
            color = Color.Black.copy(alpha = 0.6f),
            radius = 10.dp.toPx(),
            center = Offset(cx, cy),
            style = Stroke(width = 3.dp.toPx()),
        )
        drawCircle(
            color = Color.White,
            radius = 8.5.dp.toPx(),
            center = Offset(cx, cy),
            style = Stroke(width = 2.dp.toPx()),
        )
    }
}

@Composable
private fun HueSliderBar(
    hue: Float,
    onHueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = LocalGlass.current
    val rainbow = remember {
        listOf(
            Color(0xFFFF0000),
            Color(0xFFFFFF00),
            Color(0xFF00FF00),
            Color(0xFF00FFFF),
            Color(0xFF0000FF),
            Color(0xFFFF00FF),
            Color(0xFFFF0000),
        )
    }
    Canvas(
        modifier = modifier
            .clip(CircleShape)
            .border(1.dp, p.separator, CircleShape)
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    onHueChange((offset.x / size.width).coerceIn(0f, 1f) * 360f)
                }
            }
            .pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    change.consume()
                    onHueChange((change.position.x / size.width).coerceIn(0f, 1f) * 360f)
                }
            },
    ) {
        drawRoundRect(
            brush = Brush.horizontalGradient(rainbow),
            cornerRadius = CornerRadius(size.height * 0.5f, size.height * 0.5f),
        )
        val cx = (hue / 360f).coerceIn(0f, 1f) * size.width
        drawCircle(
            color = Color.Black.copy(alpha = 0.55f),
            radius = size.height * 0.44f,
            center = Offset(cx, size.height * 0.5f),
            style = Stroke(width = 3.dp.toPx()),
        )
        drawCircle(
            color = Color.White,
            radius = size.height * 0.38f,
            center = Offset(cx, size.height * 0.5f),
            style = Stroke(width = 2.dp.toPx()),
        )
    }
}

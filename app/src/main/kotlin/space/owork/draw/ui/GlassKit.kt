package space.owork.draw.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import space.owork.draw.ui.theme.LocalGlass
import space.owork.draw.ui.theme.glass
import space.owork.draw.ui.theme.glassSurface

/**
 * Floating translucent glass capsule/panel used for canvas HUD toolbars.
 */
@Composable
fun GlassPill(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(22.dp),
    content: @Composable () -> Unit,
) {
    val p = LocalGlass.current
    Box(
        modifier = modifier
            .shadow(elevation = if (p.isDark) 20.dp else 12.dp, shape = shape, clip = false)
            .glass(shape = shape, palette = p, fill = p.pillMaterial),
    ) {
        content()
    }
}

/**
 * Concentric frosted-glass tablet modal inspired by tvOS/iPadOS glass sheets.
 */
@Composable
fun GlassModal(
    title: String,
    subtitle: String? = null,
    onDismiss: () -> Unit,
    maxWidth: Dp = 540.dp,
    maxHeight: Dp = 640.dp,
    headerActions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val p = LocalGlass.current
    val outerShape = RoundedCornerShape(26.dp)
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 24.dp, vertical = 20.dp)
                .widthIn(min = 340.dp, max = maxWidth)
                .heightIn(max = maxHeight)
                .fillMaxWidth()
                .shadow(32.dp, outerShape, clip = false)
                .glass(outerShape, p, fill = p.material),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 22.dp, end = 16.dp, top = 18.dp, bottom = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        color = p.text,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = (-0.25).sp,
                    )
                    if (subtitle != null) {
                        Text(
                            text = subtitle,
                            color = p.secondary,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    headerActions()
                    GlassIconButton(
                        icon = Icons.Outlined.Close,
                        contentDescription = "Close",
                        size = 34.dp,
                        onClick = onDismiss,
                    )
                }
            }
            GlassDivider()
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 18.dp),
                content = content,
            )
        }
    }
}

@Composable
fun GlassDivider(modifier: Modifier = Modifier) {
    val p = LocalGlass.current
    Box(
        modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(p.separator),
    )
}

@Composable
fun GlassSectionLabel(
    text: String,
    modifier: Modifier = Modifier,
) {
    val p = LocalGlass.current
    Text(
        text = text,
        color = p.secondary,
        fontSize = 12.5.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.2.sp,
        modifier = modifier,
    )
}

/**
 * Capsule button: `primary = true` renders in solid high-contrast inverted monochrome.
 */
@Composable
fun GlassButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    primary: Boolean = false,
    destructive: Boolean = false,
    enabled: Boolean = true,
    height: Dp = 40.dp,
) {
    val p = LocalGlass.current
    val fill = when {
        !enabled -> p.glass.copy(alpha = p.glass.alpha * 0.5f)
        destructive -> p.destructive
        primary -> p.active
        else -> p.glassStrong
    }
    val contentColor = when {
        !enabled -> p.tertiary
        destructive -> Color.White
        primary -> p.onActive
        else -> p.text
    }
    Row(
        modifier = modifier
            .height(height)
            .glassSurface(CircleShape, p, fill = fill, bordered = false)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = enabled,
                onClick = onClick,
            )
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = contentColor, modifier = Modifier.size(17.dp))
        }
        Text(
            text = label,
            color = contentColor,
            fontSize = 13.5.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = (-0.1).sp,
        )
    }
}

@Composable
fun GlassIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    enabled: Boolean = true,
    size: Dp = 38.dp,
    subtle: Boolean = false,
) {
    GlassIconButton(
        painter = rememberVectorPainter(icon),
        contentDescription = contentDescription,
        onClick = onClick,
        modifier = modifier,
        active = active,
        enabled = enabled,
        size = size,
        subtle = subtle,
    )
}

@Composable
fun GlassIconButton(
    painter: Painter,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    enabled: Boolean = true,
    size: Dp = 38.dp,
    subtle: Boolean = false,
) {
    val p = LocalGlass.current
    val fill = when {
        active -> p.active
        subtle -> Color.Transparent
        else -> p.glass
    }
    val tint = when {
        !enabled -> p.tertiary
        active -> p.onActive
        subtle -> p.secondary
        else -> p.text
    }
    Box(
        modifier = modifier
            .size(size)
            .glassSurface(CircleShape, p, fill = fill, bordered = false)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = enabled,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painter,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(size * 0.52f),
        )
    }
}

/**
 * Capsule option chip: subtle borderless fill at rest, solid monochrome inversion when selected.
 */
@Composable
fun GlassChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
) {
    val p = LocalGlass.current
    val fill = if (selected) p.active else p.glass
    val ink = if (selected) p.onActive else p.text
    Row(
        modifier = modifier
            .height(34.dp)
            .glassSurface(CircleShape, p, fill = fill, bordered = false)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(15.dp))
        }
        Text(
            text = label,
            color = ink,
            fontSize = 12.5.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = (-0.1).sp,
        )
    }
}

/**
 * Concentric capsule segmented switcher with exact uniform 4dp padding on all 4 sides.
 */
@Composable
fun <T> GlassSegmentedTabs(
    options: List<T>,
    selected: T,
    labelOf: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = LocalGlass.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .glassSurface(CircleShape, p, fill = p.recessed, bordered = false)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEach { opt ->
            val isSelected = opt == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(34.dp)
                    .clip(CircleShape)
                    .background(if (isSelected) p.active else Color.Transparent)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { onSelect(opt) },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = labelOf(opt),
                    color = if (isSelected) p.onActive else p.secondary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = (-0.1).sp,
                )
            }
        }
    }
}

/**
 * Recessed monochrome text input field with subtle uniform hairline border.
 */
@Composable
fun GlassTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
) {
    val p = LocalGlass.current
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier = modifier
            .height(40.dp)
            .glassSurface(shape, p, fill = p.recessed, bordered = true)
            .padding(horizontal = 13.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (value.isEmpty()) {
            Text(
                text = placeholder,
                color = p.tertiary,
                fontSize = 13.5.sp,
            )
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            keyboardOptions = keyboardOptions,
            textStyle = TextStyle(color = p.text, fontSize = 13.5.sp, fontWeight = FontWeight.Normal),
            cursorBrush = SolidColor(p.active),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * Sleek monochrome capsule toggle switch with spring-animated thumb.
 */
@Composable
fun GlassToggle(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = LocalGlass.current
    val thumbOffset by animateDpAsState(
        targetValue = if (checked) 20.dp else 2.dp,
        animationSpec = spring(dampingRatio = 0.72f, stiffness = 700f),
        label = "glass toggle",
    )
    Box(
        modifier = modifier
            .width(46.dp)
            .height(26.dp)
            .glassSurface(CircleShape, p, fill = if (checked) p.active else p.glassStrong, bordered = false)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = { onCheckedChange(!checked) },
            ),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .offset(x = thumbOffset)
                .size(22.dp)
                .shadow(3.dp, CircleShape)
                .clip(CircleShape)
                .background(if (checked) p.onActive else p.elevated),
        )
    }
}

/**
 * Slim monochrome liquid-glass slider replacing bulky Material sliders.
 */
@Composable
fun GlassSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
) {
    val p = LocalGlass.current
    val span = (valueRange.endInclusive - valueRange.start).coerceAtLeast(1e-5f)
    val fraction = ((value - valueRange.start) / span).coerceIn(0f, 1f)

    fun updateFromX(x: Float, width: Int) {
        if (width <= 0) return
        val f = (x / width.toFloat()).coerceIn(0f, 1f)
        onValueChange(valueRange.start + f * span)
    }

    Canvas(
        modifier = modifier
            .height(28.dp)
            .pointerInput(valueRange) {
                detectTapGestures { offset -> updateFromX(offset.x, size.width) }
            }
            .pointerInput(valueRange) {
                detectHorizontalDragGestures { change, _ ->
                    change.consume()
                    updateFromX(change.position.x, size.width)
                }
            },
    ) {
        val trackH = 5.dp.toPx()
        val thumbR = 8.dp.toPx()
        val usableW = (size.width - thumbR * 2f).coerceAtLeast(1f)
        val centerY = size.height * 0.5f
        val thumbX = thumbR + fraction * usableW

        drawRoundRect(
            color = p.glassStrong,
            topLeft = Offset(thumbR, centerY - trackH * 0.5f),
            size = Size(usableW, trackH),
            cornerRadius = CornerRadius(trackH * 0.5f, trackH * 0.5f),
        )
        drawRoundRect(
            color = p.active,
            topLeft = Offset(thumbR, centerY - trackH * 0.5f),
            size = Size((thumbX - thumbR).coerceAtLeast(0f), trackH),
            cornerRadius = CornerRadius(trackH * 0.5f, trackH * 0.5f),
        )
        drawCircle(
            color = p.active,
            radius = thumbR,
            center = Offset(thumbX, centerY),
        )
    }
}

/**
 * Clean borderless tvOS-style menu row (like tvtube's QuickActionsDialog rows).
 */
@Composable
fun GlassMenuRow(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    detail: String? = null,
    icon: ImageVector? = null,
    highlighted: Boolean = false,
) {
    val p = LocalGlass.current
    val shape = RoundedCornerShape(14.dp)
    val fill = if (highlighted) p.active else p.glass
    val ink = if (highlighted) p.onActive else p.text
    val subInk = if (highlighted) p.onActive.copy(alpha = 0.65f) else p.secondary

    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 46.dp)
            .glassSurface(shape, p, fill = fill, bordered = false)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(19.dp))
        }
        Text(
            text = title,
            color = ink,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f),
        )
        if (detail != null) {
            Text(
                text = detail,
                color = subInk,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
fun GlassConfirmModal(
    title: String,
    message: String,
    confirmLabel: String,
    destructive: Boolean = false,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val p = LocalGlass.current
    GlassModal(
        title = title,
        onDismiss = onDismiss,
        maxWidth = 420.dp,
    ) {
        Text(text = message, color = p.secondary, fontSize = 14.sp, lineHeight = 20.sp)
        Spacer(Modifier.height(20.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
        ) {
            GlassButton(label = "Cancel", onClick = onDismiss)
            GlassButton(
                label = confirmLabel,
                primary = !destructive,
                destructive = destructive,
                onClick = onConfirm,
            )
        }
    }
}

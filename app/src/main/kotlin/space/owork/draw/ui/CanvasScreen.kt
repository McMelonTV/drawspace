package space.owork.draw.ui

import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Redo
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.BorderColor
import androidx.compose.material.icons.outlined.Brush
import androidx.compose.material.icons.outlined.CenterFocusStrong
import androidx.compose.material.icons.outlined.ColorLens
import androidx.compose.material.icons.outlined.Draw
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.FullscreenExit
import androidx.compose.material.icons.outlined.GridOff
import androidx.compose.material.icons.outlined.GridOn
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.ScreenRotationAlt
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke as DrawStroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import space.owork.draw.AppUiState
import space.owork.draw.R
import space.owork.draw.engine.BackgroundPattern
import space.owork.draw.engine.BrushSpec
import space.owork.draw.engine.CanvasBackground
import space.owork.draw.engine.CanvasLockMode
import space.owork.draw.engine.CanvasMode
import space.owork.draw.engine.EraserMode
import space.owork.draw.engine.Stroke
import space.owork.draw.engine.ToolType
import space.owork.draw.engine.ViewportCamera
import space.owork.draw.engine.WorldRect
import space.owork.draw.renderer.DrawspaceSurfaceView
import space.owork.draw.storage.CheckpointInfo
import space.owork.draw.ui.theme.LocalGlass
import space.owork.draw.ui.theme.glassSurface
import kotlin.math.abs
import kotlin.math.roundToInt

private data class EyedropperState(
    val screenX: Float,
    val screenY: Float,
    val sampledArgb: Int,
    val previousArgb: Int,
)

@Composable
fun CanvasScreen(
    state: AppUiState,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    onRename: (String) -> Unit,
    onSelectTool: (ToolType) -> Unit,
    onSelectEraserMode: (EraserMode) -> Unit,
    onSelectColor: (Int) -> Unit,
    onAddSwatch: (Int) -> Unit,
    onRemoveSwatch: (Int) -> Unit,
    onResetPalette: () -> Unit,
    onSizeChange: (Float) -> Unit,
    onOpacityChange: (Float) -> Unit,
    onToggleFingerDrawing: () -> Unit,
    onToggleSnapLineAngles: () -> Unit,
    onToggleSnapLineToGrid: () -> Unit,
    onToggleHidePattern: () -> Unit,
    onCycleLockMode: () -> Unit,
    onUpdateBackground: ((CanvasBackground) -> CanvasBackground) -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onStrokeCommitted: (Stroke) -> Unit,
    onStrokesErased: (List<String>, List<Stroke>) -> Unit,
    onBucketFillRequested: (Float, Float, BrushSpec, WorldRect?) -> Unit,
    onCameraChanged: (ViewportCamera) -> Unit,
    onSaveSnapshot: (String) -> Unit,
    onRestoreCheckpoint: (CheckpointInfo) -> Unit,
    onRequestCheckpointPreview: (String) -> Unit,
    onToggleZenMode: () -> Unit,
    onExitZenMode: () -> Unit,
    onSavePngToPhotos: (Boolean) -> Unit,
    onSharePng: (Boolean) -> Unit,
    onSaveSvg: (Boolean) -> Unit,
    onShareSvg: (Boolean) -> Unit,
) {
    val doc = state.activeDocument ?: return
    val p = LocalGlass.current
    val leftHanded = state.settings.leftHandedUi
    var surfaceRef by remember { mutableStateOf<DrawspaceSurfaceView?>(null) }
    var showDocumentModal by remember { mutableStateOf(false) }
    var showHistoryModal by remember { mutableStateOf(false) }
    var showColorPickerModal by remember { mutableStateOf(false) }
    var eyedropper by remember { mutableStateOf<EyedropperState?>(null) }

    val latestActiveColor by rememberUpdatedState(state.activeColorArgb)
    val latestOnUndo by rememberUpdatedState(onUndo)
    val latestOnRedo by rememberUpdatedState(onRedo)
    val latestOnToggleZen by rememberUpdatedState(onToggleZenMode)
    val latestOnSelectColor by rememberUpdatedState(onSelectColor)

    Box(modifier = Modifier.fillMaxSize()) {
        // Full-screen low-latency GLES 3.0 stylus surface
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                DrawspaceSurfaceView(ctx).apply {
                    surfaceRef = this
                    setListener(
                        object : DrawspaceSurfaceView.Listener {
                            override fun onStrokeCommitted(stroke: Stroke) = onStrokeCommitted(stroke)
                            override fun onStrokesErased(
                                removedStrokeIds: List<String>,
                                replacementStrokes: List<Stroke>,
                            ) = onStrokesErased(removedStrokeIds, replacementStrokes)
                            override fun onBucketFillRequested(
                                worldX: Float,
                                worldY: Float,
                                brush: BrushSpec,
                                visibleWorldBounds: WorldRect?,
                            ) = onBucketFillRequested(worldX, worldY, brush, visibleWorldBounds)

                            override fun onCameraChanged(camera: ViewportCamera) = onCameraChanged(camera)

                            override fun onTwoFingerTapUndo() {
                                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                latestOnUndo()
                            }

                            override fun onThreeFingerTapRedo() {
                                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                latestOnRedo()
                            }

                            override fun onFourFingerTapZen() {
                                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                latestOnToggleZen()
                            }

                            override fun onStylusButtonUndo() {
                                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                latestOnUndo()
                            }

                            override fun onEyedropperUpdate(screenX: Float, screenY: Float, colorArgb: Int) {
                                val prev = eyedropper
                                if (prev == null) {
                                    performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                }
                                eyedropper = EyedropperState(
                                    screenX = screenX,
                                    screenY = screenY,
                                    sampledArgb = colorArgb,
                                    previousArgb = prev?.previousArgb ?: latestActiveColor,
                                )
                            }

                            override fun onEyedropperFinished(commit: Boolean) {
                                val sampled = eyedropper?.sampledArgb
                                eyedropper = null
                                if (commit && sampled != null) {
                                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                    latestOnSelectColor(sampled)
                                }
                            }
                        },
                    )
                    setAppTheme(state.appTheme)
                    setAntiAliasing(state.settings.antiAliasing)
                    setStrokeSmoothing(state.settings.strokeSmoothing)
                    setRoundTurnsAndEnds(state.settings.roundTurnsAndEnds)
                    setPressureEraser(state.settings.pressureEraser, state.settings.pressureEraserThreshold)
                    setStylusButtonAction(state.settings.stylusButtonAction)
                    setMultiFingerTapGestures(state.settings.multiFingerTapGestures)
                    setLongPressEyedropper(state.settings.longPressEyedropper)
                    setSnapLineAngles(state.snapLineAngles)
                    setSnapLineToGrid(state.snapLineToGrid)
                    setHidePattern(state.hidePatternTemporarily)
                    setDocument(doc)
                    setBrush(state.activeBrush)
                    setEraserBrush(state.eraserBrush)
                    setEraserMode(state.eraserMode)
                    setAllowFingerDrawing(state.allowFingerDrawing)
                    setCameraLocks(state.effectiveCameraLocks)
                }
            },
            update = { view ->
                view.setAppTheme(state.appTheme)
                view.setAntiAliasing(state.settings.antiAliasing)
                view.setStrokeSmoothing(state.settings.strokeSmoothing)
                view.setRoundTurnsAndEnds(state.settings.roundTurnsAndEnds)
                view.setPressureEraser(state.settings.pressureEraser, state.settings.pressureEraserThreshold)
                view.setStylusButtonAction(state.settings.stylusButtonAction)
                view.setMultiFingerTapGestures(state.settings.multiFingerTapGestures)
                view.setLongPressEyedropper(state.settings.longPressEyedropper)
                view.setSnapLineAngles(state.snapLineAngles)
                view.setSnapLineToGrid(state.snapLineToGrid)
                view.setHidePattern(state.hidePatternTemporarily)
                view.setDocument(doc)
                view.setBrush(state.activeBrush)
                view.setEraserBrush(state.eraserBrush)
                view.setEraserMode(state.eraserMode)
                view.setAllowFingerDrawing(state.allowFingerDrawing)
                view.setCameraLocks(state.effectiveCameraLocks)
            },
        )

        // Top floating glass capsules
        AnimatedVisibility(
            visible = !state.zenMode,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .safeDrawingPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
            // Top-left: Back + Document/Canvas modal trigger
            GlassPill(shape = CircleShape) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 5.dp),
                ) {
                    GlassIconButton(
                        icon = Icons.AutoMirrored.Outlined.ArrowBack,
                        contentDescription = "Back to workspace",
                        size = 36.dp,
                        subtle = true,
                        onClick = onBack,
                    )
                    Row(
                        modifier = Modifier
                            .clip(CircleShape)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = { showDocumentModal = true },
                            )
                            .padding(start = 6.dp, end = 10.dp, top = 2.dp, bottom = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Column {
                            Text(
                                text = doc.title,
                                color = p.text,
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                letterSpacing = (-0.15).sp,
                            )
                            val modeText = when (val m = doc.canvasMode) {
                                CanvasMode.Infinite -> "Infinite"
                                is CanvasMode.Fixed -> "${m.widthPx}×${m.heightPx}"
                            }
                            Text(
                                text = "$modeText · ${(state.cameraZoom * 100).roundToInt()}% · ${state.saveStatus}",
                                color = p.secondary,
                                fontSize = 11.sp,
                            )
                        }
                        Icon(
                            Icons.Outlined.ExpandMore,
                            contentDescription = "Document and canvas options",
                            tint = p.secondary,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }

            // Top-right: High-frequency viewport & input glass capsule
            GlassPill(shape = CircleShape) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 5.dp),
                ) {
                    GlassIconButton(
                        icon = if (state.allowFingerDrawing) Icons.Outlined.TouchApp else Icons.Outlined.Draw,
                        contentDescription = if (state.allowFingerDrawing) "Finger & Stylus draw" else "Stylus-only draw",
                        active = !state.allowFingerDrawing,
                        subtle = state.allowFingerDrawing,
                        size = 36.dp,
                        onClick = onToggleFingerDrawing,
                    )

                    val isLocked = state.lockMode != CanvasLockMode.UNLOCKED
                    Row(
                        modifier = Modifier
                            .height(36.dp)
                            .clip(CircleShape)
                            .background(if (isLocked) p.active else Color.Transparent)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = onCycleLockMode,
                            )
                            .padding(horizontal = if (isLocked) 11.dp else 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(
                            imageVector = if (isLocked) Icons.Outlined.Lock else Icons.Outlined.LockOpen,
                            contentDescription = state.lockMode.label,
                            tint = if (isLocked) p.onActive else p.secondary,
                            modifier = Modifier.size(18.dp),
                        )
                        state.lockMode.shortBadge?.let { badge ->
                            Text(
                                text = badge,
                                color = p.onActive,
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }

                    if (abs(state.cameraRotationRad) > 0.02f) {
                        GlassIconButton(
                            icon = Icons.Outlined.ScreenRotationAlt,
                            contentDescription = "Reset rotation to 0°",
                            active = true,
                            size = 36.dp,
                            onClick = { surfaceRef?.resetRotation() },
                        )
                    }

                    if (doc.meta.background.pattern != BackgroundPattern.BLANK) {
                        GlassIconButton(
                            icon = if (state.hidePatternTemporarily) Icons.Outlined.GridOff else Icons.Outlined.GridOn,
                            contentDescription = if (state.hidePatternTemporarily) "Show background pattern" else "Hide background pattern temporarily",
                            active = state.hidePatternTemporarily,
                            subtle = !state.hidePatternTemporarily,
                            size = 36.dp,
                            onClick = onToggleHidePattern,
                        )
                    }

                    GlassIconButton(
                        icon = Icons.Outlined.CenterFocusStrong,
                        contentDescription = "Fit canvas to screen",
                        subtle = true,
                        size = 36.dp,
                        onClick = { surfaceRef?.fitToView() },
                    )
                    GlassIconButton(
                        icon = Icons.Outlined.History,
                        contentDescription = "Version history",
                        subtle = true,
                        size = 36.dp,
                        onClick = { showHistoryModal = true },
                    )
                    GlassIconButton(
                        icon = Icons.Outlined.Fullscreen,
                        contentDescription = "Hide UI (Zen mode)",
                        subtle = true,
                        size = 36.dp,
                        onClick = onToggleZenMode,
                    )
                }
            }
        }
        }

        // Side floating glass tool capsule (left edge by default, right edge in left-handed mode)
        AnimatedVisibility(
            visible = !state.zenMode,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(if (leftHanded) Alignment.CenterEnd else Alignment.CenterStart)
                .safeDrawingPadding()
                .padding(start = if (leftHanded) 0.dp else 16.dp, end = if (leftHanded) 16.dp else 0.dp),
        ) {
        GlassPill(
            shape = CircleShape,
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                GlassIconButton(
                    icon = Icons.Outlined.Edit,
                    contentDescription = "Pen",
                    active = state.activeTool == ToolType.PEN,
                    subtle = state.activeTool != ToolType.PEN,
                    size = 42.dp,
                    onClick = { onSelectTool(ToolType.PEN) },
                )
                GlassIconButton(
                    icon = Icons.Outlined.BorderColor,
                    contentDescription = "Pencil",
                    active = state.activeTool == ToolType.PENCIL,
                    subtle = state.activeTool != ToolType.PENCIL,
                    size = 42.dp,
                    onClick = { onSelectTool(ToolType.PENCIL) },
                )
                GlassIconButton(
                    icon = Icons.Outlined.Brush,
                    contentDescription = "Marker",
                    active = state.activeTool == ToolType.MARKER,
                    subtle = state.activeTool != ToolType.MARKER,
                    size = 42.dp,
                    onClick = { onSelectTool(ToolType.MARKER) },
                )
                GlassIconButton(
                    painter = painterResource(R.drawable.ic_tool_line),
                    contentDescription = "Line",
                    active = state.activeTool == ToolType.LINE,
                    subtle = state.activeTool != ToolType.LINE,
                    size = 42.dp,
                    onClick = { onSelectTool(ToolType.LINE) },
                )
                GlassIconButton(
                    painter = painterResource(R.drawable.ic_tool_bucket),
                    contentDescription = "Fill",
                    active = state.activeTool == ToolType.FILL,
                    subtle = state.activeTool != ToolType.FILL,
                    size = 42.dp,
                    onClick = { onSelectTool(ToolType.FILL) },
                )
                GlassIconButton(
                    painter = painterResource(R.drawable.ic_tool_eraser),
                    contentDescription = "Eraser",
                    active = state.activeTool == ToolType.ERASER,
                    subtle = state.activeTool != ToolType.ERASER,
                    size = 42.dp,
                    onClick = { onSelectTool(ToolType.ERASER) },
                )

                Box(
                    modifier = Modifier
                        .padding(vertical = 2.dp)
                        .width(24.dp)
                        .height(1.dp)
                        .background(p.separator),
                )

                GlassIconButton(
                    icon = Icons.AutoMirrored.Outlined.Undo,
                    contentDescription = "Undo",
                    enabled = state.canUndo,
                    subtle = true,
                    size = 38.dp,
                    onClick = onUndo,
                )
                GlassIconButton(
                    icon = Icons.AutoMirrored.Outlined.Redo,
                    contentDescription = "Redo",
                    enabled = state.canRedo,
                    subtle = true,
                    size = 38.dp,
                    onClick = onRedo,
                )
            }
        }
        }

        // Bottom floating glass palette + monochrome sliders capsule
        AnimatedVisibility(
            visible = !state.zenMode,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .safeDrawingPadding()
                .padding(
                    bottom = 14.dp,
                    start = if (leftHanded) 24.dp else 76.dp,
                    end = if (leftHanded) 76.dp else 24.dp,
                ),
        ) {
        GlassPill(
            shape = CircleShape,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                if (state.activeTool != ToolType.ERASER) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(Color(state.activeColorArgb))
                            .border(2.5.dp, p.active, CircleShape)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = { showColorPickerModal = true },
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        val iconTint = if (CanvasBackground.isColorDark(state.activeColorArgb)) Color.White else Color(0xFF141417)
                        Icon(
                            Icons.Outlined.ColorLens,
                            contentDescription = "Open color picker",
                            tint = iconTint.copy(alpha = 0.85f),
                            modifier = Modifier.size(16.dp),
                        )
                    }

                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f, fill = false),
                    ) {
                        items(state.palette.swatches) { swatch ->
                            val selected = state.activeColorArgb == swatch.argb
                            Box(
                                modifier = Modifier
                                    .size(if (selected) 30.dp else 26.dp)
                                    .clip(CircleShape)
                                    .background(Color(swatch.argb))
                                    .border(
                                        width = if (selected) 2.5.dp else 1.dp,
                                        color = if (selected) p.active else p.swatchBorder,
                                        shape = CircleShape,
                                    )
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                        onClick = { onSelectColor(swatch.argb) },
                                    ),
                            )
                        }
                        item {
                            Box(
                                modifier = Modifier
                                    .size(26.dp)
                                    .clip(CircleShape)
                                    .background(p.glass)
                                    .border(1.dp, p.swatchBorder, CircleShape)
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                        onClick = { showColorPickerModal = true },
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    Icons.Outlined.Add,
                                    contentDescription = "Customize palette",
                                    tint = p.secondary,
                                    modifier = Modifier.size(15.dp),
                                )
                            }
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier
                            .glassSurface(CircleShape, p, fill = p.recessed, bordered = false)
                            .padding(3.dp),
                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        EraserMode.entries.forEach { mode ->
                            val isSelected = state.eraserMode == mode
                            Box(
                                modifier = Modifier
                                    .height(28.dp)
                                    .clip(CircleShape)
                                    .background(if (isSelected) p.active else Color.Transparent)
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                        onClick = { onSelectEraserMode(mode) },
                                    )
                                    .padding(horizontal = 12.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = mode.label,
                                    color = if (isSelected) p.onActive else p.secondary,
                                    fontSize = 12.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    letterSpacing = (-0.1).sp,
                                )
                            }
                        }
                    }
                }

                if (state.activeTool == ToolType.LINE) {
                    if (doc.meta.background.pattern != BackgroundPattern.BLANK) {
                        GlassChip(
                            label = "Grid Snap",
                            selected = state.snapLineToGrid,
                            onClick = onToggleSnapLineToGrid,
                        )
                    }
                    GlassChip(
                        label = "15° Snap",
                        selected = state.snapLineAngles && !state.snapLineToGrid,
                        onClick = onToggleSnapLineAngles,
                    )
                }

                if (state.activeTool != ToolType.FILL) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = "${state.activeBrush.sizeWorldPx.roundToInt()}px",
                            color = p.secondary,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.width(34.dp),
                        )
                        GlassSlider(
                            value = state.activeBrush.sizeWorldPx,
                            onValueChange = onSizeChange,
                            valueRange = 1f..64f,
                            modifier = Modifier.width(if (state.activeTool == ToolType.ERASER) 160.dp else 108.dp),
                        )
                    }
                }

                if (state.activeTool != ToolType.ERASER) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = "${(state.activeBrush.opacity * 100).roundToInt()}%",
                            color = p.secondary,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.width(34.dp),
                        )
                        GlassSlider(
                            value = state.activeBrush.opacity,
                            onValueChange = onOpacityChange,
                            valueRange = 0.08f..1f,
                            modifier = Modifier.width(92.dp),
                        )
                    }
                }
            }
        }
        }

        // Unobtrusive corner button to exit Zen mode
        AnimatedVisibility(
            visible = state.zenMode,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(if (leftHanded) Alignment.TopStart else Alignment.TopEnd)
                .safeDrawingPadding()
                .padding(16.dp),
        ) {
            GlassPill(
                shape = CircleShape,
                modifier = Modifier.alpha(0.72f),
            ) {
                Box(modifier = Modifier.padding(4.dp)) {
                    GlassIconButton(
                        icon = Icons.Outlined.FullscreenExit,
                        contentDescription = "Exit Zen mode",
                        subtle = true,
                        size = 36.dp,
                        onClick = onExitZenMode,
                    )
                }
            }
        }

        // Live eyedropper color loupe overlay
        eyedropper?.let { sample ->
            EyedropperLoupeOverlay(sample = sample, leftHanded = leftHanded)
        }
    }

    if (showDocumentModal) {
        DocumentMenuModal(
            doc = doc,
            state = state,
            onDismiss = { showDocumentModal = false },
            onRename = onRename,
            onUpdateBackground = onUpdateBackground,
            onToggleHidePattern = onToggleHidePattern,
            onFitToView = { surfaceRef?.fitToView() },
            onResetRotation = { surfaceRef?.resetRotation() },
            onOpenHistory = { showHistoryModal = true },
            onSaveSnapshot = onSaveSnapshot,
            onSavePngToPhotos = onSavePngToPhotos,
            onSharePng = onSharePng,
            onSaveSvg = onSaveSvg,
            onShareSvg = onShareSvg,
            onOpenSettings = onOpenSettings,
        )
    }

    if (showColorPickerModal) {
        ColorPickerSheet(
            initialColorArgb = state.activeColorArgb,
            palette = state.palette,
            onDismiss = { showColorPickerModal = false },
            onSelectColor = onSelectColor,
            onAddSwatch = onAddSwatch,
            onRemoveSwatch = onRemoveSwatch,
            onResetPalette = onResetPalette,
        )
    }

    if (showHistoryModal) {
        VersionHistoryModal(
            state = state,
            onDismiss = { showHistoryModal = false },
            onSaveSnapshot = onSaveSnapshot,
            onRestoreCheckpoint = onRestoreCheckpoint,
            onRequestCheckpointPreview = onRequestCheckpointPreview,
        )
    }
}

@Composable
private fun EyedropperLoupeOverlay(sample: EyedropperState, leftHanded: Boolean) {
    val p = LocalGlass.current
    val density = LocalDensity.current
    val loupeSizeDp = 76.dp
    val loupeSizePx = with(density) { loupeSizeDp.toPx() }
    val totalHeightPx = with(density) { 108.dp.toPx() }
    val fingerClearancePx = with(density) { 74.dp.toPx() }
    val sideOffsetPx = with(density) { 104.dp.toPx() }
    val marginPx = with(density) { 16.dp.toPx() }
    val reticleRadiusPx = with(density) { 7.dp.toPx() }
    val hexText = remember(sample.sampledArgb) { "#%06X".format(sample.sampledArgb and 0xFFFFFF) }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val maxW = with(density) { maxWidth.toPx() }
        val maxH = with(density) { maxHeight.toPx() }

        // Crisp sampling reticle directly at the sampled pixel
        Canvas(modifier = Modifier.fillMaxSize()) {
            val pt = Offset(sample.screenX, sample.screenY)
            drawCircle(
                color = Color.Black.copy(alpha = 0.65f),
                radius = reticleRadiusPx + 1.5f,
                center = pt,
                style = DrawStroke(width = 3f),
            )
            drawCircle(
                color = Color.White,
                radius = reticleRadiusPx,
                center = pt,
                style = DrawStroke(width = 2.5f),
            )
        }

        // Place the full loupe (circle + hex badge) cleanly above the fingertip;
        // if near the top edge, shift to the unobstructed side (left for right-handed, right for left-handed).
        val aboveY = sample.screenY - fingerClearancePx - totalHeightPx
        val targetX: Float
        val targetY: Float
        if (aboveY >= marginPx) {
            targetX = (sample.screenX - loupeSizePx * 0.5f)
                .coerceIn(marginPx, (maxW - loupeSizePx - marginPx).coerceAtLeast(marginPx))
            targetY = aboveY
        } else {
            val preferredRight = leftHanded
            val sideX = if (preferredRight) {
                sample.screenX + sideOffsetPx - loupeSizePx * 0.5f
            } else {
                sample.screenX - sideOffsetPx - loupeSizePx * 0.5f
            }
            val fitsPreferred = sideX in marginPx..(maxW - loupeSizePx - marginPx)
            targetX = if (fitsPreferred) {
                sideX
            } else if (preferredRight) {
                (sample.screenX - sideOffsetPx - loupeSizePx * 0.5f).coerceIn(marginPx, (maxW - loupeSizePx - marginPx).coerceAtLeast(marginPx))
            } else {
                (sample.screenX + sideOffsetPx - loupeSizePx * 0.5f).coerceIn(marginPx, (maxW - loupeSizePx - marginPx).coerceAtLeast(marginPx))
            }
            targetY = (sample.screenY - loupeSizePx * 0.5f)
                .coerceIn(marginPx, (maxH - totalHeightPx - marginPx).coerceAtLeast(marginPx))
        }

        Column(
            modifier = Modifier.offset { IntOffset(targetX.roundToInt(), targetY.roundToInt()) },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(loupeSizeDp)
                    .shadow(18.dp, CircleShape, clip = false)
                    .clip(CircleShape),
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val d = size.minDimension
                    val topLeft = Offset((size.width - d) * 0.5f, (size.height - d) * 0.5f)
                    val arcSize = Size(d, d)
                    // Top half: newly sampled color; bottom half: previous color for direct comparison
                    drawArc(
                        color = Color(sample.sampledArgb),
                        startAngle = 180f,
                        sweepAngle = 180f,
                        useCenter = true,
                        topLeft = topLeft,
                        size = arcSize,
                    )
                    drawArc(
                        color = Color(sample.previousArgb),
                        startAngle = 0f,
                        sweepAngle = 180f,
                        useCenter = true,
                        topLeft = topLeft,
                        size = arcSize,
                    )
                    // Crisp double ring border for visibility on both light and dark canvases
                    drawCircle(
                        color = Color.Black.copy(alpha = 0.55f),
                        radius = d * 0.5f - 1.5f,
                        style = DrawStroke(width = 3f),
                    )
                    drawCircle(
                        color = Color.White,
                        radius = d * 0.5f - 4.5f,
                        style = DrawStroke(width = 3f),
                    )
                }
            }

            GlassPill(shape = CircleShape) {
                Text(
                    text = hexText,
                    color = p.text,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        }
    }
}

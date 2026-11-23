package space.owork.draw.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material.icons.outlined.GridOff
import androidx.compose.material.icons.outlined.GridOn
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.ScreenRotationAlt
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import space.owork.draw.AppUiState
import space.owork.draw.R
import space.owork.draw.engine.BackgroundPattern
import space.owork.draw.engine.BrushSpec
import space.owork.draw.engine.CanvasBackground
import space.owork.draw.engine.CanvasLockMode
import space.owork.draw.engine.CanvasMode
import space.owork.draw.engine.Stroke
import space.owork.draw.engine.ToolType
import space.owork.draw.engine.ViewportCamera
import space.owork.draw.engine.WorldRect
import space.owork.draw.renderer.DrawspaceSurfaceView
import space.owork.draw.storage.CheckpointInfo
import space.owork.draw.ui.theme.LocalHudColors
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun CanvasScreen(
    state: AppUiState,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    onRename: (String) -> Unit,
    onSelectTool: (ToolType) -> Unit,
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
    onStrokesErased: (List<String>) -> Unit,
    onBucketFillRequested: (Float, Float, BrushSpec, WorldRect?) -> Unit,
    onCameraChanged: (ViewportCamera) -> Unit,
    onSaveSnapshot: (String) -> Unit,
    onRestoreCheckpoint: (CheckpointInfo) -> Unit,
    onRequestCheckpointPreview: (String) -> Unit,
    onExportPng: () -> Unit,
) {
    val doc = state.activeDocument ?: return
    val hudColors = LocalHudColors.current
    var surfaceRef by remember { mutableStateOf<DrawspaceSurfaceView?>(null) }
    var showDocumentModal by remember { mutableStateOf(false) }
    var showHistoryModal by remember { mutableStateOf(false) }
    var showColorPickerModal by remember { mutableStateOf(false) }

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
                            override fun onStrokesErased(strokeIds: List<String>) = onStrokesErased(strokeIds)
                            override fun onBucketFillRequested(
                                worldX: Float,
                                worldY: Float,
                                brush: BrushSpec,
                                visibleWorldBounds: WorldRect?,
                            ) = onBucketFillRequested(worldX, worldY, brush, visibleWorldBounds)

                            override fun onCameraChanged(camera: ViewportCamera) = onCameraChanged(camera)
                        },
                    )
                    setAppTheme(state.appTheme)
                    setAntiAliasing(state.settings.antiAliasing)
                    setStrokeSmoothing(state.settings.strokeSmoothing)
                    setRoundTurnsAndEnds(state.settings.roundTurnsAndEnds)
                    setSnapLineAngles(state.snapLineAngles)
                    setSnapLineToGrid(state.snapLineToGrid)
                    setHidePattern(state.hidePatternTemporarily)
                    setDocument(doc)
                    setBrush(state.activeBrush)
                    setAllowFingerDrawing(state.allowFingerDrawing)
                    setCameraLocks(state.effectiveCameraLocks)
                }
            },
            update = { view ->
                view.setAppTheme(state.appTheme)
                view.setAntiAliasing(state.settings.antiAliasing)
                view.setStrokeSmoothing(state.settings.strokeSmoothing)
                view.setRoundTurnsAndEnds(state.settings.roundTurnsAndEnds)
                view.setSnapLineAngles(state.snapLineAngles)
                view.setSnapLineToGrid(state.snapLineToGrid)
                view.setHidePattern(state.hidePatternTemporarily)
                view.setDocument(doc)
                view.setBrush(state.activeBrush)
                view.setAllowFingerDrawing(state.allowFingerDrawing)
                view.setCameraLocks(state.effectiveCameraLocks)
            },
        )

        // Top floating header bar
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .safeDrawingPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Left pill: Back + Document & Canvas Studio Menu trigger
            FloatingPill {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                ) {
                    IconButton(onClick = onBack, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back to workspace")
                    }
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { showDocumentModal = true }
                            .padding(start = 6.dp, end = 8.dp, top = 3.dp, bottom = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Column {
                            Text(doc.title, style = MaterialTheme.typography.titleSmall)
                            val modeText = when (val m = doc.canvasMode) {
                                CanvasMode.Infinite -> "Infinite"
                                is CanvasMode.Fixed -> "${m.widthPx}×${m.heightPx}"
                            }
                            Text(
                                "$modeText · ${(state.cameraZoom * 100).roundToInt()}% · ${state.saveStatus}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Icon(
                            Icons.Outlined.ExpandMore,
                            contentDescription = "Document and canvas options",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }

            // Right pill: Quick mid-sketch viewport & input toggles
            FloatingPill {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                ) {
                    IconButton(
                        onClick = onToggleFingerDrawing,
                        modifier = Modifier.size(38.dp),
                    ) {
                        Icon(
                            imageVector = if (state.allowFingerDrawing) Icons.Outlined.TouchApp else Icons.Outlined.Draw,
                            contentDescription = if (state.allowFingerDrawing) "Finger & Stylus draw" else "Stylus-only draw",
                            tint = if (state.allowFingerDrawing) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                        )
                    }

                    // 1-tap cycling lock toggle: Unlocked -> Lock Rotation -> Lock Zoom -> Lock Zoom & Rotation
                    val isLocked = state.lockMode != CanvasLockMode.UNLOCKED
                    Surface(
                        onClick = onCycleLockMode,
                        shape = RoundedCornerShape(10.dp),
                        color = if (isLocked) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Icon(
                                imageVector = if (isLocked) Icons.Outlined.Lock else Icons.Outlined.LockOpen,
                                contentDescription = state.lockMode.label,
                                tint = if (isLocked) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                modifier = Modifier.size(20.dp),
                            )
                            state.lockMode.shortBadge?.let { badge ->
                                Text(
                                    text = badge,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }

                    if (abs(state.cameraRotationRad) > 0.02f) {
                        IconButton(
                            onClick = { surfaceRef?.resetRotation() },
                            modifier = Modifier.size(38.dp),
                        ) {
                            Icon(
                                Icons.Outlined.ScreenRotationAlt,
                                contentDescription = "Reset rotation to 0°",
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }

                    if (doc.meta.background.pattern != BackgroundPattern.BLANK) {
                        IconButton(
                            onClick = onToggleHidePattern,
                            modifier = Modifier.size(38.dp),
                        ) {
                            Icon(
                                imageVector = if (state.hidePatternTemporarily) Icons.Outlined.GridOff else Icons.Outlined.GridOn,
                                contentDescription = if (state.hidePatternTemporarily) "Show background pattern" else "Hide background pattern temporarily",
                                tint = if (state.hidePatternTemporarily) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                    }

                    IconButton(
                        onClick = { surfaceRef?.fitToView() },
                        modifier = Modifier.size(38.dp),
                    ) {
                        Icon(Icons.Outlined.CenterFocusStrong, contentDescription = "Fit canvas to screen")
                    }
                    IconButton(
                        onClick = { showHistoryModal = true },
                        modifier = Modifier.size(38.dp),
                    ) {
                        Icon(Icons.Outlined.History, contentDescription = "History checkpoints")
                    }
                }
            }
        }

        // Left floating tool rail (tablet ergonomic reach)
        FloatingPill(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .safeDrawingPadding()
                .padding(start = 16.dp),
        ) {
            Column(
                modifier = Modifier.padding(vertical = 8.dp, horizontal = 6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                ToolRailButton(
                    icon = Icons.Outlined.Edit,
                    label = "Pen",
                    selected = state.activeTool == ToolType.PEN,
                    onClick = { onSelectTool(ToolType.PEN) },
                )
                ToolRailButton(
                    icon = Icons.Outlined.BorderColor,
                    label = "Pencil",
                    selected = state.activeTool == ToolType.PENCIL,
                    onClick = { onSelectTool(ToolType.PENCIL) },
                )
                ToolRailButton(
                    icon = Icons.Outlined.Brush,
                    label = "Marker",
                    selected = state.activeTool == ToolType.MARKER,
                    onClick = { onSelectTool(ToolType.MARKER) },
                )
                ToolRailButton(
                    painter = painterResource(R.drawable.ic_tool_line),
                    label = "Line",
                    selected = state.activeTool == ToolType.LINE,
                    onClick = { onSelectTool(ToolType.LINE) },
                )
                ToolRailButton(
                    painter = painterResource(R.drawable.ic_tool_bucket),
                    label = "Fill",
                    selected = state.activeTool == ToolType.FILL,
                    onClick = { onSelectTool(ToolType.FILL) },
                )
                ToolRailButton(
                    painter = painterResource(R.drawable.ic_tool_eraser),
                    label = "Eraser",
                    selected = state.activeTool == ToolType.ERASER,
                    onClick = { onSelectTool(ToolType.ERASER) },
                )

                HorizontalDivider(modifier = Modifier.width(28.dp).padding(vertical = 4.dp))

                IconButton(onClick = onUndo, enabled = state.canUndo, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.AutoMirrored.Outlined.Undo, contentDescription = "Undo")
                }
                IconButton(onClick = onRedo, enabled = state.canRedo, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.AutoMirrored.Outlined.Redo, contentDescription = "Redo")
                }
            }
        }

        // Bottom floating palette + brush size/opacity dock
        FloatingPill(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .safeDrawingPadding()
                .padding(bottom = 14.dp, start = 76.dp, end = 24.dp),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (state.activeTool != ToolType.ERASER) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(Color(state.activeColorArgb))
                            .border(2.5.dp, MaterialTheme.colorScheme.primary, CircleShape)
                            .clickable { showColorPickerModal = true },
                        contentAlignment = Alignment.Center,
                    ) {
                        val iconTint = if (CanvasBackground.isColorDark(state.activeColorArgb)) Color.White else Color(0xFF181A1F)
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
                                        color = if (selected) MaterialTheme.colorScheme.primary else hudColors.swatchBorder,
                                        shape = CircleShape,
                                    )
                                    .clickable { onSelectColor(swatch.argb) },
                            )
                        }
                        item {
                            Box(
                                modifier = Modifier
                                    .size(26.dp)
                                    .clip(CircleShape)
                                    .border(1.dp, hudColors.swatchBorder, CircleShape)
                                    .clickable { showColorPickerModal = true },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    Icons.Outlined.Add,
                                    contentDescription = "Customize palette",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                    }
                } else {
                    Text(
                        text = "Eraser",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (state.activeTool == ToolType.LINE) {
                    if (doc.meta.background.pattern != BackgroundPattern.BLANK) {
                        FilterChip(
                            selected = state.snapLineToGrid,
                            onClick = onToggleSnapLineToGrid,
                            label = { Text("Grid Snap") },
                        )
                    }
                    FilterChip(
                        selected = state.snapLineAngles && !state.snapLineToGrid,
                        onClick = onToggleSnapLineAngles,
                        label = { Text("15° Snap") },
                    )
                }

                if (state.activeTool != ToolType.FILL) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = "${state.activeBrush.sizeWorldPx.roundToInt()}px",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(34.dp),
                        )
                        Slider(
                            value = state.activeBrush.sizeWorldPx,
                            onValueChange = onSizeChange,
                            valueRange = 1f..64f,
                            modifier = Modifier.width(if (state.activeTool == ToolType.ERASER) 160.dp else 110.dp),
                        )
                    }
                }

                if (state.activeTool != ToolType.ERASER) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = "${(state.activeBrush.opacity * 100).roundToInt()}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(34.dp),
                        )
                        Slider(
                            value = state.activeBrush.opacity,
                            onValueChange = onOpacityChange,
                            valueRange = 0.08f..1f,
                            modifier = Modifier.width(96.dp),
                        )
                    }
                }
            }
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
            onExportPng = onExportPng,
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
private fun FloatingPill(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val hudColors = LocalHudColors.current
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        color = hudColors.pillBackground,
        border = BorderStroke(1.dp, hudColors.pillBorder),
        tonalElevation = 6.dp,
        shadowElevation = 8.dp,
        content = content,
    )
}

@Composable
private fun ToolRailButton(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    ToolRailButton(
        painter = rememberVectorPainter(icon),
        label = label,
        selected = selected,
        onClick = onClick,
    )
}

@Composable
private fun ToolRailButton(
    painter: Painter,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
        modifier = Modifier.size(44.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                painter = painter,
                contentDescription = label,
                tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

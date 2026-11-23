package space.owork.draw.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Redo
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.BorderColor
import androidx.compose.material.icons.outlined.Brush
import androidx.compose.material.icons.outlined.CenterFocusStrong
import androidx.compose.material.icons.outlined.ColorLens
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Draw
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.GridOff
import androidx.compose.material.icons.outlined.GridOn
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.ScreenRotationAlt
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import space.owork.draw.engine.AppTheme
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
import java.text.DateFormat
import java.util.Date
import kotlin.math.abs
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
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
    var showHistorySheet by remember { mutableStateOf(false) }
    var showAppearanceSheet by remember { mutableStateOf(false) }
    var showColorPickerSheet by remember { mutableStateOf(false) }
    var showSnapshotDialog by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }

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
            FloatingPill {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                ) {
                    IconButton(onClick = onBack, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back to workspace")
                    }
                    Column(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { showRenameDialog = true }
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    ) {
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
                }
            }

            FloatingPill {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                ) {
                    // Quick Stylus-only vs Touch+Stylus toggle
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

                    // Show quick level-rotation snap whenever canvas is rotated
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
                        onClick = { showAppearanceSheet = true },
                        modifier = Modifier.size(38.dp),
                    ) {
                        Icon(Icons.Outlined.Tune, contentDescription = "Canvas theme & background")
                    }
                    IconButton(
                        onClick = { surfaceRef?.fitToView() },
                        modifier = Modifier.size(38.dp),
                    ) {
                        Icon(Icons.Outlined.CenterFocusStrong, contentDescription = "Fit canvas to screen")
                    }
                    IconButton(
                        onClick = { showHistorySheet = true },
                        modifier = Modifier.size(38.dp),
                    ) {
                        Icon(Icons.Outlined.History, contentDescription = "History checkpoints")
                    }
                    IconButton(
                        onClick = onExportPng,
                        modifier = Modifier.size(38.dp),
                    ) {
                        Icon(Icons.Outlined.Download, contentDescription = "Export PNG")
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
                    // Active color picker trigger
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(Color(state.activeColorArgb))
                            .border(2.5.dp, MaterialTheme.colorScheme.primary, CircleShape)
                            .clickable { showColorPickerSheet = true },
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
                                    .clickable { showColorPickerSheet = true },
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

                // Size slider (hidden for Bucket Fill)
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

    if (showColorPickerSheet) {
        ColorPickerSheet(
            initialColorArgb = state.activeColorArgb,
            palette = state.palette,
            onDismiss = { showColorPickerSheet = false },
            onSelectColor = onSelectColor,
            onAddSwatch = onAddSwatch,
            onRemoveSwatch = onRemoveSwatch,
            onResetPalette = onResetPalette,
        )
    }

    if (showAppearanceSheet) {
        val bg = doc.meta.background
        ModalBottomSheet(onDismissRequest = { showAppearanceSheet = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Canvas Background", style = MaterialTheme.typography.titleLarge)
                    TextButton(
                        onClick = {
                            showAppearanceSheet = false
                            onOpenSettings()
                        },
                    ) {
                        Icon(Icons.Outlined.Settings, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("App Settings")
                    }
                }

                // Per-canvas theme (applies ONLY to this canvas background & surround)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Canvas Theme (this sketch only)",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        FilterChip(
                            selected = bg.canvasTheme == null && bg.customPaperColorArgb == null,
                            onClick = {
                                onUpdateBackground { it.copy(canvasTheme = null, customPaperColorArgb = null) }
                            },
                            label = { Text("Follow App (${state.appTheme.label})") },
                        )
                        AppTheme.entries.forEach { t ->
                            FilterChip(
                                selected = bg.canvasTheme == t && bg.customPaperColorArgb == null,
                                onClick = {
                                    onUpdateBackground { it.copy(canvasTheme = t, customPaperColorArgb = null) }
                                },
                                label = {
                                    Text(if (t == AppTheme.BLACK) "Black (AMOLED)" else t.label)
                                },
                            )
                        }
                    }
                }

                // Custom paper color override
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Custom Paper Color",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CanvasBackground.paperPresets.forEach { swatch ->
                            val selected = bg.customPaperColorArgb == swatch.argb
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
                                    .clickable { onUpdateBackground { it.copy(customPaperColorArgb = swatch.argb) } },
                            )
                        }
                    }
                }

                HorizontalDivider()

                // Pattern selector
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Background Pattern",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        BackgroundPattern.entries.forEach { pat ->
                            FilterChip(
                                selected = bg.pattern == pat,
                                onClick = { onUpdateBackground { it.copy(pattern = pat) } },
                                label = { Text(pat.label) },
                            )
                        }
                    }
                }

                if (bg.pattern != BackgroundPattern.BLANK) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Temporarily hide pattern", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "Keeps your pattern settings without saving changes",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = state.hidePatternTemporarily,
                            onCheckedChange = { onToggleHidePattern() },
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Spacing: ${bg.spacingPx.roundToInt()} px",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Slider(
                                value = bg.spacingPx,
                                onValueChange = { v -> onUpdateBackground { it.copy(spacingPx = v) } },
                                valueRange = 20f..160f,
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Opacity: ${(bg.opacity * 100).roundToInt()}%",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Slider(
                                value = bg.opacity,
                                onValueChange = { v -> onUpdateBackground { it.copy(opacity = v) } },
                                valueRange = 0.05f..0.85f,
                            )
                        }
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "Pattern Color",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            FilterChip(
                                selected = bg.patternColorArgb == null,
                                onClick = { onUpdateBackground { it.copy(patternColorArgb = null) } },
                                label = { Text("Auto") },
                            )
                            CanvasBackground.patternColorPresets.forEach { swatch ->
                                val selected = bg.patternColorArgb == swatch.argb
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
                                        .clickable { onUpdateBackground { it.copy(patternColorArgb = swatch.argb) } },
                                )
                            }
                        }
                    }
                }

                if (doc.canvasMode == CanvasMode.Infinite) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Show origin ring", style = MaterialTheme.typography.bodyMedium)
                        Switch(
                            checked = bg.showOriginMarker,
                            onCheckedChange = { checked -> onUpdateBackground { it.copy(showOriginMarker = checked) } },
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))
            }
        }
    }

    if (showHistorySheet) {
        ModalBottomSheet(onDismissRequest = { showHistorySheet = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Version History", style = MaterialTheme.typography.titleLarge)
                    Button(onClick = { showSnapshotDialog = true }) {
                        Text("Save Snapshot")
                    }
                }
                Spacer(Modifier.height(12.dp))
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(360.dp),
                ) {
                    itemsIndexed(state.history, key = { _, cp -> cp.id }) { index, cp ->
                        LaunchedEffect(cp.id) {
                            onRequestCheckpointPreview(cp.id)
                        }
                        val preview = state.historyPreviews[cp.id]
                        val prevCheckpoint = state.history.getOrNull(index + 1)
                        val prevPreview = prevCheckpoint?.let { state.historyPreviews[it.id] }
                        val addedIds = remember(preview, prevPreview) {
                            if (preview != null && prevPreview != null) {
                                val prevIds = prevPreview.previewStrokes.mapTo(HashSet()) { it.id }
                                preview.previewStrokes.asSequence().map { it.id }.filterNot { it in prevIds }.toSet()
                            } else {
                                emptySet()
                            }
                        }
                        val removedCount = remember(preview, prevPreview) {
                            if (preview != null && prevPreview != null) {
                                val currIds = preview.previewStrokes.mapTo(HashSet()) { it.id }
                                prevPreview.previewStrokes.count { it.id !in currIds }
                            } else {
                                0
                            }
                        }
                        val ts = remember(cp.timestampMs) {
                            DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM)
                                .format(Date(cp.timestampMs))
                        }
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier.padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(width = 96.dp, height = 62.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp)),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (preview != null) {
                                        DocumentThumbnailCanvas(
                                            summary = preview,
                                            appTheme = state.appTheme,
                                            highlightStrokeIds = addedIds,
                                            highlightColor = MaterialTheme.colorScheme.primary,
                                            fixedArtboardPaddingDp = 4f,
                                            showPattern = false,
                                            modifier = Modifier.fillMaxSize(),
                                        )
                                    } else {
                                        Text(
                                            "…",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }

                                Column(Modifier.weight(1f)) {
                                    Text(
                                        text = if (cp.isNamedSnapshot) "★ ${cp.label}" else cp.label,
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                    val stats = buildString {
                                        append(ts)
                                        if (preview != null) {
                                            append(" · ${preview.strokeCount} strokes")
                                            if (addedIds.isNotEmpty()) append(" (+${addedIds.size})")
                                            if (removedCount > 0) append(" (-$removedCount)")
                                        }
                                    }
                                    Text(
                                        text = stats,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                TextButton(
                                    onClick = {
                                        showHistorySheet = false
                                        onRestoreCheckpoint(cp)
                                    },
                                ) {
                                    Text("Restore")
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }

    if (showSnapshotDialog) {
        var label by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showSnapshotDialog = false },
            title = { Text("Save Named Snapshot") },
            text = {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Label") },
                    placeholder = { Text("Rough inks done") },
                    singleLine = true,
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showSnapshotDialog = false
                        onSaveSnapshot(label.ifBlank { "Snapshot" })
                    },
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showSnapshotDialog = false }) { Text("Cancel") }
            },
        )
    }

    if (showRenameDialog) {
        var draftTitle by remember(doc.title) {
            mutableStateOf(if (doc.title == "Unnamed Sketch") "" else doc.title)
        }
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("Rename Sketch") },
            text = {
                OutlinedTextField(
                    value = draftTitle,
                    onValueChange = { draftTitle = it },
                    label = { Text("Name") },
                    placeholder = { Text("Unnamed Sketch") },
                    singleLine = true,
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showRenameDialog = false
                        onRename(draftTitle)
                    },
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) { Text("Cancel") }
            },
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

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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.CenterFocusStrong
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.ScreenRotationAlt
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import space.owork.draw.AppUiState
import space.owork.draw.engine.AppTheme
import space.owork.draw.engine.BackgroundPattern
import space.owork.draw.engine.CanvasBackground
import space.owork.draw.engine.CanvasMode
import space.owork.draw.engine.Document
import space.owork.draw.ui.theme.LocalHudColors
import kotlin.math.abs
import kotlin.math.roundToInt

private enum class DocumentModalTab(val label: String) {
    DOCUMENT("Document"),
    CANVAS("Canvas"),
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DocumentMenuModal(
    doc: Document,
    state: AppUiState,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit,
    onUpdateBackground: ((CanvasBackground) -> CanvasBackground) -> Unit,
    onToggleHidePattern: () -> Unit,
    onFitToView: () -> Unit,
    onResetRotation: () -> Unit,
    onOpenHistory: () -> Unit,
    onSaveSnapshot: (String) -> Unit,
    onExportPng: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val hudColors = LocalHudColors.current
    var activeTab by remember { mutableStateOf(DocumentModalTab.DOCUMENT) }
    var draftTitle by remember(doc.title) {
        mutableStateOf(if (doc.title == "Unnamed Sketch") "" else doc.title)
    }
    var snapshotDraft by remember { mutableStateOf("") }

    val modeLabel = when (val m = doc.canvasMode) {
        CanvasMode.Infinite -> "Infinite Canvas"
        is CanvasMode.Fixed -> "${m.widthPx} × ${m.heightPx} px · ${m.dpi} DPI"
    }
    val strokeCount = remember(doc) { doc.visibleStrokes().size }

    StudioModal(
        title = doc.title,
        subtitle = "$modeLabel · $strokeCount strokes",
        onDismiss = onDismiss,
        maxWidth = 600.dp,
        maxHeight = 620.dp,
    ) {
        // Recessed segmented tab switcher with concentric radii (14dp outer - 5dp pad = 9dp inner)
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            color = hudColors.modalTrack,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.75f)),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(5.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                DocumentModalTab.entries.forEach { tab ->
                    val selected = activeTab == tab
                    Surface(
                        onClick = { activeTab = tab },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(9.dp),
                        color = if (selected) hudColors.modalCard else Color.Transparent,
                        border = if (selected) BorderStroke(1.dp, MaterialTheme.colorScheme.outline) else null,
                    ) {
                        Box(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = tab.label,
                                style = MaterialTheme.typography.labelLarge,
                                color = if (selected) {
                                    MaterialTheme.colorScheme.onSurface
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (activeTab) {
                DocumentModalTab.DOCUMENT -> {
                    // Inline sketch rename
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedTextField(
                            value = draftTitle,
                            onValueChange = { draftTitle = it },
                            label = { Text("Sketch Name") },
                            placeholder = { Text("Unnamed Sketch") },
                            shape = RoundedCornerShape(12.dp),
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        val normalizedDraft = draftTitle.trim().ifEmpty { "Unnamed Sketch" }
                        FilledTonalButton(
                            enabled = normalizedDraft != doc.title,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.height(50.dp),
                            onClick = { onRename(draftTitle) },
                        ) {
                            Icon(Icons.Outlined.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Rename")
                        }
                    }

                    // Quick named snapshot
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedTextField(
                            value = snapshotDraft,
                            onValueChange = { snapshotDraft = it },
                            label = { Text("Save Named Snapshot") },
                            placeholder = { Text("e.g. Base colors done") },
                            shape = RoundedCornerShape(12.dp),
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        FilledTonalButton(
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.height(50.dp),
                            onClick = {
                                val label = snapshotDraft.trim().ifEmpty { "Snapshot" }
                                snapshotDraft = ""
                                onSaveSnapshot(label)
                            },
                        ) {
                            Icon(Icons.Outlined.BookmarkAdd, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Save")
                        }
                    }

                    HorizontalDivider()

                    // Action cards grid
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            ModalActionCard(
                                icon = Icons.Outlined.History,
                                title = "Version History",
                                subtitle = "${state.history.size} checkpoints",
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    onDismiss()
                                    onOpenHistory()
                                },
                            )
                            ModalActionCard(
                                icon = Icons.Outlined.Download,
                                title = "Export PNG",
                                subtitle = if (doc.canvasMode is CanvasMode.Fixed) "Full resolution" else "Fitted bounds",
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    onDismiss()
                                    onExportPng()
                                },
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            ModalActionCard(
                                icon = Icons.Outlined.CenterFocusStrong,
                                title = "Fit to View",
                                subtitle = "Zoom ${(state.cameraZoom * 100).roundToInt()}%",
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    onFitToView()
                                    onDismiss()
                                },
                            )
                            if (abs(state.cameraRotationRad) > 0.02f) {
                                ModalActionCard(
                                    icon = Icons.Outlined.ScreenRotationAlt,
                                    title = "Reset Rotation",
                                    subtitle = "Snap back to 0°",
                                    modifier = Modifier.weight(1f),
                                    onClick = {
                                        onResetRotation()
                                        onDismiss()
                                    },
                                )
                            } else {
                                ModalActionCard(
                                    icon = Icons.Outlined.Settings,
                                    title = "App Settings",
                                    subtitle = "AA, theme, stylus",
                                    modifier = Modifier.weight(1f),
                                    onClick = {
                                        onDismiss()
                                        onOpenSettings()
                                    },
                                )
                            }
                        }
                        if (abs(state.cameraRotationRad) > 0.02f) {
                            ModalActionCard(
                                icon = Icons.Outlined.Settings,
                                title = "App Settings",
                                subtitle = "Anti-aliasing, app theme, stylus & defaults",
                                modifier = Modifier.fillMaxWidth(),
                                onClick = {
                                    onDismiss()
                                    onOpenSettings()
                                },
                            )
                        }
                    }
                }

                DocumentModalTab.CANVAS -> {
                    val bg = doc.meta.background

                    // Per-canvas theme (applies ONLY to this sketch's background & surround)
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

                    // Custom paper color
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
                                    "Hides pattern without changing saved document settings",
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
                }
            }
        }
    }
}

@Composable
private fun ModalActionCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val hudColors = LocalHudColors.current
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = hudColors.modalCard,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.75f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

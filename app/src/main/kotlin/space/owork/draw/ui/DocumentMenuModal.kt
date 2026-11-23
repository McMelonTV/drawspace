package space.owork.draw.ui

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.CenterFocusStrong
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.ScreenRotationAlt
import androidx.compose.material.icons.outlined.Settings
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import space.owork.draw.AppUiState
import space.owork.draw.engine.AppTheme
import space.owork.draw.engine.BackgroundPattern
import space.owork.draw.engine.CanvasBackground
import space.owork.draw.engine.CanvasMode
import space.owork.draw.engine.Document
import space.owork.draw.ui.theme.LocalGlass
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
    val p = LocalGlass.current
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

    GlassModal(
        title = doc.title,
        subtitle = "$modeLabel · $strokeCount strokes",
        onDismiss = onDismiss,
        maxWidth = 540.dp,
        maxHeight = 620.dp,
    ) {
        GlassSegmentedTabs(
            options = DocumentModalTab.entries,
            selected = activeTab,
            labelOf = { it.label },
            onSelect = { activeTab = it },
        )

        Spacer(Modifier.height(16.dp))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            when (activeTab) {
                DocumentModalTab.DOCUMENT -> {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        GlassSectionLabel("Sketch Name")
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            GlassTextField(
                                value = draftTitle,
                                onValueChange = { draftTitle = it },
                                placeholder = "Unnamed Sketch",
                                modifier = Modifier.weight(1f),
                            )
                            val normalized = draftTitle.trim().ifEmpty { "Unnamed Sketch" }
                            GlassButton(
                                label = "Rename",
                                icon = Icons.Outlined.Check,
                                primary = normalized != doc.title,
                                enabled = normalized != doc.title,
                                onClick = { onRename(draftTitle) },
                            )
                        }
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        GlassSectionLabel("Named Snapshot")
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            GlassTextField(
                                value = snapshotDraft,
                                onValueChange = { snapshotDraft = it },
                                placeholder = "e.g. Base colors done",
                                modifier = Modifier.weight(1f),
                            )
                            GlassButton(
                                label = "Save",
                                icon = Icons.Outlined.BookmarkAdd,
                                onClick = {
                                    val label = snapshotDraft.trim().ifEmpty { "Snapshot" }
                                    snapshotDraft = ""
                                    onSaveSnapshot(label)
                                },
                            )
                        }
                    }

                    GlassDivider()

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            GlassMenuRow(
                                title = "Version History",
                                subtitle = "${state.history.size} checkpoints",
                                icon = Icons.Outlined.History,
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    onDismiss()
                                    onOpenHistory()
                                },
                            )
                            GlassMenuRow(
                                title = "Export PNG",
                                subtitle = if (doc.canvasMode is CanvasMode.Fixed) "Full resolution" else "Fitted bounds",
                                icon = Icons.Outlined.Download,
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    onDismiss()
                                    onExportPng()
                                },
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            GlassMenuRow(
                                title = "Fit to View",
                                subtitle = "Zoom ${(state.cameraZoom * 100).roundToInt()}%",
                                icon = Icons.Outlined.CenterFocusStrong,
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    onFitToView()
                                    onDismiss()
                                },
                            )
                            if (abs(state.cameraRotationRad) > 0.02f) {
                                GlassMenuRow(
                                    title = "Reset Rotation",
                                    subtitle = "Snap back to 0°",
                                    icon = Icons.Outlined.ScreenRotationAlt,
                                    modifier = Modifier.weight(1f),
                                    onClick = {
                                        onResetRotation()
                                        onDismiss()
                                    },
                                )
                            } else {
                                GlassMenuRow(
                                    title = "App Settings",
                                    subtitle = "AA, theme, stylus",
                                    icon = Icons.Outlined.Settings,
                                    modifier = Modifier.weight(1f),
                                    onClick = {
                                        onDismiss()
                                        onOpenSettings()
                                    },
                                )
                            }
                        }
                        if (abs(state.cameraRotationRad) > 0.02f) {
                            GlassMenuRow(
                                title = "App Settings",
                                subtitle = "Anti-aliasing, app theme, stylus & defaults",
                                icon = Icons.Outlined.Settings,
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

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        GlassSectionLabel("Canvas Theme (this sketch)")
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            GlassChip(
                                label = "Follow App (${state.appTheme.label})",
                                selected = bg.canvasTheme == null && bg.customPaperColorArgb == null,
                                onClick = {
                                    onUpdateBackground { it.copy(canvasTheme = null, customPaperColorArgb = null) }
                                },
                            )
                            AppTheme.entries.forEach { t ->
                                GlassChip(
                                    label = if (t == AppTheme.BLACK) "Black (AMOLED)" else t.label,
                                    selected = bg.canvasTheme == t && bg.customPaperColorArgb == null,
                                    onClick = {
                                        onUpdateBackground { it.copy(canvasTheme = t, customPaperColorArgb = null) }
                                    },
                                )
                            }
                        }
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        GlassSectionLabel("Custom Paper Color")
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
                                            color = if (selected) p.active else p.swatchBorder,
                                            shape = CircleShape,
                                        )
                                        .clickable { onUpdateBackground { it.copy(customPaperColorArgb = swatch.argb) } },
                                )
                            }
                        }
                    }

                    GlassDivider()

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        GlassSectionLabel("Background Pattern")
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            BackgroundPattern.entries.forEach { pat ->
                                GlassChip(
                                    label = pat.label,
                                    selected = bg.pattern == pat,
                                    onClick = { onUpdateBackground { it.copy(pattern = pat) } },
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
                                Text("Temporarily hide pattern", color = p.text, fontSize = 14.sp)
                                Text(
                                    "Keeps your saved grid setup intact",
                                    color = p.secondary,
                                    fontSize = 12.sp,
                                )
                            }
                            GlassToggle(
                                checked = state.hidePatternTemporarily,
                                onCheckedChange = { onToggleHidePattern() },
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(20.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("Spacing · ${bg.spacingPx.roundToInt()} px", color = p.secondary, fontSize = 12.sp)
                                GlassSlider(
                                    value = bg.spacingPx,
                                    onValueChange = { v -> onUpdateBackground { it.copy(spacingPx = v) } },
                                    valueRange = 20f..160f,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("Opacity · ${(bg.opacity * 100).roundToInt()}%", color = p.secondary, fontSize = 12.sp)
                                GlassSlider(
                                    value = bg.opacity,
                                    onValueChange = { v -> onUpdateBackground { it.copy(opacity = v) } },
                                    valueRange = 0.05f..0.85f,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }

                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            GlassSectionLabel("Pattern Color")
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                GlassChip(
                                    label = "Auto",
                                    selected = bg.patternColorArgb == null,
                                    onClick = { onUpdateBackground { it.copy(patternColorArgb = null) } },
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
                                                color = if (selected) p.active else p.swatchBorder,
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
                            Text("Show origin ring", color = p.text, fontSize = 14.sp)
                            GlassToggle(
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

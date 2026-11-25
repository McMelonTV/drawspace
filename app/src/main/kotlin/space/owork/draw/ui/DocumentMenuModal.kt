package space.owork.draw.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.CenterFocusStrong
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.ScreenRotationAlt
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import space.owork.draw.AppUiState
import space.owork.draw.engine.AppTheme
import space.owork.draw.engine.BackgroundPattern
import space.owork.draw.engine.CanvasBackground
import space.owork.draw.engine.CanvasMode
import space.owork.draw.engine.Document
import space.owork.draw.storage.DocumentSummary
import space.owork.draw.ui.theme.LocalGlass
import space.owork.draw.ui.theme.glass
import kotlin.math.abs
import kotlin.math.roundToInt

private enum class DocumentModalTab(val label: String) {
    ACTIONS("Actions"),
    CANVAS("Canvas"),
    EXPORT("Export"),
}

/**
 * tvOS QuickActions-inspired two-column document modal:
 * Left: lifted live sketch thumbnail + tap-to-rename title & metadata.
 * Right: frosted glass panel with concentric Actions / Canvas / Export tabs and clean borderless rows.
 */
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
    onSavePngToPhotos: (transparentBackground: Boolean) -> Unit,
    onSharePng: (transparentBackground: Boolean) -> Unit,
    onSaveSvg: (transparentBackground: Boolean) -> Unit,
    onShareSvg: (transparentBackground: Boolean) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val p = LocalGlass.current
    var activeTab by remember { mutableStateOf(DocumentModalTab.ACTIONS) }
    var editingTitle by remember { mutableStateOf(false) }
    var draftTitle by remember(doc.title) {
        mutableStateOf(if (doc.title == "Unnamed Sketch") "" else doc.title)
    }
    var showingSnapshotInput by remember { mutableStateOf(false) }
    var snapshotDraft by remember { mutableStateOf("") }
    var exportTransparent by remember { mutableStateOf(false) }

    val summary = remember(doc) {
        val visible = doc.visibleStrokes()
        DocumentSummary(
            meta = doc.meta,
            strokeCount = visible.size,
            hasUncommittedChanges = false,
            previewStrokes = visible,
            contentBounds = doc.contentBounds(),
        )
    }
    val modeLabel = when (val m = doc.canvasMode) {
        CanvasMode.Infinite -> "Infinite Canvas"
        is CanvasMode.Fixed -> "${m.widthPx} × ${m.heightPx} px · ${m.dpi} DPI"
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        val outerShape = RoundedCornerShape(26.dp)
        Row(
            modifier = Modifier
                .padding(horizontal = 24.dp, vertical = 20.dp)
                .widthIn(min = 520.dp, max = 720.dp)
                .heightIn(max = 580.dp)
                .fillMaxWidth()
                .shadow(36.dp, outerShape, clip = false)
                .glass(outerShape, p, fill = p.material)
                .padding(20.dp),
            horizontalArrangement = Arrangement.spacedBy(22.dp),
            verticalAlignment = Alignment.Top,
        ) {
            // Left preview & identity column (inspired by tvtube QuickActionsDialog)
            Column(
                modifier = Modifier.width(236.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val previewShape = RoundedCornerShape(16.dp)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 10f)
                        .shadow(16.dp, previewShape, clip = false)
                        .clip(previewShape)
                        .border(1.dp, p.hairline, previewShape),
                ) {
                    DocumentThumbnailCanvas(
                        summary = summary,
                        appTheme = state.appTheme,
                        showPattern = false,
                        fixedArtboardPaddingDp = 8f,
                        modifier = Modifier.fillMaxSize(),
                    )
                }

                if (editingTitle) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        GlassTextField(
                            value = draftTitle,
                            onValueChange = { draftTitle = it },
                            placeholder = "Unnamed Sketch",
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            GlassButton(
                                label = "Save",
                                icon = Icons.Outlined.Check,
                                primary = true,
                                height = 34.dp,
                                onClick = {
                                    onRename(draftTitle)
                                    editingTitle = false
                                },
                            )
                            GlassButton(
                                label = "Cancel",
                                height = 34.dp,
                                onClick = {
                                    draftTitle = if (doc.title == "Unnamed Sketch") "" else doc.title
                                    editingTitle = false
                                },
                            )
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = { editingTitle = true },
                            )
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = doc.title,
                            color = p.text,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = (-0.25).sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(
                            Icons.Outlined.Edit,
                            contentDescription = "Rename sketch",
                            tint = p.secondary,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }

                Text(
                    text = "$modeLabel\n${summary.strokeCount} strokes · ${state.saveStatus}",
                    color = p.secondary,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                )
            }

            // Right interactive panel: concentric segmented tabs + clean list / canvas controls
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    GlassSegmentedTabs(
                        options = DocumentModalTab.entries,
                        selected = activeTab,
                        labelOf = { it.label },
                        onSelect = { activeTab = it },
                        modifier = Modifier.weight(1f),
                    )
                    GlassIconButton(
                        icon = Icons.Outlined.Close,
                        contentDescription = "Close",
                        size = 38.dp,
                        onClick = onDismiss,
                    )
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    when (activeTab) {
                        DocumentModalTab.ACTIONS -> {
                            if (showingSnapshotInput) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(bottom = 4.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    GlassTextField(
                                        value = snapshotDraft,
                                        onValueChange = { snapshotDraft = it },
                                        placeholder = "Snapshot label…",
                                        modifier = Modifier.weight(1f),
                                    )
                                    GlassButton(
                                        label = "Save",
                                        primary = true,
                                        height = 40.dp,
                                        onClick = {
                                            val label = snapshotDraft.trim().ifEmpty { "Snapshot" }
                                            snapshotDraft = ""
                                            showingSnapshotInput = false
                                            onSaveSnapshot(label)
                                        },
                                    )
                                }
                            } else {
                                GlassMenuRow(
                                    title = "Save Named Snapshot",
                                    icon = Icons.Outlined.BookmarkAdd,
                                    onClick = { showingSnapshotInput = true },
                                )
                            }

                            GlassMenuRow(
                                title = "Version History",
                                detail = "${state.history.size}",
                                icon = Icons.Outlined.History,
                                onClick = {
                                    onDismiss()
                                    onOpenHistory()
                                },
                            )
                            GlassMenuRow(
                                title = "Export & Share",
                                detail = "PNG · SVG",
                                icon = Icons.Outlined.Share,
                                onClick = { activeTab = DocumentModalTab.EXPORT },
                            )

                            GlassDivider(Modifier.padding(vertical = 4.dp))

                            GlassMenuRow(
                                title = "Fit to View",
                                detail = "${(state.cameraZoom * 100).roundToInt()}%",
                                icon = Icons.Outlined.CenterFocusStrong,
                                onClick = {
                                    onFitToView()
                                    onDismiss()
                                },
                            )
                            if (abs(state.cameraRotationRad) > 0.02f) {
                                GlassMenuRow(
                                    title = "Reset Rotation",
                                    detail = "0°",
                                    icon = Icons.Outlined.ScreenRotationAlt,
                                    onClick = {
                                        onResetRotation()
                                        onDismiss()
                                    },
                                )
                            }

                            GlassDivider(Modifier.padding(vertical = 4.dp))

                            GlassMenuRow(
                                title = "App Settings",
                                detail = "AA, theme, stylus",
                                icon = Icons.Outlined.Settings,
                                onClick = {
                                    onDismiss()
                                    onOpenSettings()
                                },
                            )
                        }

                        DocumentModalTab.CANVAS -> {
                            val bg = doc.meta.background
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    GlassSectionLabel("Canvas Theme")
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
                                                label = if (t == AppTheme.BLACK) "Black" else t.label,
                                                selected = bg.canvasTheme == t && bg.customPaperColorArgb == null,
                                                onClick = {
                                                    onUpdateBackground { it.copy(canvasTheme = t, customPaperColorArgb = null) }
                                                },
                                            )
                                        }
                                    }
                                }

                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    GlassSectionLabel("Paper Tint")
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
                                                    .clickable(
                                                        interactionSource = remember { MutableInteractionSource() },
                                                        indication = null,
                                                    ) { onUpdateBackground { it.copy(customPaperColorArgb = swatch.argb) } },
                                            )
                                        }
                                    }
                                }

                                GlassDivider()

                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    GlassSectionLabel("Pattern")
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
                                        Text("Temporarily hide pattern", color = p.text, fontSize = 13.5.sp)
                                        GlassToggle(
                                            checked = state.hidePatternTemporarily,
                                            onCheckedChange = { onToggleHidePattern() },
                                        )
                                    }

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                            Text("Spacing · ${bg.spacingPx.roundToInt()}px", color = p.secondary, fontSize = 11.5.sp)
                                            GlassSlider(
                                                value = bg.spacingPx,
                                                onValueChange = { v -> onUpdateBackground { it.copy(spacingPx = v) } },
                                                valueRange = 20f..160f,
                                                modifier = Modifier.fillMaxWidth(),
                                            )
                                        }
                                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                            Text("Opacity · ${(bg.opacity * 100).roundToInt()}%", color = p.secondary, fontSize = 11.5.sp)
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
                                                        .size(if (selected) 28.dp else 24.dp)
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
                                                        ) { onUpdateBackground { it.copy(patternColorArgb = swatch.argb) } },
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
                                        Text("Show origin ring", color = p.text, fontSize = 13.5.sp)
                                        GlassToggle(
                                            checked = bg.showOriginMarker,
                                            onCheckedChange = { checked -> onUpdateBackground { it.copy(showOriginMarker = checked) } },
                                        )
                                    }
                                }
                            }
                        }

                        DocumentModalTab.EXPORT -> {
                            val bgDetail = if (exportTransparent) "Transparent" else "With paper"
                            val pngSizeDetail = if (doc.canvasMode is CanvasMode.Fixed) "Full res" else "Up to 2560px"
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 4.dp, vertical = 2.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                                        Text("Transparent background", color = p.text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                        Text(
                                            "Omit paper background in exported PNG & SVG.",
                                            color = p.secondary,
                                            fontSize = 11.5.sp,
                                        )
                                    }
                                    GlassToggle(
                                        checked = exportTransparent,
                                        onCheckedChange = { exportTransparent = it },
                                    )
                                }

                                GlassDivider(Modifier.padding(vertical = 2.dp))
                                GlassSectionLabel("PNG Image · $pngSizeDetail")

                                GlassMenuRow(
                                    title = "Save PNG to Photos",
                                    detail = "Pictures/Drawspace",
                                    icon = Icons.Outlined.PhotoLibrary,
                                    onClick = {
                                        val transparent = exportTransparent
                                        onDismiss()
                                        onSavePngToPhotos(transparent)
                                    },
                                )
                                GlassMenuRow(
                                    title = "Share PNG",
                                    detail = bgDetail,
                                    icon = Icons.Outlined.Share,
                                    onClick = {
                                        val transparent = exportTransparent
                                        onDismiss()
                                        onSharePng(transparent)
                                    },
                                )

                                GlassDivider(Modifier.padding(vertical = 2.dp))
                                GlassSectionLabel("SVG Vector")

                                GlassMenuRow(
                                    title = "Save SVG…",
                                    detail = bgDetail,
                                    icon = Icons.Outlined.Download,
                                    onClick = {
                                        val transparent = exportTransparent
                                        onDismiss()
                                        onSaveSvg(transparent)
                                    },
                                )
                                GlassMenuRow(
                                    title = "Share SVG",
                                    detail = bgDetail,
                                    icon = Icons.Outlined.Share,
                                    onClick = {
                                        val transparent = exportTransparent
                                        onDismiss()
                                        onShareSvg(transparent)
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

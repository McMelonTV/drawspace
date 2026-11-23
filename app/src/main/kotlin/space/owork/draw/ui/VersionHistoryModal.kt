package space.owork.draw.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import space.owork.draw.AppUiState
import space.owork.draw.storage.CheckpointInfo
import space.owork.draw.ui.theme.LocalGlass
import space.owork.draw.ui.theme.glassSurface
import java.text.DateFormat
import java.util.Date

@Composable
fun VersionHistoryModal(
    state: AppUiState,
    onDismiss: () -> Unit,
    onSaveSnapshot: (String) -> Unit,
    onRestoreCheckpoint: (CheckpointInfo) -> Unit,
    onRequestCheckpointPreview: (String) -> Unit,
) {
    val p = LocalGlass.current
    var snapshotLabel by remember { mutableStateOf("") }

    GlassModal(
        title = "Version History",
        subtitle = "${state.history.size} saved checkpoints · added strokes highlighted",
        onDismiss = onDismiss,
        maxWidth = 560.dp,
        maxHeight = 620.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GlassTextField(
                value = snapshotLabel,
                onValueChange = { snapshotLabel = it },
                placeholder = "Name a new snapshot…",
                modifier = Modifier.weight(1f),
            )
            GlassButton(
                label = "Save",
                icon = Icons.Outlined.BookmarkAdd,
                primary = snapshotLabel.isNotBlank(),
                onClick = {
                    val label = snapshotLabel.trim().ifEmpty { "Snapshot" }
                    snapshotLabel = ""
                    onSaveSnapshot(label)
                },
            )
        }

        Spacer(Modifier.height(14.dp))

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 420.dp),
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
                val cardShape = RoundedCornerShape(14.dp)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .glassSurface(cardShape, p, fill = p.glass, bordered = false)
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(width = 92.dp, height = 58.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .border(1.dp, p.separator, RoundedCornerShape(8.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (preview != null) {
                            DocumentThumbnailCanvas(
                                summary = preview,
                                appTheme = state.appTheme,
                                highlightStrokeIds = addedIds,
                                highlightColor = Color(0xFF5AC8FA),
                                fixedArtboardPaddingDp = 4f,
                                showPattern = false,
                                modifier = Modifier.fillMaxSize(),
                            )
                        } else {
                            Text("…", color = p.secondary, fontSize = 11.sp)
                        }
                    }

                    Column(Modifier.weight(1f)) {
                        Text(
                            text = if (cp.isNamedSnapshot) "★ ${cp.label}" else cp.label,
                            color = p.text,
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.Medium,
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
                            color = p.secondary,
                            fontSize = 11.5.sp,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }

                    GlassButton(
                        label = "Restore",
                        height = 34.dp,
                        onClick = {
                            onDismiss()
                            onRestoreCheckpoint(cp)
                        },
                    )
                }
            }
        }
    }
}

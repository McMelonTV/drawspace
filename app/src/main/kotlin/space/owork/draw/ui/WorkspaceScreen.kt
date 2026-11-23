package space.owork.draw.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AllInclusive
import androidx.compose.material.icons.outlined.CropLandscape
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Settings
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
import space.owork.draw.AppUiState
import space.owork.draw.engine.AppTheme
import space.owork.draw.engine.CanvasMode
import space.owork.draw.storage.DocumentSummary
import space.owork.draw.ui.theme.LocalGlass
import space.owork.draw.ui.theme.glass
import java.text.DateFormat
import java.util.Date

@Composable
fun WorkspaceScreen(
    state: AppUiState,
    onOpenSettings: () -> Unit,
    onOpenDocument: (String) -> Unit,
    onCreateDocument: (String, CanvasMode) -> Unit,
    onDeleteDocument: (String) -> Unit,
) {
    val p = LocalGlass.current
    var showNewDialog by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<DocumentSummary?>(null) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(p.background),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(horizontal = 28.dp, vertical = 18.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Text(
                        text = "Drawspace",
                        color = p.text,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = (-0.7).sp,
                    )
                    Text(
                        text = "${state.documents.size} sketch${if (state.documents.size == 1) "" else "es"}",
                        color = p.secondary,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    GlassButton(
                        label = "Quick Infinite",
                        icon = Icons.Outlined.AllInclusive,
                        onClick = { onCreateDocument("Unnamed Sketch", CanvasMode.Infinite) },
                    )
                    GlassButton(
                        label = "New Sketch",
                        icon = Icons.Outlined.Add,
                        primary = true,
                        onClick = { showNewDialog = true },
                    )
                    GlassIconButton(
                        icon = Icons.Outlined.Settings,
                        contentDescription = "Settings",
                        size = 40.dp,
                        onClick = onOpenSettings,
                    )
                }
            }

            Spacer(Modifier.height(24.dp))

            if (state.documents.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Text(
                            text = "Start sketching on an infinite canvas or a fixed artboard.",
                            color = p.secondary,
                            fontSize = 15.sp,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            GlassButton(
                                label = "Quick Infinite Canvas",
                                icon = Icons.Outlined.AllInclusive,
                                primary = true,
                                onClick = { onCreateDocument("Unnamed Sketch", CanvasMode.Infinite) },
                            )
                            GlassButton(
                                label = "Choose Fixed Size…",
                                icon = Icons.Outlined.CropLandscape,
                                onClick = { showNewDialog = true },
                            )
                        }
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 256.dp),
                    contentPadding = PaddingValues(bottom = 28.dp),
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    items(state.documents, key = { it.meta.id }) { summary ->
                        DocumentCard(
                            summary = summary,
                            appTheme = state.appTheme,
                            onClick = { onOpenDocument(summary.meta.id) },
                            onDelete = { pendingDelete = summary },
                        )
                    }
                }
            }
        }
    }

    if (showNewDialog) {
        NewSketchDialog(
            onDismiss = { showNewDialog = false },
            onCreate = { title, mode ->
                showNewDialog = false
                onCreateDocument(title, mode)
            },
        )
    }

    pendingDelete?.let { doc ->
        GlassConfirmModal(
            title = "Delete \"${doc.meta.title}\"?",
            message = "This removes the sketch from your workspace.",
            confirmLabel = "Delete",
            destructive = true,
            onConfirm = {
                pendingDelete = null
                onDeleteDocument(doc.meta.id)
            },
            onDismiss = { pendingDelete = null },
        )
    }
}

@Composable
private fun DocumentCard(
    summary: DocumentSummary,
    appTheme: AppTheme,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    val p = LocalGlass.current
    val modeLabel = when (val mode = summary.meta.canvasMode) {
        CanvasMode.Infinite -> "Infinite"
        is CanvasMode.Fixed -> "${mode.widthPx} × ${mode.heightPx}"
    }
    val dateText = remember(summary.meta.updatedAtMs) {
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
            .format(Date(summary.meta.updatedAtMs))
    }
    val cardShape = RoundedCornerShape(18.dp)

    Column(
        modifier = Modifier
            .shadow(elevation = if (p.isDark) 18.dp else 8.dp, shape = cardShape, clip = false)
            .glass(cardShape, p, fill = p.elevated, edge = true)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(156.dp),
            contentAlignment = Alignment.BottomStart,
        ) {
            DocumentThumbnailCanvas(
                summary = summary,
                appTheme = appTheme,
                showPattern = false,
                modifier = Modifier.fillMaxSize(),
            )

            Row(
                modifier = Modifier
                    .padding(10.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.65f))
                    .padding(horizontal = 9.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(
                    imageVector = if (summary.meta.canvasMode == CanvasMode.Infinite) {
                        Icons.Outlined.AllInclusive
                    } else {
                        Icons.Outlined.CropLandscape
                    },
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(13.dp),
                )
                Text(
                    text = modeLabel,
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, top = 11.dp, end = 10.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = summary.meta.title,
                    color = p.text,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = (-0.15).sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${summary.strokeCount} strokes · $dateText",
                    color = p.secondary,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            GlassIconButton(
                icon = Icons.Outlined.DeleteOutline,
                contentDescription = "Delete sketch",
                size = 34.dp,
                subtle = true,
                onClick = onDelete,
            )
        }
    }
}

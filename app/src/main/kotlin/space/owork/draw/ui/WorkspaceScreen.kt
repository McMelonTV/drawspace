package space.owork.draw.ui

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AllInclusive
import androidx.compose.material.icons.outlined.CropLandscape
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import space.owork.draw.AppUiState
import space.owork.draw.engine.AppTheme
import space.owork.draw.engine.CanvasMode
import space.owork.draw.storage.DocumentSummary
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
    var showNewDialog by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<DocumentSummary?>(null) }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(horizontal = 24.dp, vertical = 16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Text(
                        text = "Drawspace",
                        style = MaterialTheme.typography.headlineMedium,
                    )
                    Text(
                        text = "${state.documents.size} sketch${if (state.documents.size == 1) "" else "es"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FilledTonalButton(
                        onClick = { onCreateDocument("Unnamed Sketch", CanvasMode.Infinite) },
                    ) {
                        Icon(Icons.Outlined.AllInclusive, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Quick Infinite")
                    }

                    Button(onClick = { showNewDialog = true }) {
                        Icon(Icons.Outlined.Add, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("New Sketch")
                    }

                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Outlined.Settings, contentDescription = "Settings")
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            if (state.documents.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            text = "Start sketching on an infinite canvas or a fixed artboard.",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(onClick = { onCreateDocument("Unnamed Sketch", CanvasMode.Infinite) }) {
                                Icon(Icons.Outlined.AllInclusive, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("Quick Infinite Canvas")
                            }
                            FilledTonalButton(onClick = { showNewDialog = true }) {
                                Icon(Icons.Outlined.CropLandscape, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("Choose Fixed Size…")
                            }
                        }
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 240.dp),
                    contentPadding = PaddingValues(bottom = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
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
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete \"${doc.meta.title}\"?") },
            text = { Text("This removes the sketch from your workspace.") },
            confirmButton = {
                Button(
                    onClick = {
                        pendingDelete = null
                        onDeleteDocument(doc.meta.id)
                    },
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            },
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
    val modeLabel = when (val mode = summary.meta.canvasMode) {
        CanvasMode.Infinite -> "Infinite"
        is CanvasMode.Fixed -> "${mode.widthPx} × ${mode.heightPx}"
    }
    val dateText = remember(summary.meta.updatedAtMs) {
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
            .format(Date(summary.meta.updatedAtMs))
    }

    Card(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(150.dp),
                contentAlignment = Alignment.BottomStart,
            ) {
                DocumentThumbnailCanvas(
                    summary = summary,
                    appTheme = appTheme,
                    showPattern = false,
                    modifier = Modifier.fillMaxSize(),
                )

                Surface(
                    modifier = Modifier.padding(10.dp),
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xCC181B21),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
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
                            modifier = Modifier.size(14.dp),
                        )
                        Text(
                            text = modeLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White,
                        )
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 14.dp, top = 10.dp, end = 6.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = summary.meta.title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "${summary.strokeCount} strokes · $dateText",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Outlined.DeleteOutline,
                        contentDescription = "Delete sketch",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

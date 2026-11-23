package space.owork.draw.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AllInclusive
import androidx.compose.material.icons.outlined.CropLandscape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import space.owork.draw.engine.CanvasMode
import space.owork.draw.engine.CanvasPreset
import space.owork.draw.engine.CanvasPresets

@Composable
fun NewSketchDialog(
    onDismiss: () -> Unit,
    onCreate: (title: String, mode: CanvasMode) -> Unit,
) {
    var title by remember { mutableStateOf("") }
    var isInfinite by remember { mutableStateOf(true) }
    var selectedPreset by remember { mutableStateOf<CanvasPreset?>(CanvasPresets.defaultPresets.first()) }
    var customWidth by remember { mutableStateOf("2560") }
    var customHeight by remember { mutableStateOf("1600") }
    var customDpi by remember { mutableStateOf("300") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New Sketch") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = isInfinite,
                        onClick = { isInfinite = true },
                        label = { Text("Infinite Canvas") },
                        leadingIcon = { Icon(Icons.Outlined.AllInclusive, contentDescription = null) },
                    )
                    FilterChip(
                        selected = !isInfinite,
                        onClick = { isInfinite = false },
                        label = { Text("Fixed Size") },
                        leadingIcon = { Icon(Icons.Outlined.CropLandscape, contentDescription = null) },
                    )
                }

                if (isInfinite) {
                    Text(
                        text = "Boundless world-space canvas with an adaptive dot grid. Pan and zoom freely in any direction.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        text = "Presets",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 220.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(CanvasPresets.defaultPresets) { preset ->
                            val selected = selectedPreset?.id == preset.id
                            Card(
                                onClick = {
                                    selectedPreset = preset
                                    customWidth = preset.widthPx.toString()
                                    customHeight = preset.heightPx.toString()
                                    customDpi = preset.dpi.toString()
                                },
                                shape = RoundedCornerShape(10.dp),
                                border = if (selected) {
                                    BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
                                } else {
                                    BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                                },
                                colors = CardDefaults.cardColors(
                                    containerColor = if (selected) {
                                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                                    } else {
                                        MaterialTheme.colorScheme.surfaceVariant
                                    },
                                ),
                            ) {
                                Column(Modifier.padding(10.dp)) {
                                    Text(preset.label, style = MaterialTheme.typography.labelLarge)
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        preset.subtitle,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedTextField(
                            value = customWidth,
                            onValueChange = {
                                customWidth = it.filter(Char::isDigit)
                                selectedPreset = null
                            },
                            label = { Text("W (px)") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = customHeight,
                            onValueChange = {
                                customHeight = it.filter(Char::isDigit)
                                selectedPreset = null
                            },
                            label = { Text("H (px)") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = customDpi,
                            onValueChange = {
                                customDpi = it.filter(Char::isDigit)
                                selectedPreset = null
                            },
                            label = { Text("DPI") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(0.8f),
                        )
                    }
                }

                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Name (optional)") },
                    placeholder = { Text("Unnamed Sketch") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val mode = if (isInfinite) {
                        CanvasMode.Infinite
                    } else {
                        val w = customWidth.toIntOrNull()?.coerceIn(64, 16384) ?: 2560
                        val h = customHeight.toIntOrNull()?.coerceIn(64, 16384) ?: 1600
                        val dpi = customDpi.toIntOrNull()?.coerceIn(72, 1200) ?: 300
                        CanvasMode.Fixed(w, h, dpi)
                    }
                    onCreate(title.trim().ifEmpty { "Unnamed Sketch" }, mode)
                },
            ) {
                Text("Create")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

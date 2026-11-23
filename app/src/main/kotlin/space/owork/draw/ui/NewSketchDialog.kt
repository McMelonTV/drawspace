package space.owork.draw.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import space.owork.draw.engine.CanvasMode
import space.owork.draw.engine.CanvasPreset
import space.owork.draw.engine.CanvasPresets
import space.owork.draw.ui.theme.LocalGlass
import space.owork.draw.ui.theme.glassSurface

private enum class NewCanvasTab(val label: String) {
    INFINITE("Infinite Canvas"),
    FIXED("Fixed Size"),
}

@Composable
fun NewSketchDialog(
    onDismiss: () -> Unit,
    onCreate: (title: String, mode: CanvasMode) -> Unit,
) {
    val p = LocalGlass.current
    var title by remember { mutableStateOf("") }
    var tab by remember { mutableStateOf(NewCanvasTab.INFINITE) }
    var selectedPreset by remember { mutableStateOf<CanvasPreset?>(CanvasPresets.defaultPresets.first()) }
    var customWidth by remember { mutableStateOf("2560") }
    var customHeight by remember { mutableStateOf("1600") }
    var customDpi by remember { mutableStateOf("300") }

    GlassModal(
        title = "New Sketch",
        onDismiss = onDismiss,
        maxWidth = 540.dp,
        maxHeight = 620.dp,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            GlassSegmentedTabs(
                options = NewCanvasTab.entries,
                selected = tab,
                labelOf = { it.label },
                onSelect = { tab = it },
            )

            if (tab == NewCanvasTab.INFINITE) {
                Text(
                    text = "Boundless world-space canvas with an adaptive dot grid. Pan and zoom freely in any direction.",
                    color = p.secondary,
                    fontSize = 13.5.sp,
                    lineHeight = 20.sp,
                )
            } else {
                GlassSectionLabel("Presets")
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
                        val shape = RoundedCornerShape(14.dp)
                        Column(
                            modifier = Modifier
                                .glassSurface(shape, p, fill = if (selected) p.active else p.glass, bordered = false)
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                ) {
                                    selectedPreset = preset
                                    customWidth = preset.widthPx.toString()
                                    customHeight = preset.heightPx.toString()
                                    customDpi = preset.dpi.toString()
                                }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                        ) {
                            Text(
                                text = preset.label,
                                color = if (selected) p.onActive else p.text,
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = preset.subtitle,
                                color = if (selected) p.onActive.copy(alpha = 0.7f) else p.secondary,
                                fontSize = 11.5.sp,
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Width (px)", color = p.secondary, fontSize = 11.sp)
                        GlassTextField(
                            value = customWidth,
                            onValueChange = {
                                customWidth = it.filter(Char::isDigit)
                                selectedPreset = null
                            },
                            placeholder = "2560",
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        )
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Height (px)", color = p.secondary, fontSize = 11.sp)
                        GlassTextField(
                            value = customHeight,
                            onValueChange = {
                                customHeight = it.filter(Char::isDigit)
                                selectedPreset = null
                            },
                            placeholder = "1600",
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        )
                    }
                    Column(Modifier.weight(0.75f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("DPI", color = p.secondary, fontSize = 11.sp)
                        GlassTextField(
                            value = customDpi,
                            onValueChange = {
                                customDpi = it.filter(Char::isDigit)
                                selectedPreset = null
                            },
                            placeholder = "300",
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        )
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                GlassSectionLabel("Name (optional)")
                GlassTextField(
                    value = title,
                    onValueChange = { title = it },
                    placeholder = "Unnamed Sketch",
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
            ) {
                GlassButton(label = "Cancel", onClick = onDismiss)
                GlassButton(
                    label = "Create",
                    primary = true,
                    onClick = {
                        val mode = if (tab == NewCanvasTab.INFINITE) {
                            CanvasMode.Infinite
                        } else {
                            val w = customWidth.toIntOrNull()?.coerceIn(64, 16384) ?: 2560
                            val h = customHeight.toIntOrNull()?.coerceIn(64, 16384) ?: 1600
                            val dpi = customDpi.toIntOrNull()?.coerceIn(72, 1200) ?: 300
                            CanvasMode.Fixed(w, h, dpi)
                        }
                        onCreate(title.trim().ifEmpty { "Unnamed Sketch" }, mode)
                    },
                )
            }
        }
    }
}

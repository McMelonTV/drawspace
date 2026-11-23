package space.owork.draw.ui

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import space.owork.draw.engine.AntiAliasingMode
import space.owork.draw.engine.AppSettings
import space.owork.draw.engine.AppTheme
import space.owork.draw.engine.BackgroundPattern
import space.owork.draw.engine.CanvasLockMode
import space.owork.draw.engine.StrokeSmoothingLevel
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    onBack: () -> Unit,
    onUpdateSettings: ((AppSettings) -> AppSettings) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding(),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 760.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                    }
                    Column {
                        Text("Settings", style = MaterialTheme.typography.headlineSmall)
                        Text(
                            "Global app preferences & rendering defaults",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                // 1. App Appearance
                SettingsSection(title = "Appearance") {
                    Text(
                        "App Theme",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        "Controls app chrome and acts as the default canvas theme for sketches set to Follow App.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        AppTheme.entries.forEach { t ->
                            FilterChip(
                                selected = settings.appTheme == t,
                                onClick = { onUpdateSettings { it.copy(appTheme = t) } },
                                label = {
                                    Text(if (t == AppTheme.BLACK) "Black (AMOLED)" else t.label)
                                },
                            )
                        }
                    }
                }

                // 2. Rendering & Line Quality
                SettingsSection(title = "Rendering & Line Quality") {
                    Text("Line Anti-Aliasing", style = MaterialTheme.typography.titleSmall)
                    Text(
                        settings.antiAliasing.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        AntiAliasingMode.entries.forEach { mode ->
                            FilterChip(
                                selected = settings.antiAliasing == mode,
                                onClick = { onUpdateSettings { it.copy(antiAliasing = mode) } },
                                label = { Text(mode.label) },
                            )
                        }
                    }

                    HorizontalDivider(Modifier.padding(vertical = 4.dp))

                    Text("Stroke Smoothing", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Reduces micro-jitter on thin stylus strokes while preserving responsiveness.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        StrokeSmoothingLevel.entries.forEach { lvl ->
                            FilterChip(
                                selected = settings.strokeSmoothing == lvl,
                                onClick = { onUpdateSettings { it.copy(strokeSmoothing = lvl) } },
                                label = { Text(lvl.label) },
                            )
                        }
                    }

                    HorizontalDivider(Modifier.padding(vertical = 4.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                            Text("Round Turns & Line Ends", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "Trims stylus lift hooks and applies corner-rounding on pen-up so zoomed-in strokes stay smooth.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = settings.roundTurnsAndEnds,
                            onCheckedChange = { enabled ->
                                onUpdateSettings { it.copy(roundTurnsAndEnds = enabled) }
                            },
                        )
                    }
                }

                // 3. Stylus & Navigation
                SettingsSection(title = "Stylus & Navigation") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                            Text("Stylus-Only Mode by Default", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "Disables finger drawing by default so only the stylus draws. Two-finger pan/zoom/rotate still works.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = settings.stylusOnlyDefault,
                            onCheckedChange = { enabled ->
                                onUpdateSettings { it.copy(stylusOnlyDefault = enabled) }
                            },
                        )
                    }

                    HorizontalDivider(Modifier.padding(vertical = 4.dp))

                    Text("Default Canvas Lock Mode", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Quick-toggled on the canvas toolbar.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        CanvasLockMode.entries.forEach { mode ->
                            FilterChip(
                                selected = settings.defaultLockMode == mode,
                                onClick = { onUpdateSettings { it.copy(defaultLockMode = mode) } },
                                label = { Text(mode.label) },
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                            Text("Lock Panning", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "Prevents 2-finger panning across the canvas.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = settings.lockPan,
                            onCheckedChange = { locked ->
                                onUpdateSettings { it.copy(lockPan = locked) }
                            },
                        )
                    }
                }

                // 4. New Canvas Defaults
                SettingsSection(title = "New Canvas Defaults") {
                    Text("Default Background Pattern", style = MaterialTheme.typography.titleSmall)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        BackgroundPattern.entries.forEach { pat ->
                            FilterChip(
                                selected = settings.defaultPattern == pat,
                                onClick = { onUpdateSettings { it.copy(defaultPattern = pat) } },
                                label = { Text(pat.label) },
                            )
                        }
                    }

                    if (settings.defaultPattern != BackgroundPattern.BLANK) {
                        Column {
                            Text(
                                "Default Pattern Spacing: ${settings.defaultPatternSpacingPx.roundToInt()} px",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Slider(
                                value = settings.defaultPatternSpacingPx,
                                onValueChange = { v -> onUpdateSettings { it.copy(defaultPatternSpacingPx = v) } },
                                valueRange = 20f..160f,
                            )
                        }
                    }
                }

                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            content()
        }
    }
}

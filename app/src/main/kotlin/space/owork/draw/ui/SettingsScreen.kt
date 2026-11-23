package space.owork.draw.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import space.owork.draw.R
import space.owork.draw.engine.AntiAliasingMode
import space.owork.draw.engine.AppSettings
import space.owork.draw.engine.AppTheme
import space.owork.draw.engine.BackgroundPattern
import space.owork.draw.engine.CanvasLockMode
import space.owork.draw.engine.StrokeSmoothingLevel
import space.owork.draw.ui.theme.LocalGlass
import space.owork.draw.ui.theme.glassSurface
import kotlin.math.roundToInt

/**
 * tvOS/iPadOS-inspired two-column Settings screen.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    onBack: () -> Unit,
    onUpdateSettings: ((AppSettings) -> AppSettings) -> Unit,
) {
    val p = LocalGlass.current

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(p.background)
            .safeDrawingPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 28.dp, vertical = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(32.dp),
        ) {
            // Left hero column with sculpted opaque Drawspace icon (no transparent-shadow bleed)
            Column(
                modifier = Modifier
                    .weight(0.34f)
                    .fillMaxHeight(),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    GlassIconButton(
                        icon = Icons.AutoMirrored.Outlined.ArrowBack,
                        contentDescription = "Back",
                        size = 40.dp,
                        onClick = onBack,
                    )
                    Text(
                        text = "Settings",
                        color = p.text,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = (-0.7).sp,
                    )
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        val iconShape = RoundedCornerShape(30.dp)
                        Box(
                            modifier = Modifier
                                .size(112.dp)
                                .shadow(if (p.isDark) 24.dp else 14.dp, iconShape, clip = false)
                                .clip(iconShape),
                        ) {
                            Image(
                                painter = painterResource(R.drawable.ic_launcher_background),
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                            )
                            Image(
                                painter = painterResource(R.drawable.ic_launcher_foreground),
                                contentDescription = "Drawspace",
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                        Text(
                            text = "Drawspace",
                            color = p.text,
                            fontSize = 19.sp,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = (-0.3).sp,
                            textAlign = TextAlign.Center,
                        )
                        Text(
                            text = "GLES 3.0 ink rendering, stylus defaults, and canvas presets.",
                            color = p.secondary,
                            fontSize = 13.sp,
                            lineHeight = 19.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.widthIn(max = 250.dp),
                        )
                    }
                }
            }

            // Right settings groups column
            Column(
                modifier = Modifier
                    .weight(0.66f)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                GlassSettingsCard(title = "Appearance") {
                    Text("App Theme", color = p.text, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Styles app chrome and serves as the default background theme for sketches set to Follow App.",
                        color = p.secondary,
                        fontSize = 12.5.sp,
                        lineHeight = 18.sp,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        AppTheme.entries.forEach { t ->
                            GlassChip(
                                label = if (t == AppTheme.BLACK) "Black (AMOLED)" else t.label,
                                selected = settings.appTheme == t,
                                onClick = { onUpdateSettings { it.copy(appTheme = t) } },
                            )
                        }
                    }
                }

                GlassSettingsCard(title = "Rendering & Line Quality") {
                    Text("Line Anti-Aliasing", color = p.text, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold)
                    Text(settings.antiAliasing.description, color = p.secondary, fontSize = 12.5.sp)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        AntiAliasingMode.entries.forEach { mode ->
                            GlassChip(
                                label = mode.label,
                                selected = settings.antiAliasing == mode,
                                onClick = { onUpdateSettings { it.copy(antiAliasing = mode) } },
                            )
                        }
                    }

                    GlassDivider(Modifier.padding(vertical = 4.dp))

                    Text("Stroke Smoothing", color = p.text, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Damps stylus micro-jitter on thin lines while staying responsive.",
                        color = p.secondary,
                        fontSize = 12.5.sp,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        StrokeSmoothingLevel.entries.forEach { lvl ->
                            GlassChip(
                                label = lvl.label,
                                selected = settings.strokeSmoothing == lvl,
                                onClick = { onUpdateSettings { it.copy(strokeSmoothing = lvl) } },
                            )
                        }
                    }

                    GlassDivider(Modifier.padding(vertical = 4.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                            Text("Round Turns & Line Ends", color = p.text, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold)
                            Text(
                                "Trims stylus lift hooks and rounds sharp corners on pen-up.",
                                color = p.secondary,
                                fontSize = 12.5.sp,
                            )
                        }
                        GlassToggle(
                            checked = settings.roundTurnsAndEnds,
                            onCheckedChange = { enabled -> onUpdateSettings { it.copy(roundTurnsAndEnds = enabled) } },
                        )
                    }
                }

                GlassSettingsCard(title = "Stylus & Navigation") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                            Text("Stylus-Only Mode by Default", color = p.text, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold)
                            Text(
                                "Disables single-finger drawing by default; 2-finger pan/zoom/rotate stays active.",
                                color = p.secondary,
                                fontSize = 12.5.sp,
                            )
                        }
                        GlassToggle(
                            checked = settings.stylusOnlyDefault,
                            onCheckedChange = { enabled -> onUpdateSettings { it.copy(stylusOnlyDefault = enabled) } },
                        )
                    }

                    GlassDivider(Modifier.padding(vertical = 4.dp))

                    Text("Default Canvas Lock Mode", color = p.text, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CanvasLockMode.entries.forEach { mode ->
                            GlassChip(
                                label = mode.label,
                                selected = settings.defaultLockMode == mode,
                                onClick = { onUpdateSettings { it.copy(defaultLockMode = mode) } },
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                            Text("Lock Panning", color = p.text, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold)
                            Text(
                                "Prevents 2-finger panning across the canvas.",
                                color = p.secondary,
                                fontSize = 12.5.sp,
                            )
                        }
                        GlassToggle(
                            checked = settings.lockPan,
                            onCheckedChange = { locked -> onUpdateSettings { it.copy(lockPan = locked) } },
                        )
                    }
                }

                GlassSettingsCard(title = "New Canvas Defaults") {
                    Text("Default Background Pattern", color = p.text, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        BackgroundPattern.entries.forEach { pat ->
                            GlassChip(
                                label = pat.label,
                                selected = settings.defaultPattern == pat,
                                onClick = { onUpdateSettings { it.copy(defaultPattern = pat) } },
                            )
                        }
                    }

                    if (settings.defaultPattern != BackgroundPattern.BLANK) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                "Default Pattern Spacing · ${settings.defaultPatternSpacingPx.roundToInt()} px",
                                color = p.secondary,
                                fontSize = 12.sp,
                            )
                            GlassSlider(
                                value = settings.defaultPatternSpacingPx,
                                onValueChange = { v -> onUpdateSettings { it.copy(defaultPatternSpacingPx = v) } },
                                valueRange = 20f..160f,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }

                Spacer(Modifier.height(20.dp))
            }
        }
    }
}

@Composable
private fun GlassSettingsCard(
    title: String,
    content: @Composable () -> Unit,
) {
    val p = LocalGlass.current
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .glassSurface(shape, p, fill = p.elevated, bordered = true)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        GlassSectionLabel(title)
        content()
    }
}

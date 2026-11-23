package space.owork.draw.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import space.owork.draw.engine.AppTheme

/**
 * Monochrome, content-first liquid-glass palette inspired by tvOS/iPadOS:
 * the artwork & ink palette supply the color; chrome stays neutral and active controls invert crisply.
 */
@Immutable
data class GlassPalette(
    val isDark: Boolean,
    val background: Color,
    val elevated: Color,
    val elevated2: Color,
    val text: Color,
    val secondary: Color,
    val tertiary: Color,
    val separator: Color,
    val hairline: Color,
    /** Solid high-contrast active/selected fill (white in dark/black, near-black in light). */
    val active: Color,
    val onActive: Color,
    /** Translucent resting glass fills. */
    val glass: Color,
    val glassStrong: Color,
    val material: Color,
    val pillMaterial: Color,
    val recessed: Color,
    val specularTop: Color,
    val specularBottom: Color,
    val swatchBorder: Color,
    val destructive: Color,
)

val LocalGlass = staticCompositionLocalOf { DarkGlassPalette }

private val DarkGlassPalette = GlassPalette(
    isDark = true,
    background = Color(0xFF09090B),
    elevated = Color(0xFF17171A),
    elevated2 = Color(0xFF252529),
    text = Color(0xFFF5F5F7),
    secondary = Color(0xFFF5F5F7).copy(alpha = 0.62f),
    tertiary = Color(0xFFF5F5F7).copy(alpha = 0.36f),
    separator = Color.White.copy(alpha = 0.08f),
    hairline = Color.White.copy(alpha = 0.09f),
    active = Color(0xFFF7F7F9),
    onActive = Color(0xFF0E0E10),
    glass = Color.White.copy(alpha = 0.075f),
    glassStrong = Color.White.copy(alpha = 0.14f),
    material = Color(0xFF17171B).copy(alpha = 0.96f),
    pillMaterial = Color(0xFF151519).copy(alpha = 0.90f),
    recessed = Color.Black.copy(alpha = 0.32f),
    specularTop = Color.White.copy(alpha = 0.16f),
    specularBottom = Color.White.copy(alpha = 0.05f),
    swatchBorder = Color.White.copy(alpha = 0.28f),
    destructive = Color(0xFFFF453A),
)

private val BlackGlassPalette = GlassPalette(
    isDark = true,
    background = Color(0xFF000000),
    elevated = Color(0xFF0C0C0E),
    elevated2 = Color(0xFF1A1A1E),
    text = Color(0xFFF7F7F9),
    secondary = Color(0xFFF7F7F9).copy(alpha = 0.60f),
    tertiary = Color(0xFFF7F7F9).copy(alpha = 0.34f),
    separator = Color.White.copy(alpha = 0.09f),
    hairline = Color.White.copy(alpha = 0.11f),
    active = Color(0xFFFFFFFF),
    onActive = Color(0xFF000000),
    glass = Color.White.copy(alpha = 0.075f),
    glassStrong = Color.White.copy(alpha = 0.15f),
    material = Color(0xFF0A0A0C).copy(alpha = 0.97f),
    pillMaterial = Color(0xFF040406).copy(alpha = 0.93f),
    recessed = Color(0xFF000000),
    specularTop = Color.White.copy(alpha = 0.18f),
    specularBottom = Color.White.copy(alpha = 0.06f),
    swatchBorder = Color.White.copy(alpha = 0.34f),
    destructive = Color(0xFFFF453A),
)

private val LightGlassPalette = GlassPalette(
    isDark = false,
    background = Color(0xFFF4F4F6),
    elevated = Color(0xFFFFFFFF),
    elevated2 = Color(0xFFE9E9EE),
    text = Color(0xFF111114),
    secondary = Color(0xFF111114).copy(alpha = 0.58f),
    tertiary = Color(0xFF111114).copy(alpha = 0.36f),
    separator = Color.Black.copy(alpha = 0.075f),
    hairline = Color.Black.copy(alpha = 0.09f),
    active = Color(0xFF141417),
    onActive = Color(0xFFFFFFFF),
    glass = Color.Black.copy(alpha = 0.05f),
    glassStrong = Color.Black.copy(alpha = 0.10f),
    material = Color(0xFFFCFCFD).copy(alpha = 0.97f),
    pillMaterial = Color(0xFFFFFFFF).copy(alpha = 0.92f),
    recessed = Color.Black.copy(alpha = 0.055f),
    specularTop = Color.Black.copy(alpha = 0.08f),
    specularBottom = Color.Black.copy(alpha = 0.10f),
    swatchBorder = Color.Black.copy(alpha = 0.22f),
    destructive = Color(0xFFD93025),
)

/**
 * Floating glass container (HUD pills & top-level modals): translucent fill + gentle rim light.
 */
fun Modifier.glass(
    shape: Shape,
    palette: GlassPalette,
    fill: Color = palette.glass,
    edge: Boolean = true,
): Modifier = clip(shape)
    .background(fill)
    .then(
        if (edge) {
            Modifier.border(
                width = 1.dp,
                brush = Brush.verticalGradient(listOf(palette.specularTop, palette.specularBottom)),
                shape = shape,
            )
        } else {
            Modifier
        },
    )

/**
 * Clean nested surface with uniform hairline border (used inside modals & cards so edges never
 * look uneven or compromise readability).
 */
fun Modifier.glassSurface(
    shape: Shape,
    palette: GlassPalette,
    fill: Color = palette.glass,
    bordered: Boolean = false,
): Modifier = clip(shape)
    .background(fill)
    .then(
        if (bordered) Modifier.border(1.dp, palette.hairline, shape) else Modifier,
    )

private val StudioTypography = Typography(
    displayLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 36.sp, lineHeight = 42.sp, letterSpacing = (-1.0).sp),
    headlineLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 34.sp, letterSpacing = (-0.7).sp),
    headlineMedium = TextStyle(fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 28.sp, letterSpacing = (-0.45).sp),
    headlineSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp, letterSpacing = (-0.35).sp),
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 18.sp, lineHeight = 23.sp, letterSpacing = (-0.25).sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 20.sp, letterSpacing = (-0.15).sp),
    titleSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 18.sp, letterSpacing = (-0.1).sp),
    bodyLarge = TextStyle(fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontWeight = FontWeight.Normal, fontSize = 13.5.sp, lineHeight = 19.sp),
    bodySmall = TextStyle(fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp, letterSpacing = (-0.1).sp),
    labelMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 12.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 11.sp),
)

@Composable
fun DrawspaceTheme(
    appTheme: AppTheme = AppTheme.DARK,
    content: @Composable () -> Unit,
) {
    val glass = when (appTheme) {
        AppTheme.LIGHT -> LightGlassPalette
        AppTheme.DARK -> DarkGlassPalette
        AppTheme.BLACK -> BlackGlassPalette
    }
    val colorScheme = if (glass.isDark) {
        darkColorScheme(
            primary = glass.active,
            onPrimary = glass.onActive,
            primaryContainer = glass.glassStrong,
            onPrimaryContainer = glass.text,
            secondary = glass.secondary,
            onSecondary = glass.onActive,
            background = glass.background,
            onBackground = glass.text,
            surface = glass.elevated,
            onSurface = glass.text,
            surfaceVariant = glass.elevated2,
            onSurfaceVariant = glass.secondary,
            surfaceTint = Color.Transparent,
            outline = glass.separator,
            error = glass.destructive,
        )
    } else {
        lightColorScheme(
            primary = glass.active,
            onPrimary = glass.onActive,
            primaryContainer = glass.glassStrong,
            onPrimaryContainer = glass.text,
            secondary = glass.secondary,
            onSecondary = glass.onActive,
            background = glass.background,
            onBackground = glass.text,
            surface = glass.elevated,
            onSurface = glass.text,
            surfaceVariant = glass.elevated2,
            onSurfaceVariant = glass.secondary,
            surfaceTint = Color.Transparent,
            outline = glass.separator,
            error = glass.destructive,
        )
    }

    CompositionLocalProvider(LocalGlass provides glass) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = StudioTypography,
            content = content,
        )
    }
}

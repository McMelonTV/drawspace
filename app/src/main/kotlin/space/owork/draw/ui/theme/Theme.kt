package space.owork.draw.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import space.owork.draw.engine.AppTheme

@Immutable
data class DrawspaceHudColors(
    val pillBackground: Color,
    val pillBorder: Color,
    val swatchBorder: Color,
    val modalBackground: Color,
    val modalTrack: Color,
    val modalCard: Color,
)

val LocalHudColors = staticCompositionLocalOf {
    DrawspaceHudColors(
        pillBackground = Color(0xEC16191F),
        pillBorder = Color(0xFF2D323E),
        swatchBorder = Color(0x66FFFFFF),
        modalBackground = Color(0xFF171A21),
        modalTrack = Color(0xFF101217),
        modalCard = Color(0xFF20242E),
    )
}

private val StudioLightColorScheme = lightColorScheme(
    primary = Color(0xFF285EC4),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE6FA),
    onPrimaryContainer = Color(0xFF0D2554),
    secondary = Color(0xFF525A69),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE5E9F1),
    onSecondaryContainer = Color(0xFF1A1F29),
    background = Color(0xFFF3F4F7),
    onBackground = Color(0xFF161920),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF161920),
    surfaceVariant = Color(0xFFECEFF4),
    onSurfaceVariant = Color(0xFF535B6B),
    surfaceTint = Color.Transparent,
    outline = Color(0xFFD0D5DF),
    error = Color(0xFFC93434),
)

private val StudioDarkColorScheme = darkColorScheme(
    primary = Color(0xFF8AB4F8),
    onPrimary = Color(0xFF0A192F),
    primaryContainer = Color(0xFF1E3254),
    onPrimaryContainer = Color(0xFFDCE8FF),
    secondary = Color(0xFFB3BCCB),
    onSecondary = Color(0xFF1A1E26),
    secondaryContainer = Color(0xFF262B35),
    onSecondaryContainer = Color(0xFFE6EAF2),
    background = Color(0xFF111318),
    onBackground = Color(0xFFECEFF4),
    surface = Color(0xFF171A21),
    onSurface = Color(0xFFECEFF4),
    surfaceVariant = Color(0xFF21252F),
    onSurfaceVariant = Color(0xFF9EA7B8),
    surfaceTint = Color.Transparent,
    outline = Color(0xFF2E3440),
    error = Color(0xFFF28B82),
)

private val StudioBlackColorScheme = darkColorScheme(
    primary = Color(0xFF8AB4F8),
    onPrimary = Color(0xFF06101F),
    primaryContainer = Color(0xFF152540),
    onPrimaryContainer = Color(0xFFDCE8FF),
    secondary = Color(0xFFB0B8C7),
    onSecondary = Color(0xFF0C0E12),
    secondaryContainer = Color(0xFF181B22),
    onSecondaryContainer = Color(0xFFE6EAF2),
    background = Color(0xFF000000),
    onBackground = Color(0xFFF2F4F8),
    surface = Color(0xFF0A0C10),
    onSurface = Color(0xFFF2F4F8),
    surfaceVariant = Color(0xFF14171E),
    onSurfaceVariant = Color(0xFF9AA3B4),
    surfaceTint = Color.Transparent,
    outline = Color(0xFF242934),
    error = Color(0xFFF28B82),
)

@Composable
fun DrawspaceTheme(
    appTheme: AppTheme = AppTheme.DARK,
    content: @Composable () -> Unit,
) {
    val colorScheme = when (appTheme) {
        AppTheme.LIGHT -> StudioLightColorScheme
        AppTheme.DARK -> StudioDarkColorScheme
        AppTheme.BLACK -> StudioBlackColorScheme
    }
    val hudColors = when (appTheme) {
        AppTheme.LIGHT -> DrawspaceHudColors(
            pillBackground = Color(0xF0FFFFFF),
            pillBorder = Color(0xFFD2D7E0),
            swatchBorder = Color(0x55000000),
            modalBackground = Color(0xFFFFFFFF),
            modalTrack = Color(0xFFECEFF4),
            modalCard = Color(0xFFF5F7FA),
        )

        AppTheme.DARK -> DrawspaceHudColors(
            pillBackground = Color(0xEC16191F),
            pillBorder = Color(0xFF2D323E),
            swatchBorder = Color(0x66FFFFFF),
            modalBackground = Color(0xFF171A21),
            modalTrack = Color(0xFF101217),
            modalCard = Color(0xFF20242E),
        )

        AppTheme.BLACK -> DrawspaceHudColors(
            pillBackground = Color(0xF5000000),
            pillBorder = Color(0xFF262B36),
            swatchBorder = Color(0x77FFFFFF),
            modalBackground = Color(0xFF090B0E),
            modalTrack = Color(0xFF000000),
            modalCard = Color(0xFF14171E),
        )
    }

    CompositionLocalProvider(LocalHudColors provides hudColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            content = content,
        )
    }
}

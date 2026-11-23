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
)

val LocalHudColors = staticCompositionLocalOf {
    DrawspaceHudColors(
        pillBackground = Color(0xE6181B21),
        pillBorder = Color(0xFF323845),
        swatchBorder = Color(0x66FFFFFF),
    )
}

private val StudioLightColorScheme = lightColorScheme(
    primary = Color(0xFF285EC4),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE6FA),
    onPrimaryContainer = Color(0xFF0D2554),
    secondary = Color(0xFF525A69),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE7EBF2),
    onSecondaryContainer = Color(0xFF1D222B),
    background = Color(0xFFF3F4F7),
    onBackground = Color(0xFF161920),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF161920),
    surfaceVariant = Color(0xFFE8ECF2),
    onSurfaceVariant = Color(0xFF535B6B),
    outline = Color(0xFFD0D5DF),
    error = Color(0xFFC93434),
)

private val StudioDarkColorScheme = darkColorScheme(
    primary = Color(0xFF8AB4F8),
    onPrimary = Color(0xFF0A192F),
    primaryContainer = Color(0xFF1F3558),
    onPrimaryContainer = Color(0xFFD6E4FF),
    secondary = Color(0xFFB3BCCB),
    onSecondary = Color(0xFF1A1E26),
    secondaryContainer = Color(0xFF2B303A),
    onSecondaryContainer = Color(0xFFE1E6F0),
    background = Color(0xFF121418),
    onBackground = Color(0xFFECEFF4),
    surface = Color(0xFF181B21),
    onSurface = Color(0xFFECEFF4),
    surfaceVariant = Color(0xFF232730),
    onSurfaceVariant = Color(0xFFA7B0C0),
    outline = Color(0xFF353B47),
    error = Color(0xFFF28B82),
)

private val StudioBlackColorScheme = darkColorScheme(
    primary = Color(0xFF8AB4F8),
    onPrimary = Color(0xFF06101F),
    primaryContainer = Color(0xFF162742),
    onPrimaryContainer = Color(0xFFD6E4FF),
    secondary = Color(0xFFB0B8C7),
    onSecondary = Color(0xFF0C0E12),
    secondaryContainer = Color(0xFF16181E),
    onSecondaryContainer = Color(0xFFE4E8F0),
    background = Color(0xFF000000),
    onBackground = Color(0xFFF2F4F8),
    surface = Color(0xFF000000),
    onSurface = Color(0xFFF2F4F8),
    surfaceVariant = Color(0xFF111318),
    onSurfaceVariant = Color(0xFF9EA6B6),
    outline = Color(0xFF262B35),
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
            pillBackground = Color(0xEBFFFFFF),
            pillBorder = Color(0xFFD2D7E0),
            swatchBorder = Color(0x55000000),
        )

        AppTheme.DARK -> DrawspaceHudColors(
            pillBackground = Color(0xE6181B21),
            pillBorder = Color(0xFF323845),
            swatchBorder = Color(0x66FFFFFF),
        )

        AppTheme.BLACK -> DrawspaceHudColors(
            pillBackground = Color(0xF2000000),
            pillBorder = Color(0xFF2B303B),
            swatchBorder = Color(0x77FFFFFF),
        )
    }

    CompositionLocalProvider(LocalHudColors provides hudColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            content = content,
        )
    }
}

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
    val modalBorder: Color,
    val modalDivider: Color,
    val modalTrack: Color,
    val modalActiveTab: Color,
    val modalCard: Color,
    val modalCardBorder: Color,
    val modalInputBackground: Color,
)

val LocalHudColors = staticCompositionLocalOf {
    DrawspaceHudColors(
        pillBackground = Color(0xEC141519),
        pillBorder = Color(0x1AFFFFFF),
        swatchBorder = Color(0x55FFFFFF),
        modalBackground = Color(0xFF15161A),
        modalBorder = Color(0x1CFFFFFF),
        modalDivider = Color(0x12FFFFFF),
        modalTrack = Color(0xFF0D0E11),
        modalActiveTab = Color(0xFF25272F),
        modalCard = Color(0x0CFFFFFF),
        modalCardBorder = Color(0x12FFFFFF),
        modalInputBackground = Color(0xFF0E0F13),
    )
}

private val StudioLightColorScheme = lightColorScheme(
    primary = Color(0xFF285EC4),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE3ECFC),
    onPrimaryContainer = Color(0xFF0E2554),
    secondary = Color(0xFF555963),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFEAECEF),
    onSecondaryContainer = Color(0xFF181A1F),
    background = Color(0xFFF4F5F7),
    onBackground = Color(0xFF141519),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF141519),
    surfaceVariant = Color(0xFFF0F1F4),
    onSurfaceVariant = Color(0xFF5F6470),
    surfaceTint = Color.Transparent,
    outline = Color(0x1F000000),
    error = Color(0xFFC93434),
)

private val StudioDarkColorScheme = darkColorScheme(
    primary = Color(0xFF82AEFA),
    onPrimary = Color(0xFF09162B),
    primaryContainer = Color(0xFF1D2F4F),
    onPrimaryContainer = Color(0xFFDCE8FF),
    secondary = Color(0xFFA8ADB8),
    onSecondary = Color(0xFF16171B),
    secondaryContainer = Color(0xFF23252C),
    onSecondaryContainer = Color(0xFFECEEF2),
    background = Color(0xFF0F1013),
    onBackground = Color(0xFFF0F1F4),
    surface = Color(0xFF15161A),
    onSurface = Color(0xFFF0F1F4),
    surfaceVariant = Color(0xFF1E2026),
    onSurfaceVariant = Color(0xFF8E93A0),
    surfaceTint = Color.Transparent,
    outline = Color(0x1AFFFFFF),
    error = Color(0xFFF28B82),
)

private val StudioBlackColorScheme = darkColorScheme(
    primary = Color(0xFF82AEFA),
    onPrimary = Color(0xFF06101F),
    primaryContainer = Color(0xFF14223B),
    onPrimaryContainer = Color(0xFFDCE8FF),
    secondary = Color(0xFFA8ADB8),
    onSecondary = Color(0xFF0A0B0D),
    secondaryContainer = Color(0xFF18191E),
    onSecondaryContainer = Color(0xFFECEEF2),
    background = Color(0xFF000000),
    onBackground = Color(0xFFF4F5F7),
    surface = Color(0xFF0A0B0D),
    onSurface = Color(0xFFF4F5F7),
    surfaceVariant = Color(0xFF141519),
    onSurfaceVariant = Color(0xFF8A8F9C),
    surfaceTint = Color.Transparent,
    outline = Color(0x1EFFFFFF),
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
            pillBackground = Color(0xF2FFFFFF),
            pillBorder = Color(0x1A000000),
            swatchBorder = Color(0x40000000),
            modalBackground = Color(0xFFFFFFFF),
            modalBorder = Color(0x1A000000),
            modalDivider = Color(0x12000000),
            modalTrack = Color(0xFFF0F1F4),
            modalActiveTab = Color(0xFFFFFFFF),
            modalCard = Color(0xFFF6F7F9),
            modalCardBorder = Color(0x12000000),
            modalInputBackground = Color(0xFFF5F6F8),
        )

        AppTheme.DARK -> DrawspaceHudColors(
            pillBackground = Color(0xEC141519),
            pillBorder = Color(0x1AFFFFFF),
            swatchBorder = Color(0x55FFFFFF),
            modalBackground = Color(0xFF15161A),
            modalBorder = Color(0x1CFFFFFF),
            modalDivider = Color(0x12FFFFFF),
            modalTrack = Color(0xFF0D0E11),
            modalActiveTab = Color(0xFF25272F),
            modalCard = Color(0x0BFFFFFF),
            modalCardBorder = Color(0x12FFFFFF),
            modalInputBackground = Color(0xFF0E0F13),
        )

        AppTheme.BLACK -> DrawspaceHudColors(
            pillBackground = Color(0xF5000000),
            pillBorder = Color(0x1EFFFFFF),
            swatchBorder = Color(0x66FFFFFF),
            modalBackground = Color(0xFF0A0B0D),
            modalBorder = Color(0x1EFFFFFF),
            modalDivider = Color(0x14FFFFFF),
            modalTrack = Color(0xFF000000),
            modalActiveTab = Color(0xFF1C1D23),
            modalCard = Color(0x0CFFFFFF),
            modalCardBorder = Color(0x14FFFFFF),
            modalInputBackground = Color(0xFF000000),
        )
    }

    CompositionLocalProvider(LocalHudColors provides hudColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            content = content,
        )
    }
}

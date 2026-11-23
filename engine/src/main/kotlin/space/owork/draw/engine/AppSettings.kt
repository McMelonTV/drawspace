package space.owork.draw.engine

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class AntiAliasingMode(
    val label: String,
    val description: String,
    val featherPx: Float,
    val supersampleScale: Float,
) {
    @SerialName("off")
    OFF("Off", "Hard pixel edges, fastest", 0f, 1f),

    @SerialName("standard")
    STANDARD("Standard", "Crisp analytic SDF anti-aliasing", 1.05f, 1f),

    @SerialName("high")
    HIGH("High", "Softer analytic AA + finer curves", 1.55f, 1f),

    @SerialName("ultra")
    ULTRA("Ultra (SSAA)", "1.5× supersampled FBO + SDF AA", 1.35f, 1.5f),
}

@Serializable
enum class StrokeSmoothingLevel(
    val label: String,
    val inputBlend: Float,
) {
    @SerialName("off")
    OFF("Off", 0f),

    @SerialName("low")
    LOW("Low", 0.22f),

    @SerialName("medium")
    MEDIUM("Medium", 0.42f),

    @SerialName("high")
    HIGH("High", 0.62f),
}

/**
 * Global application preferences (distinct from per-document [CanvasBackground] settings).
 */
@Serializable
data class AppSettings(
    val appTheme: AppTheme = AppTheme.DARK,
    val antiAliasing: AntiAliasingMode = AntiAliasingMode.HIGH,
    val strokeSmoothing: StrokeSmoothingLevel = StrokeSmoothingLevel.LOW,
    val roundTurnsAndEnds: Boolean = true,
    val stylusOnlyDefault: Boolean = false,
    val defaultLockMode: CanvasLockMode = CanvasLockMode.UNLOCKED,
    val lockPan: Boolean = false,
    val defaultPattern: BackgroundPattern = BackgroundPattern.DOT_GRID,
    val defaultPatternSpacingPx: Float = 48f,
)

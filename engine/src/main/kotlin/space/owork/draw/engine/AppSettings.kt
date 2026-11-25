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
    /** Whether the eraser removes whole strokes/fills or only the touched portion. */
    val eraserMode: EraserMode = EraserMode.STROKE,
    /** Press and hold the stylus still, then press firmly, to switch to the eraser until the pen lifts. */
    val pressureEraser: Boolean = true,
    /** Raw stylus pressure (0..1) the firm press must exceed once the hold elapses. */
    val pressureEraserThreshold: Float = DEFAULT_PRESSURE_ERASER_THRESHOLD,
    val stylusOnlyDefault: Boolean = false,
    val defaultLockMode: CanvasLockMode = CanvasLockMode.UNLOCKED,
    val lockPan: Boolean = false,
    val defaultPattern: BackgroundPattern = BackgroundPattern.DOT_GRID,
    val defaultPatternSpacingPx: Float = 48f,
) {
    companion object {
        const val DEFAULT_PRESSURE_ERASER_THRESHOLD: Float = 0.5f
    }
}

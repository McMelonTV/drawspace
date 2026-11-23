package space.owork.draw.engine

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class AppTheme(val label: String) {
    @SerialName("light")
    LIGHT("Light"),

    @SerialName("dark")
    DARK("Dark"),

    @SerialName("black")
    BLACK("Black"),
}

@Serializable
enum class BackgroundPattern(val label: String) {
    @SerialName("blank")
    BLANK("Blank"),

    @SerialName("dot_grid")
    DOT_GRID("Dot Grid"),

    @SerialName("square_grid")
    SQUARE_GRID("Grid"),

    @SerialName("lined")
    LINED("Lined"),

    @SerialName("isometric")
    ISOMETRIC("Isometric"),

    @SerialName("cross_grid")
    CROSS_GRID("Crosses"),
}

/**
 * Per-document canvas background & pattern configuration.
 * - [canvasTheme]: `null` inherits the global [AppTheme]; otherwise overrides ONLY this canvas's
 *   background & surround theme (`LIGHT`, `DARK`, or `BLACK` AMOLED).
 * - [customPaperColorArgb]: optional explicit paper tint overriding the resolved theme paper.
 * - [patternColorArgb]: `null` auto-contrasts against the resolved paper color.
 */
@Serializable
data class CanvasBackground(
    val canvasTheme: AppTheme? = null,
    val pattern: BackgroundPattern = BackgroundPattern.DOT_GRID,
    val spacingPx: Float = 48f,
    val opacity: Float = 0.28f,
    val patternColorArgb: Int? = null,
    val customPaperColorArgb: Int? = null,
    val showOriginMarker: Boolean = true,
) {
    fun effectiveTheme(appTheme: AppTheme): AppTheme = canvasTheme ?: appTheme

    fun resolvePaperArgb(appTheme: AppTheme): Int =
        customPaperColorArgb ?: defaultPaperFor(effectiveTheme(appTheme))

    fun resolveSurroundArgb(appTheme: AppTheme): Int =
        defaultSurroundFor(effectiveTheme(appTheme))

    fun resolvePatternArgb(appTheme: AppTheme): Int {
        patternColorArgb?.let { return it }
        val paper = resolvePaperArgb(appTheme)
        return if (isColorDark(paper)) DEFAULT_PATTERN_ON_DARK else DEFAULT_PATTERN_ON_LIGHT
    }

    companion object {
        const val LIGHT_PAPER_ARGB: Int = 0xFFFAF8F5.toInt()
        const val DARK_PAPER_ARGB: Int = 0xFF1A1D23.toInt()
        const val BLACK_PAPER_ARGB: Int = 0xFF000000.toInt()

        const val LIGHT_SURROUND_ARGB: Int = 0xFFE6E9EF.toInt()
        const val DARK_SURROUND_ARGB: Int = 0xFF101217.toInt()
        const val BLACK_SURROUND_ARGB: Int = 0xFF000000.toInt()

        const val DEFAULT_PATTERN_ON_LIGHT: Int = 0xFF5C6475.toInt()
        const val DEFAULT_PATTERN_ON_DARK: Int = 0xFF9AA4B8.toInt()

        fun defaultPaperFor(theme: AppTheme): Int = when (theme) {
            AppTheme.LIGHT -> LIGHT_PAPER_ARGB
            AppTheme.DARK -> DARK_PAPER_ARGB
            AppTheme.BLACK -> BLACK_PAPER_ARGB
        }

        fun defaultSurroundFor(theme: AppTheme): Int = when (theme) {
            AppTheme.LIGHT -> LIGHT_SURROUND_ARGB
            AppTheme.DARK -> DARK_SURROUND_ARGB
            AppTheme.BLACK -> BLACK_SURROUND_ARGB
        }

        fun isColorDark(argb: Int): Boolean {
            val r = (argb ushr 16) and 0xFF
            val g = (argb ushr 8) and 0xFF
            val b = argb and 0xFF
            val luma = 0.299f * r + 0.587f * g + 0.114f * b
            return luma < 128f
        }

        val paperPresets: List<ColorSwatch> = listOf(
            ColorSwatch("Warm Paper", 0xFFFAF8F5.toInt()),
            ColorSwatch("Pure White", 0xFFFFFFFF.toInt()),
            ColorSwatch("Cream", 0xFFF3EBD8.toInt()),
            ColorSwatch("Blueprint", 0xFF15315B.toInt()),
            ColorSwatch("Dark Slate", 0xFF1A1D23.toInt()),
            ColorSwatch("True Black", 0xFF000000.toInt()),
        )

        val patternColorPresets: List<ColorSwatch> = listOf(
            ColorSwatch("Slate", 0xFF6B7487.toInt()),
            ColorSwatch("Blue", 0xFF4A90E2.toInt()),
            ColorSwatch("Teal", 0xFF2AA198.toInt()),
            ColorSwatch("Amber", 0xFFD99B26.toInt()),
            ColorSwatch("Rose", 0xFFD94F68.toInt()),
            ColorSwatch("White", 0xFFE8ECF2.toInt()),
            ColorSwatch("Ink", 0xFF1D2028.toInt()),
        )
    }
}

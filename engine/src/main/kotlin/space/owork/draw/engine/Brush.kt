package space.owork.draw.engine

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class ToolType {
    @SerialName("pen")
    PEN,

    @SerialName("pencil")
    PENCIL,

    @SerialName("marker")
    MARKER,

    @SerialName("line")
    LINE,

    @SerialName("eraser")
    ERASER,
}

@Serializable
data class BrushSpec(
    val tool: ToolType = ToolType.PEN,
    /** ARGB 32-bit packed color (e.g. 0xFF1A1D24.toInt()). */
    val colorArgb: Int = DEFAULT_INK_COLOR,
    /** Base stroke diameter in world pixels at nominal pressure. */
    val sizeWorldPx: Float = 6f,
    /** Base opacity [0f, 1f]. */
    val opacity: Float = 1f,
    /** How strongly stylus pressure modulates stroke width [0f, 1f]. */
    val pressureSizeSensitivity: Float = 0.72f,
    /** How strongly stylus pressure modulates stroke opacity [0f, 1f]. */
    val pressureOpacitySensitivity: Float = 0.15f,
    /** How strongly stylus tilt widens/shades strokes (used by Pencil & Marker). */
    val tiltSensitivity: Float = 0.45f,
) {
    companion object {
        const val DEFAULT_INK_COLOR: Int = 0xFF181A1F.toInt()

        fun defaultFor(tool: ToolType, colorArgb: Int = DEFAULT_INK_COLOR): BrushSpec = when (tool) {
            ToolType.PEN -> BrushSpec(
                tool = ToolType.PEN,
                colorArgb = colorArgb,
                sizeWorldPx = 5.5f,
                opacity = 1.0f,
                pressureSizeSensitivity = 0.75f,
                pressureOpacitySensitivity = 0.05f,
                tiltSensitivity = 0.0f,
            )

            ToolType.PENCIL -> BrushSpec(
                tool = ToolType.PENCIL,
                colorArgb = colorArgb,
                sizeWorldPx = 4.5f,
                opacity = 0.85f,
                pressureSizeSensitivity = 0.45f,
                pressureOpacitySensitivity = 0.65f,
                tiltSensitivity = 0.85f,
            )

            ToolType.MARKER -> BrushSpec(
                tool = ToolType.MARKER,
                colorArgb = colorArgb,
                sizeWorldPx = 22f,
                opacity = 0.42f,
                pressureSizeSensitivity = 0.2f,
                pressureOpacitySensitivity = 0.15f,
                tiltSensitivity = 0.5f,
            )

            ToolType.LINE -> BrushSpec(
                tool = ToolType.LINE,
                colorArgb = colorArgb,
                sizeWorldPx = 5.5f,
                opacity = 1.0f,
                pressureSizeSensitivity = 0.0f,
                pressureOpacitySensitivity = 0.0f,
                tiltSensitivity = 0.0f,
            )

            ToolType.ERASER -> BrushSpec(
                tool = ToolType.ERASER,
                colorArgb = 0x00000000,
                sizeWorldPx = 26f,
                opacity = 1.0f,
                pressureSizeSensitivity = 0.25f,
                pressureOpacitySensitivity = 0.0f,
                tiltSensitivity = 0.0f,
            )
        }
    }
}

@Serializable
data class ColorSwatch(
    val name: String,
    val argb: Int,
)

@Serializable
data class ColorPalette(
    val name: String = "Studio Sketch",
    val swatches: List<ColorSwatch> = defaultSwatches,
) {
    companion object {
        val defaultSwatches: List<ColorSwatch> = listOf(
            ColorSwatch("Obsidian", 0xFF181A1F.toInt()),
            ColorSwatch("Graphite", 0xFF4A505C.toInt()),
            ColorSwatch("Silver", 0xFF8C94A3.toInt()),
            ColorSwatch("Chalk", 0xFFF7F7F5.toInt()),
            ColorSwatch("Crimson", 0xFFD93848.toInt()),
            ColorSwatch("Vermilion", 0xFFEB6B38.toInt()),
            ColorSwatch("Ochre", 0xFFE3A72F.toInt()),
            ColorSwatch("Moss", 0xFF3E8E5A.toInt()),
            ColorSwatch("Teal", 0xFF238A96.toInt()),
            ColorSwatch("Cobalt", 0xFF2F66D0.toInt()),
            ColorSwatch("Indigo", 0xFF6B4FD1.toInt()),
            ColorSwatch("Sepia", 0xFF8C5A3C.toInt()),
        )
    }
}

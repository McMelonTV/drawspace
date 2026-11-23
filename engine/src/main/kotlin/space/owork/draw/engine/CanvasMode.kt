package space.owork.draw.engine

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Both Infinite and Fixed-Size canvases share the exact same world-coordinate engine.
 * Fixed-Size simply binds an artboard rectangle + DPI for clipping, paper backdrop, and export.
 */
@Serializable
sealed interface CanvasMode {
    val bounds: WorldRect?

    @Serializable
    @SerialName("infinite")
    data object Infinite : CanvasMode {
        override val bounds: WorldRect? = null
    }

    @Serializable
    @SerialName("fixed")
    data class Fixed(
        val widthPx: Int,
        val heightPx: Int,
        val dpi: Int = 300,
    ) : CanvasMode {
        init {
            require(widthPx > 0 && heightPx > 0) { "Canvas dimensions must be positive" }
            require(dpi > 0) { "DPI must be positive" }
        }

        override val bounds: WorldRect
            get() = WorldRect(0f, 0f, widthPx.toFloat(), heightPx.toFloat())
    }
}

data class CanvasPreset(
    val id: String,
    val label: String,
    val subtitle: String,
    val widthPx: Int,
    val heightPx: Int,
    val dpi: Int,
    val category: PresetCategory,
) {
    fun toCanvasMode(): CanvasMode.Fixed = CanvasMode.Fixed(widthPx, heightPx, dpi)
}

enum class PresetCategory {
    SCREEN,
    SQUARE,
    PRINT,
}

object CanvasPresets {
    val defaultPresets: List<CanvasPreset> = listOf(
        CanvasPreset(
            id = "tablet_11_2k5",
            label = "Tablet Screen",
            subtitle = "2560 × 1600 px · 16:10",
            widthPx = 2560,
            heightPx = 1600,
            dpi = 274,
            category = PresetCategory.SCREEN,
        ),
        CanvasPreset(
            id = "full_hd",
            label = "Full HD (16:9)",
            subtitle = "1920 × 1080 px",
            widthPx = 1920,
            heightPx = 1080,
            dpi = 144,
            category = PresetCategory.SCREEN,
        ),
        CanvasPreset(
            id = "uhd_4k",
            label = "4K Canvas",
            subtitle = "3840 × 2160 px",
            widthPx = 3840,
            heightPx = 2160,
            dpi = 300,
            category = PresetCategory.SCREEN,
        ),
        CanvasPreset(
            id = "square_2k",
            label = "Square 2K",
            subtitle = "2048 × 2048 px",
            widthPx = 2048,
            heightPx = 2048,
            dpi = 300,
            category = PresetCategory.SQUARE,
        ),
        CanvasPreset(
            id = "a4_300",
            label = "A4 Print",
            subtitle = "2480 × 3508 px · 300 DPI",
            widthPx = 2480,
            heightPx = 3508,
            dpi = 300,
            category = PresetCategory.PRINT,
        ),
        CanvasPreset(
            id = "letter_300",
            label = "US Letter",
            subtitle = "2550 × 3300 px · 300 DPI",
            widthPx = 2550,
            heightPx = 3300,
            dpi = 300,
            category = PresetCategory.PRINT,
        ),
        CanvasPreset(
            id = "sketchbook_portrait",
            label = "Portrait Sketch",
            subtitle = "1600 × 2560 px",
            widthPx = 1600,
            heightPx = 2560,
            dpi = 274,
            category = PresetCategory.SCREEN,
        ),
    )
}

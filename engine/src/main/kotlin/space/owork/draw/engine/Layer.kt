package space.owork.draw.engine

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class BlendMode {
    @SerialName("normal")
    NORMAL,

    @SerialName("multiply")
    MULTIPLY,

    @SerialName("screen")
    SCREEN,

    @SerialName("overlay")
    OVERLAY,

    @SerialName("darken")
    DARKEN,

    @SerialName("lighten")
    LIGHTEN,
}

@Serializable
data class TileCoord(
    val tx: Int,
    val ty: Int,
) {
    val fileKey: String get() = "${tx}_${ty}"
}

/**
 * Reference to a persisted 512x512 lossless raster tile stored in Git under
 * `layers/<layerId>/tiles/<tx>_<ty>.png`.
 */
@Serializable
data class RasterTileRef(
    val coord: TileCoord,
    val sha256: String? = null,
    val byteSize: Int = 0,
)

@Serializable
data class LayerMeta(
    val id: String,
    val name: String,
    val kind: LayerKind,
    val visible: Boolean = true,
    val opacity: Float = 1f,
    val blendMode: BlendMode = BlendMode.NORMAL,
    val tileSize: Int = 512,
)

@Serializable
enum class LayerKind {
    @SerialName("stroke")
    STROKE,

    @SerialName("raster")
    RASTER,
}

/**
 * First-class hybrid scene graph: documents hold an ordered stack of both vector/stroke layers
 * and sparse tiled raster layers from commit 1.
 */
sealed interface Layer {
    val id: String
    val name: String
    val visible: Boolean
    val opacity: Float
    val blendMode: BlendMode

    fun toMeta(): LayerMeta

    data class StrokeLayer(
        override val id: String = Ulid.next(),
        override val name: String = "Sketch",
        override val visible: Boolean = true,
        override val opacity: Float = 1f,
        override val blendMode: BlendMode = BlendMode.NORMAL,
        val strokes: List<Stroke> = emptyList(),
        val tombstones: Set<String> = emptySet(),
    ) : Layer {
        val activeStrokes: List<Stroke> by lazy(LazyThreadSafetyMode.PUBLICATION) {
            if (tombstones.isEmpty()) strokes else strokes.filterNot { it.id in tombstones }
        }

        override fun toMeta(): LayerMeta = LayerMeta(
            id = id,
            name = name,
            kind = LayerKind.STROKE,
            visible = visible,
            opacity = opacity,
            blendMode = blendMode,
        )
    }

    data class RasterLayer(
        override val id: String = Ulid.next(),
        override val name: String = "Paint",
        override val visible: Boolean = true,
        override val opacity: Float = 1f,
        override val blendMode: BlendMode = BlendMode.NORMAL,
        val tileSize: Int = 512,
        val tiles: Map<TileCoord, RasterTileRef> = emptyMap(),
    ) : Layer {
        override fun toMeta(): LayerMeta = LayerMeta(
            id = id,
            name = name,
            kind = LayerKind.RASTER,
            visible = visible,
            opacity = opacity,
            blendMode = blendMode,
            tileSize = tileSize,
        )
    }
}

package space.owork.draw.engine

import kotlinx.serialization.Serializable

@Serializable
data class DocumentMeta(
    val id: String = Ulid.next(),
    val title: String,
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val canvasMode: CanvasMode = CanvasMode.Infinite,
    val backgroundArgb: Int = DEFAULT_PAPER_BG,
    val background: CanvasBackground = CanvasBackground(),
    val createdAtMs: Long = System.currentTimeMillis(),
    val updatedAtMs: Long = createdAtMs,
    val layerOrder: List<String> = emptyList(),
    val palette: ColorPalette = ColorPalette(),
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
        const val DEFAULT_PAPER_BG: Int = 0xFFFAF8F5.toInt()
        const val DEFAULT_DARK_INFINITE_BG: Int = 0xFF1A1C20.toInt()
    }
}

/**
 * In-memory document aggregate shared across `:engine`, `:storage`, `:renderer`, and `:app`.
 */
data class Document(
    val meta: DocumentMeta,
    val layers: List<Layer>,
    val undoStack: UndoStackSnapshot = UndoStackSnapshot(),
) {
    val id: String get() = meta.id
    val title: String get() = meta.title
    val canvasMode: CanvasMode get() = meta.canvasMode

    fun primaryStrokeLayer(): Layer.StrokeLayer =
        layers.filterIsInstance<Layer.StrokeLayer>().first()

    fun visibleStrokes(): List<Stroke> =
        layers.asSequence()
            .filter { it.visible }
            .filterIsInstance<Layer.StrokeLayer>()
            .flatMap { it.activeStrokes.asSequence() }
            .toList()

    fun contentBounds(): WorldRect? {
        meta.canvasMode.bounds?.let { return it }
        var acc: WorldRect? = null
        for (layer in layers) {
            if (!layer.visible) continue
            if (layer is Layer.StrokeLayer) {
                for (stroke in layer.activeStrokes) {
                    acc = acc?.union(stroke.bounds) ?: stroke.bounds
                }
            }
        }
        return acc
    }

    fun updateStrokeLayer(
        layerId: String,
        updatedAtMs: Long = System.currentTimeMillis(),
        transform: (Layer.StrokeLayer) -> Layer.StrokeLayer,
    ): Document {
        val nextLayers = layers.map { layer ->
            if (layer is Layer.StrokeLayer && layer.id == layerId) transform(layer) else layer
        }
        return copy(
            meta = meta.copy(updatedAtMs = updatedAtMs),
            layers = nextLayers,
        )
    }

    companion object {
        fun createNew(
            title: String,
            canvasMode: CanvasMode,
            background: CanvasBackground = CanvasBackground(),
            backgroundArgb: Int = DocumentMeta.DEFAULT_PAPER_BG,
            nowMs: Long = System.currentTimeMillis(),
        ): Document {
            val sketchLayer = Layer.StrokeLayer(id = "L_${Ulid.next(nowMs)}", name = "Sketch")
            val meta = DocumentMeta(
                id = Ulid.next(nowMs),
                title = title,
                canvasMode = canvasMode,
                backgroundArgb = backgroundArgb,
                background = background,
                createdAtMs = nowMs,
                updatedAtMs = nowMs,
                layerOrder = listOf(sketchLayer.id),
            )
            return Document(meta = meta, layers = listOf(sketchLayer))
        }
    }
}

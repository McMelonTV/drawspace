package space.owork.draw.engine

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.math.roundToInt

/**
 * Deterministic, Git-diff-friendly codec for Drawspace documents.
 *
 * - Metadata (`document.json`, `meta.json`) is canonical pretty-printed JSON.
 * - Strokes are encoded as compact append-only JSONL chunks (up to [STROKES_PER_CHUNK] per file)
 *   with quantized coordinates so untouched chunks keep identical Git blob SHAs across commits.
 * - Erased stroke IDs are stored in sorted `tombstones.jsonl`.
 */
object DocumentCodec {
    const val STROKES_PER_CHUNK = 500

    val prettyJson: Json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    val compactJson: Json = Json {
        prettyPrint = false
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    fun encodeDocumentMeta(meta: DocumentMeta): ByteArray =
        (prettyJson.encodeToString(meta) + "\n").encodeToByteArray()

    fun decodeDocumentMeta(bytes: ByteArray): DocumentMeta =
        prettyJson.decodeFromString(bytes.decodeToString())

    fun encodeLayerMeta(meta: LayerMeta): ByteArray =
        (prettyJson.encodeToString(meta) + "\n").encodeToByteArray()

    fun decodeLayerMeta(bytes: ByteArray): LayerMeta =
        prettyJson.decodeFromString(bytes.decodeToString())

    fun encodePalette(palette: ColorPalette): ByteArray =
        (prettyJson.encodeToString(palette) + "\n").encodeToByteArray()

    fun decodePalette(bytes: ByteArray): ColorPalette =
        prettyJson.decodeFromString(bytes.decodeToString())

    fun encodeUndoStack(snapshot: UndoStackSnapshot): ByteArray =
        (compactJson.encodeToString(snapshot) + "\n").encodeToByteArray()

    fun decodeUndoStack(bytes: ByteArray?): UndoStackSnapshot {
        if (bytes == null || bytes.isEmpty()) return UndoStackSnapshot()
        return runCatching { compactJson.decodeFromString<UndoStackSnapshot>(bytes.decodeToString()) }
            .getOrDefault(UndoStackSnapshot())
    }

    /**
     * Splits a stroke list into deterministic chunk filenames (`c_0001.jsonl`, `c_0002.jsonl`, ...)
     * sorted by ULID so earlier chunks remain immutable as new strokes are appended.
     */
    fun encodeStrokeChunks(strokes: List<Stroke>): Map<String, ByteArray> {
        if (strokes.isEmpty()) return emptyMap()
        val sorted = strokes.sortedBy { it.id }
        val result = LinkedHashMap<String, ByteArray>()
        var chunkIndex = 1
        var cursor = 0
        while (cursor < sorted.size) {
            val end = (cursor + STROKES_PER_CHUNK).coerceAtMost(sorted.size)
            val sb = StringBuilder((end - cursor) * 180)
            for (i in cursor until end) {
                sb.append(compactJson.encodeToString(quantizeStroke(sorted[i])))
                sb.append('\n')
            }
            val fileName = "c_%04d.jsonl".format(chunkIndex)
            result[fileName] = sb.toString().encodeToByteArray()
            chunkIndex++
            cursor = end
        }
        return result
    }

    fun decodeStrokeChunks(chunks: Map<String, ByteArray>): List<Stroke> {
        val byId = LinkedHashMap<String, Stroke>()
        for (name in chunks.keys.sorted()) {
            val text = chunks[name]?.decodeToString() ?: continue
            for (line in text.lineSequence()) {
                val trimmed = line.trim()
                if (trimmed.isEmpty()) continue
                val stroke = compactJson.decodeFromString<Stroke>(trimmed)
                byId[stroke.id] = stroke
            }
        }
        return byId.values.sortedBy { it.id }
    }

    fun encodeTombstones(tombstones: Set<String>): ByteArray {
        if (tombstones.isEmpty()) return ByteArray(0)
        return (tombstones.sorted().joinToString("\n") + "\n").encodeToByteArray()
    }

    fun decodeTombstones(bytes: ByteArray?): Set<String> {
        if (bytes == null || bytes.isEmpty()) return emptySet()
        return bytes.decodeToString()
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
    }

    fun quantizeStroke(stroke: Stroke): Stroke = stroke.copy(
        brush = stroke.brush.copy(
            sizeWorldPx = round2(stroke.brush.sizeWorldPx),
            opacity = round3(stroke.brush.opacity),
            pressureSizeSensitivity = round3(stroke.brush.pressureSizeSensitivity),
            pressureOpacitySensitivity = round3(stroke.brush.pressureOpacitySensitivity),
            tiltSensitivity = round3(stroke.brush.tiltSensitivity),
        ),
        points = stroke.points.map { p ->
            p.copy(
                x = round2(p.x),
                y = round2(p.y),
                pressure = round3(p.pressure),
                tilt = round3(p.tilt),
                orientation = round3(p.orientation),
            )
        },
        bounds = WorldRect(
            left = round2(stroke.bounds.left),
            top = round2(stroke.bounds.top),
            right = round2(stroke.bounds.right),
            bottom = round2(stroke.bounds.bottom),
        ),
    )

    private fun round2(v: Float): Float = (v * 100f).roundToInt() / 100f
    private fun round3(v: Float): Float = (v * 1000f).roundToInt() / 1000f
}

/**
 * Domain-aware 3-way merge for stroke layers so multi-device Git sync never exposes
 * text conflict markers to the user.
 */
object LayerMerger {
    fun mergeStrokeLayer(
        base: Layer.StrokeLayer?,
        ours: Layer.StrokeLayer,
        theirs: Layer.StrokeLayer,
    ): Layer.StrokeLayer {
        val mergedStrokes = LinkedHashMap<String, Stroke>()
        for (s in theirs.strokes) mergedStrokes[s.id] = s
        for (s in ours.strokes) mergedStrokes[s.id] = s

        // Tombstones follow OR-Set / add-wins-deletion semantics relative to base:
        // keep tombstones added on either side, respect un-deletions (undo) made since base.
        val baseTombstones = base?.tombstones.orEmpty()
        val addedOurs = ours.tombstones - baseTombstones
        val addedTheirs = theirs.tombstones - baseTombstones
        val removedOurs = baseTombstones - ours.tombstones
        val removedTheirs = baseTombstones - theirs.tombstones
        val survivingBase = baseTombstones - removedOurs - removedTheirs
        val mergedTombstones = survivingBase + addedOurs + addedTheirs

        return ours.copy(
            strokes = mergedStrokes.values.sortedBy { it.id },
            tombstones = mergedTombstones,
        )
    }
}

package space.owork.draw.storage

import kotlinx.serialization.encodeToString
import space.owork.draw.engine.Document
import space.owork.draw.engine.DocumentCodec
import space.owork.draw.engine.DocumentDelta
import space.owork.draw.engine.Layer
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/**
 * Crash-safe Write-Ahead Log stored outside the bare Git repository (`<workspace>/wal/`).
 * Every flush writes to a temp file, calls `FileChannel.force(true)` (fsync), and atomically
 * renames it over the active WAL file so a sudden SIGKILL or power loss never corrupts data.
 */
internal class AtomicWal(
    private val walDir: File,
) {
    init {
        walDir.mkdirs()
    }

    fun walFile(documentId: String): File = File(walDir, "$documentId.wal.jsonl")

    fun hasPending(documentId: String): Boolean {
        val f = walFile(documentId)
        return f.exists() && f.length() > 0L
    }

    fun pendingDocumentIds(): List<String> =
        walDir.listFiles()
            ?.asSequence()
            ?.filter { it.isFile && it.name.endsWith(".wal.jsonl") && it.length() > 0L }
            ?.map { it.name.removeSuffix(".wal.jsonl") }
            ?.sorted()
            ?.toList()
            .orEmpty()

    fun appendDelta(documentId: String, delta: DocumentDelta) {
        if (delta.isEmpty) return
        appendDeltas(documentId, listOf(delta))
    }

    fun appendDeltas(documentId: String, deltas: List<DocumentDelta>) {
        val nonEmpty = deltas.filterNot { it.isEmpty }
        if (nonEmpty.isEmpty()) return
        walDir.mkdirs()
        val target = walFile(documentId)
        val tmp = File(walDir, "$documentId.wal.tmp")

        val existingBytes = if (target.exists() && target.length() > 0L) target.readBytes() else ByteArray(0)
        val newLines = buildString {
            for (d in nonEmpty) {
                append(DocumentCodec.compactJson.encodeToString(d))
                append('\n')
            }
        }.encodeToByteArray()

        FileChannel.open(
            tmp.toPath(),
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
            StandardOpenOption.TRUNCATE_EXISTING,
        ).use { ch ->
            if (existingBytes.isNotEmpty()) {
                writeFully(ch, existingBytes)
            }
            writeFully(ch, newLines)
            ch.force(true)
        }

        try {
            Files.move(
                tmp.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: UnsupportedOperationException) {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    fun readDeltas(documentId: String): List<DocumentDelta> {
        val file = walFile(documentId)
        if (!file.exists()) return emptyList()
        val out = ArrayList<DocumentDelta>()
        file.forEachLine { line ->
            val trimmed = line.trim()
            if (trimmed.isNotEmpty()) {
                runCatching { DocumentCodec.compactJson.decodeFromString<DocumentDelta>(trimmed) }
                    .onSuccess { out.add(it) }
            }
        }
        return out
    }

    fun clear(documentId: String) {
        val file = walFile(documentId)
        if (file.exists()) {
            file.delete()
        }
    }

    fun replayOnto(document: Document): Document {
        val deltas = readDeltas(document.id)
        if (deltas.isEmpty()) return document
        var current = document
        for (delta in deltas) {
            current = applyDelta(current, delta)
        }
        return current
    }

    private fun applyDelta(document: Document, delta: DocumentDelta): Document {
        val updated = document.updateStrokeLayer(delta.layerId, updatedAtMs = delta.timestampMs) { layer: Layer.StrokeLayer ->
            val byId = LinkedHashMap<String, space.owork.draw.engine.Stroke>(layer.strokes.size + delta.addedStrokes.size)
            for (s in layer.strokes) byId[s.id] = s
            for (s in delta.addedStrokes) byId[s.id] = s
            val nextTombstones = (layer.tombstones + delta.addedTombstones) - delta.removedTombstones.toSet()
            layer.copy(
                strokes = byId.values.sortedBy { it.id },
                tombstones = nextTombstones,
            )
        }
        return if (delta.undoStack != null) updated.copy(undoStack = delta.undoStack!!) else updated
    }

    private fun writeFully(ch: FileChannel, bytes: ByteArray) {
        val buf = ByteBuffer.wrap(bytes)
        while (buf.hasRemaining()) {
            ch.write(buf)
        }
    }
}

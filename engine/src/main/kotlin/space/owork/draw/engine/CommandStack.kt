package space.owork.draw.engine

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Incremental delta emitted by each document mutation so the storage WAL only flushes
 * what changed since the last Git checkpoint.
 */
@Serializable
data class DocumentDelta(
    val documentId: String,
    val layerId: String,
    val addedStrokes: List<Stroke> = emptyList(),
    val addedTombstones: List<String> = emptyList(),
    val removedTombstones: List<String> = emptyList(),
    val undoStack: UndoStackSnapshot? = null,
    val timestampMs: Long = System.currentTimeMillis(),
) {
    val isEmpty: Boolean
        get() = addedStrokes.isEmpty() &&
            addedTombstones.isEmpty() &&
            removedTombstones.isEmpty() &&
            undoStack == null
}

/**
 * Compact, serializable representation of a command in the persistent Undo/Redo stack.
 * Because undone strokes remain in `layer.strokes` (marked in `tombstones`), persisting
 * a 200-step undo/redo stack only requires storing stroke ULIDs (~a few KB).
 */
@Serializable
sealed interface PersistedCommand {
    val layerId: String

    @Serializable
    @SerialName("add_stroke")
    data class AddStrokeRef(
        override val layerId: String,
        val strokeId: String,
    ) : PersistedCommand

    @Serializable
    @SerialName("erase_strokes")
    data class EraseStrokesRef(
        override val layerId: String,
        val strokeIds: List<String>,
    ) : PersistedCommand
}

@Serializable
data class UndoStackSnapshot(
    val undo: List<PersistedCommand> = emptyList(),
    val redo: List<PersistedCommand> = emptyList(),
) {
    val isEmpty: Boolean get() = undo.isEmpty() && redo.isEmpty()
}

sealed interface DocumentCommand {
    val layerId: String

    fun apply(document: Document, nowMs: Long = System.currentTimeMillis()): Pair<Document, DocumentDelta>
    fun revert(document: Document, nowMs: Long = System.currentTimeMillis()): Pair<Document, DocumentDelta>
    fun toPersisted(): PersistedCommand

    data class AddStroke(
        override val layerId: String,
        val stroke: Stroke? = null,
        val strokeId: String = requireNotNull(stroke) { "stroke or strokeId required" }.id,
    ) : DocumentCommand {
        override fun apply(document: Document, nowMs: Long): Pair<Document, DocumentDelta> {
            val next = document.updateStrokeLayer(layerId, nowMs) { layer ->
                val updatedStrokes = if (stroke != null && layer.strokes.none { it.id == strokeId }) {
                    layer.strokes + stroke
                } else {
                    layer.strokes
                }
                layer.copy(
                    strokes = updatedStrokes,
                    tombstones = layer.tombstones - strokeId,
                )
            }
            return next to DocumentDelta(
                documentId = document.id,
                layerId = layerId,
                addedStrokes = listOfNotNull(stroke),
                removedTombstones = listOf(strokeId),
                timestampMs = nowMs,
            )
        }

        override fun revert(document: Document, nowMs: Long): Pair<Document, DocumentDelta> {
            val next = document.updateStrokeLayer(layerId, nowMs) { layer ->
                layer.copy(tombstones = layer.tombstones + strokeId)
            }
            return next to DocumentDelta(
                documentId = document.id,
                layerId = layerId,
                addedTombstones = listOf(strokeId),
                timestampMs = nowMs,
            )
        }

        override fun toPersisted(): PersistedCommand =
            PersistedCommand.AddStrokeRef(layerId = layerId, strokeId = strokeId)
    }

    data class EraseStrokes(
        override val layerId: String,
        val strokeIds: List<String>,
    ) : DocumentCommand {
        override fun apply(document: Document, nowMs: Long): Pair<Document, DocumentDelta> {
            val next = document.updateStrokeLayer(layerId, nowMs) { layer ->
                layer.copy(tombstones = layer.tombstones + strokeIds)
            }
            return next to DocumentDelta(
                documentId = document.id,
                layerId = layerId,
                addedTombstones = strokeIds,
                timestampMs = nowMs,
            )
        }

        override fun revert(document: Document, nowMs: Long): Pair<Document, DocumentDelta> {
            val next = document.updateStrokeLayer(layerId, nowMs) { layer ->
                layer.copy(tombstones = layer.tombstones - strokeIds.toSet())
            }
            return next to DocumentDelta(
                documentId = document.id,
                layerId = layerId,
                removedTombstones = strokeIds,
                timestampMs = nowMs,
            )
        }

        override fun toPersisted(): PersistedCommand =
            PersistedCommand.EraseStrokesRef(layerId = layerId, strokeIds = strokeIds)
    }

    companion object {
        fun fromPersisted(cmd: PersistedCommand): DocumentCommand = when (cmd) {
            is PersistedCommand.AddStrokeRef -> AddStroke(layerId = cmd.layerId, stroke = null, strokeId = cmd.strokeId)
            is PersistedCommand.EraseStrokesRef -> EraseStrokes(layerId = cmd.layerId, strokeIds = cmd.strokeIds)
        }
    }
}

class CommandStack(
    private val maxHistory: Int = 200,
) {
    private val undoStack = ArrayDeque<DocumentCommand>()
    private val redoStack = ArrayDeque<DocumentCommand>()

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()
    val undoDepth: Int get() = undoStack.size

    fun snapshot(): UndoStackSnapshot = UndoStackSnapshot(
        undo = undoStack.map { it.toPersisted() },
        redo = redoStack.map { it.toPersisted() },
    )

    fun restore(snapshot: UndoStackSnapshot) {
        undoStack.clear()
        redoStack.clear()
        snapshot.undo.takeLast(maxHistory).forEach { undoStack.addLast(DocumentCommand.fromPersisted(it)) }
        snapshot.redo.takeLast(maxHistory).forEach { redoStack.addLast(DocumentCommand.fromPersisted(it)) }
    }

    fun execute(
        document: Document,
        command: DocumentCommand,
        nowMs: Long = System.currentTimeMillis(),
    ): Pair<Document, DocumentDelta> {
        val (nextDoc, rawDelta) = command.apply(document, nowMs)
        undoStack.addLast(command)
        while (undoStack.size > maxHistory) {
            undoStack.removeFirst()
        }
        redoStack.clear()
        val snap = snapshot()
        return nextDoc.copy(undoStack = snap) to rawDelta.copy(undoStack = snap)
    }

    fun undo(
        document: Document,
        nowMs: Long = System.currentTimeMillis(),
    ): Pair<Document, DocumentDelta>? {
        val command = undoStack.removeLastOrNull() ?: return null
        val (nextDoc, rawDelta) = command.revert(document, nowMs)
        redoStack.addLast(command)
        val snap = snapshot()
        return nextDoc.copy(undoStack = snap) to rawDelta.copy(undoStack = snap)
    }

    fun redo(
        document: Document,
        nowMs: Long = System.currentTimeMillis(),
    ): Pair<Document, DocumentDelta>? {
        val command = redoStack.removeLastOrNull() ?: return null
        val (nextDoc, rawDelta) = command.apply(document, nowMs)
        undoStack.addLast(command)
        val snap = snapshot()
        return nextDoc.copy(undoStack = snap) to rawDelta.copy(undoStack = snap)
    }

    fun clear() {
        undoStack.clear()
        redoStack.clear()
    }
}

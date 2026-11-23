package space.owork.draw.storage

import space.owork.draw.engine.CanvasBackground
import space.owork.draw.engine.CanvasMode
import space.owork.draw.engine.Document
import space.owork.draw.engine.DocumentDelta
import space.owork.draw.engine.DocumentMeta
import space.owork.draw.engine.Stroke
import space.owork.draw.engine.WorldRect

data class DocumentSummary(
    val meta: DocumentMeta,
    val strokeCount: Int,
    val hasUncommittedChanges: Boolean,
    val previewStrokes: List<Stroke> = emptyList(),
    val contentBounds: WorldRect? = null,
)

data class CheckpointInfo(
    val id: String,
    val label: String,
    val timestampMs: Long,
    val isNamedSnapshot: Boolean,
)

data class VariationInfo(
    val name: String,
    val isCurrent: Boolean,
    val headCommitId: String?,
)

/**
 * Domain-level storage abstraction hiding Git completely from UI and rendering code.
 * A single workspace owns one bare Git repository containing multiple documents under `docs/<id>/`.
 */
interface WorkspaceStore : AutoCloseable {
    suspend fun initialize()

    suspend fun listDocuments(): List<DocumentSummary>

    suspend fun createDocument(
        title: String,
        canvasMode: CanvasMode,
        background: CanvasBackground = CanvasBackground(),
    ): Document

    suspend fun loadDocument(documentId: String): Document

    /**
     * Crash-safe incremental working save (writes to atomic WAL outside `.git/`).
     * Safe to call frequently (e.g., 1-2s debounced after strokes) with zero Git overhead.
     */
    suspend fun flushDelta(documentId: String, delta: DocumentDelta)

    /**
     * Commits the current document state into immutable Git history, updates `preview.svg` and
     * GitHub navigation `README.md`s, and clears the WAL.
     */
    suspend fun checkpoint(
        document: Document,
        label: String? = null,
        isNamedSnapshot: Boolean = false,
    ): CheckpointInfo

    suspend fun listHistory(documentId: String, limit: Int = 50): List<CheckpointInfo>

    suspend fun loadDocumentAtCheckpoint(documentId: String, checkpointId: String): Document

    suspend fun deleteDocument(documentId: String)

    suspend fun listVariations(): List<VariationInfo>

    suspend fun createVariation(name: String, switchToIt: Boolean = true): VariationInfo

    suspend fun switchVariation(name: String)

    /**
     * Verifies repository integrity (object hashes and active ref reachability).
     */
    suspend fun verifyIntegrity(): Boolean
}

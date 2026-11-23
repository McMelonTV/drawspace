package space.owork.draw.storage

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.eclipse.jgit.lib.CommitBuilder
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.ObjectChecker
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.lib.RefUpdate
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.treewalk.filter.AndTreeFilter
import org.eclipse.jgit.treewalk.filter.PathFilter
import org.eclipse.jgit.treewalk.filter.TreeFilter
import space.owork.draw.engine.CanvasBackground
import space.owork.draw.engine.CanvasMode
import space.owork.draw.engine.ColorPalette
import space.owork.draw.engine.Document
import space.owork.draw.engine.DocumentCodec
import space.owork.draw.engine.DocumentDelta
import space.owork.draw.engine.DocumentMeta
import space.owork.draw.engine.DocumentPreviewGenerator
import space.owork.draw.engine.Layer
import space.owork.draw.engine.LayerKind
import java.io.File
import java.io.RandomAccessFile
import java.time.Instant
import java.time.ZoneOffset

/**
 * Corruption-hardened Git-native [WorkspaceStore] backed by a bare JGit repository.
 *
 * Corruption-prevention guarantees:
 * 1. Bare repo + pure plumbing (`ObjectInserter` / `TreeFormatter` / `CommitBuilder` / atomic CAS `RefUpdate`):
 *    zero `.git/index`, zero working-tree stat-cache races, atomic commit visibility.
 * 2. `core.fsyncObjectFiles = true`, `core.fsyncRefFiles = true`, `gc.auto = 0`.
 * 3. Serialized IO dispatcher + coroutine [Mutex] + OS file lock (`.workspace.lock`).
 * 4. Crash-safe [AtomicWal] outside `.git/` with automatic recovery on [initialize].
 * 5. `refs/backup/last-good` updated before every mutation for instant recovery.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GitWorkspaceStore(
    private val workspaceDir: File,
    private val authorName: String = "Drawspace",
    private val authorEmail: String = "drawspace@owork.space",
    dispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1),
) : WorkspaceStore {

    private val ioDispatcher = dispatcher
    private val mutex = Mutex()
    private val gitDir = File(workspaceDir, "repo.git")
    private val lockFile = File(workspaceDir, ".workspace.lock")
    private val wal = AtomicWal(File(workspaceDir, "wal"))

    @Volatile
    private var repository: Repository? = null

    override suspend fun initialize() = withRepoLock {
        workspaceDir.mkdirs()
        val repo = openOrCreateBareRepo()
        repository = repo

        // Ensure an initial commit exists on the default branch so HEAD is always valid.
        if (resolveHeadCommit(repo) == null) {
            commitTree(
                repo = repo,
                parentId = null,
                mutator = { files, insert ->
                    files[ROOT_README] = insert(DocumentPreviewGenerator.generateWorkspaceReadme(emptyList()))
                    files[WORKSPACE_PALETTE] = insert(DocumentCodec.encodePalette(ColorPalette()))
                },
                message = "Initialize Drawspace workspace",
                timestampMs = System.currentTimeMillis(),
            )
        }

        // Recover any uncommitted WAL deltas left behind by an abrupt process kill.
        recoverPendingWalsLocked(repo)
    }

    override suspend fun listDocuments(): List<DocumentSummary> = withRepoLock {
        val repo = requireRepo()
        val head = resolveHeadCommit(repo) ?: return@withRepoLock emptyList()
        val files = BareTreeBuilder.readAllBlobIds(repo, head)
        val docIds = extractDocumentIds(files)
        docIds.mapNotNull { docId ->
            runCatching {
                val baseDoc = readDocumentFromFiles(repo, files, docId)
                val recovered = wal.replayOnto(baseDoc)
                val visible = recovered.visibleStrokes()
                DocumentSummary(
                    meta = recovered.meta,
                    strokeCount = visible.size,
                    hasUncommittedChanges = wal.hasPending(docId),
                    previewStrokes = visible,
                    contentBounds = recovered.contentBounds(),
                )
            }.getOrNull()
        }.sortedByDescending { it.meta.updatedAtMs }
    }

    override suspend fun createDocument(
        title: String,
        canvasMode: CanvasMode,
        background: CanvasBackground,
    ): Document = withRepoLock {
        val repo = requireRepo()
        val doc = Document.createNew(
            title = title.ifBlank { "Unnamed Sketch" },
            canvasMode = canvasMode,
            background = background,
        )
        commitDocumentLocked(
            repo = repo,
            document = doc,
            message = "Create sketch: ${doc.title}",
            isNamedSnapshot = true,
        )
        doc
    }

    override suspend fun loadDocument(documentId: String): Document = withRepoLock {
        val repo = requireRepo()
        val head = resolveHeadCommit(repo) ?: error("Workspace has no commits")
        val files = BareTreeBuilder.readAllBlobIds(repo, head)
        val committed = readDocumentFromFiles(repo, files, documentId)
        wal.replayOnto(committed)
    }

    override suspend fun flushDelta(documentId: String, delta: DocumentDelta) = withRepoLock {
        wal.appendDelta(documentId, delta)
    }

    override suspend fun checkpoint(
        document: Document,
        label: String?,
        isNamedSnapshot: Boolean,
    ): CheckpointInfo = withRepoLock {
        val repo = requireRepo()
        val message = label?.takeIf { it.isNotBlank() } ?: "Auto-checkpoint: ${document.title}"
        val info = commitDocumentLocked(
            repo = repo,
            document = document,
            message = message,
            isNamedSnapshot = isNamedSnapshot,
        )
        wal.clear(document.id)
        info
    }

    override suspend fun listHistory(documentId: String, limit: Int): List<CheckpointInfo> = withRepoLock {
        val repo = requireRepo()
        val head = resolveHeadCommit(repo) ?: return@withRepoLock emptyList()
        val result = ArrayList<CheckpointInfo>()
        RevWalk(repo).use { walk ->
            walk.markStart(walk.parseCommit(head))
            walk.treeFilter = AndTreeFilter.create(
                PathFilter.create("docs/$documentId"),
                TreeFilter.ANY_DIFF,
            )
            for (commit in walk) {
                val full = commit.fullMessage
                val named = full.contains(NAMED_TRAILER)
                val titleLine = commit.shortMessage
                result.add(
                    CheckpointInfo(
                        id = commit.id.name,
                        label = titleLine,
                        timestampMs = commit.commitTime.toLong() * 1000L,
                        isNamedSnapshot = named,
                    ),
                )
                if (result.size >= limit) break
            }
        }
        result
    }

    override suspend fun loadDocumentAtCheckpoint(documentId: String, checkpointId: String): Document = withRepoLock {
        val repo = requireRepo()
        val commitId = ObjectId.fromString(checkpointId)
        val files = BareTreeBuilder.readAllBlobIds(repo, commitId)
        readDocumentFromFiles(repo, files, documentId)
    }

    override suspend fun deleteDocument(documentId: String) = withRepoLock {
        val repo = requireRepo()
        val parent = resolveHeadCommit(repo) ?: return@withRepoLock
        val prefix = "docs/$documentId/"
        commitTree(
            repo = repo,
            parentId = parent,
            mutator = { files, insert ->
                val keysToRemove = files.keys.filter { it.startsWith(prefix) }
                for (k in keysToRemove) files.remove(k)
                val remainingMetas = loadAllDocumentMetas(repo, files)
                files[ROOT_README] = insert(DocumentPreviewGenerator.generateWorkspaceReadme(remainingMetas))
            },
            message = "Delete sketch: $documentId",
            timestampMs = System.currentTimeMillis(),
        )
        wal.clear(documentId)
    }

    override suspend fun listVariations(): List<VariationInfo> = withRepoLock {
        val repo = requireRepo()
        val currentBranch = repo.branch
        val refs = repo.refDatabase.getRefsByPrefix(Constants.R_HEADS)
        refs.map { ref ->
            val shortName = ref.name.removePrefix(Constants.R_HEADS)
            VariationInfo(
                name = shortName,
                isCurrent = shortName == currentBranch,
                headCommitId = ref.objectId?.name,
            )
        }.sortedBy { it.name }
    }

    override suspend fun createVariation(name: String, switchToIt: Boolean): VariationInfo = withRepoLock {
        val repo = requireRepo()
        val safeName = sanitizeVariationName(name)
        val head = resolveHeadCommit(repo) ?: error("Cannot create variation on empty workspace")
        val targetRef = Constants.R_HEADS + safeName
        val update = repo.updateRef(targetRef)
        update.setNewObjectId(head)
        update.setExpectedOldObjectId(ObjectId.zeroId())
        val res = update.update()
        check(res == RefUpdate.Result.NEW) { "Failed to create variation '$safeName': $res" }

        if (switchToIt) {
            val headUpdate = repo.updateRef(Constants.HEAD)
            val linkRes = headUpdate.link(targetRef)
            check(linkRes == RefUpdate.Result.FORCED || linkRes == RefUpdate.Result.NO_CHANGE || linkRes == RefUpdate.Result.NEW) {
                "Failed to switch HEAD to '$safeName': $linkRes"
            }
        }
        VariationInfo(name = safeName, isCurrent = switchToIt, headCommitId = head.name)
    }

    override suspend fun switchVariation(name: String) = withRepoLock {
        val repo = requireRepo()
        val safeName = sanitizeVariationName(name)
        val targetRef = Constants.R_HEADS + safeName
        require(repo.exactRef(targetRef) != null) { "Variation '$safeName' does not exist" }
        val headUpdate = repo.updateRef(Constants.HEAD)
        val res = headUpdate.link(targetRef)
        check(res == RefUpdate.Result.FORCED || res == RefUpdate.Result.NO_CHANGE || res == RefUpdate.Result.NEW) {
            "Failed to switch to variation '$safeName': $res"
        }
    }

    override suspend fun verifyIntegrity(): Boolean = withRepoLock {
        val repo = requireRepo()
        val head = resolveHeadCommit(repo) ?: return@withRepoLock true
        val checker = ObjectChecker()
        RevWalk(repo).use { revWalk ->
            val commit = revWalk.parseCommit(head)
            val rawCommit = repo.open(commit.id, Constants.OBJ_COMMIT).bytes
            checker.checkCommit(rawCommit)
            val rawTree = repo.open(commit.tree.id, Constants.OBJ_TREE).bytes
            checker.checkTree(rawTree)
            val files = BareTreeBuilder.readAllBlobIds(repo, head)
            for ((_, blobId) in files) {
                // Reading and inflating every reachable blob validates zlib & SHA-1 integrity.
                val loader = repo.open(blobId, Constants.OBJ_BLOB)
                loader.bytes
            }
        }
        true
    }

    override fun close() {
        repository?.close()
        repository = null
    }

    private fun openOrCreateBareRepo(): Repository {
        val exists = File(gitDir, "HEAD").exists()
        val repo = FileRepositoryBuilder()
            .setGitDir(gitDir)
            .setBare()
            .build()
        if (!exists) {
            gitDir.mkdirs()
            repo.create(true)
            val headRef = repo.updateRef(Constants.HEAD)
            headRef.link(DEFAULT_BRANCH_REF)
        }
        // Harden durability & prevent surprise background GC on Android.
        val cfg = repo.config
        var dirty = false
        if (!cfg.getBoolean("core", "fsyncObjectFiles", false)) {
            cfg.setBoolean("core", null, "fsyncObjectFiles", true)
            dirty = true
        }
        if (!cfg.getBoolean("core", "fsyncRefFiles", false)) {
            cfg.setBoolean("core", null, "fsyncRefFiles", true)
            dirty = true
        }
        if (cfg.getInt("gc", "auto", -1) != 0) {
            cfg.setInt("gc", null, "auto", 0)
            dirty = true
        }
        if (dirty) cfg.save()
        return repo
    }

    private fun recoverPendingWalsLocked(repo: Repository) {
        val pendingIds = wal.pendingDocumentIds()
        if (pendingIds.isEmpty()) return
        val head = resolveHeadCommit(repo) ?: return
        val files = BareTreeBuilder.readAllBlobIds(repo, head)
        for (docId in pendingIds) {
            if ("docs/$docId/document.json" !in files) {
                wal.clear(docId)
                continue
            }
            val baseDoc = readDocumentFromFiles(repo, files, docId)
            val recovered = wal.replayOnto(baseDoc)
            commitDocumentLocked(
                repo = repo,
                document = recovered,
                message = "Recovered unsaved session: ${recovered.title}",
                isNamedSnapshot = false,
            )
            wal.clear(docId)
        }
    }

    private fun commitDocumentLocked(
        repo: Repository,
        document: Document,
        message: String,
        isNamedSnapshot: Boolean,
    ): CheckpointInfo {
        val nowMs = document.meta.updatedAtMs.coerceAtLeast(System.currentTimeMillis())
        val updatedMeta = document.meta.copy(
            updatedAtMs = nowMs,
            layerOrder = document.layers.map { it.id },
        )
        val normalizedDoc = document.copy(meta = updatedMeta)
        val parent = resolveHeadCommit(repo)
        val fullMessage = if (isNamedSnapshot) "$message\n\n$NAMED_TRAILER" else message

        val commitId = commitTree(
            repo = repo,
            parentId = parent,
            mutator = { files, insert ->
                val docPrefix = "docs/${normalizedDoc.id}/"
                // Preserve untouched raster tiles while updating document metadata, stroke layers, and previews.
                val staleKeys = files.keys.filter { key ->
                    key.startsWith(docPrefix) && !key.contains("/tiles/")
                }
                for (k in staleKeys) files.remove(k)

                files["${docPrefix}document.json"] = insert(DocumentCodec.encodeDocumentMeta(normalizedDoc.meta))
                if (!normalizedDoc.undoStack.isEmpty) {
                    files["${docPrefix}undo_stack.json"] = insert(DocumentCodec.encodeUndoStack(normalizedDoc.undoStack))
                }
                files["${docPrefix}preview.svg"] = insert(DocumentPreviewGenerator.generateSvg(normalizedDoc))
                files["${docPrefix}README.md"] = insert(DocumentPreviewGenerator.generateDocumentReadme(normalizedDoc))

                for (layer in normalizedDoc.layers) {
                    val layerPrefix = "${docPrefix}layers/${layer.id}/"
                    files["${layerPrefix}meta.json"] = insert(DocumentCodec.encodeLayerMeta(layer.toMeta()))
                    when (layer) {
                        is Layer.StrokeLayer -> {
                            val chunks = DocumentCodec.encodeStrokeChunks(layer.strokes)
                            for ((fileName, bytes) in chunks) {
                                files["${layerPrefix}strokes/$fileName"] = insert(bytes)
                            }
                            if (layer.tombstones.isNotEmpty()) {
                                files["${layerPrefix}tombstones.jsonl"] =
                                    insert(DocumentCodec.encodeTombstones(layer.tombstones))
                            }
                        }

                        is Layer.RasterLayer -> {
                            // Tile PNGs under `${layerPrefix}tiles/<tx>_<ty>.png` are preserved above
                            // and written incrementally when raster brush painting is active.
                        }
                    }
                }

                // Refresh workspace root gallery README.md for GitHub browsing.
                val metas = loadAllDocumentMetas(repo, files, override = normalizedDoc.meta)
                files[ROOT_README] = insert(DocumentPreviewGenerator.generateWorkspaceReadme(metas))
            },
            message = fullMessage,
            timestampMs = nowMs,
        )

        return CheckpointInfo(
            id = commitId.name,
            label = message,
            timestampMs = nowMs,
            isNamedSnapshot = isNamedSnapshot,
        )
    }

    private fun commitTree(
        repo: Repository,
        parentId: ObjectId?,
        mutator: (MutableMap<String, ObjectId>, (ByteArray) -> ObjectId) -> Unit,
        message: String,
        timestampMs: Long,
    ): ObjectId {
        val files = BareTreeBuilder.readAllBlobIds(repo, parentId)
        val branchRefName = resolveCurrentBranchRefName(repo)

        val commitId = repo.newObjectInserter().use { inserter ->
            val insertBlob: (ByteArray) -> ObjectId = { bytes ->
                inserter.insert(Constants.OBJ_BLOB, bytes)
            }
            mutator(files, insertBlob)

            val treeId = BareTreeBuilder.writeTree(inserter, files)
            val ident = PersonIdent(authorName, authorEmail, Instant.ofEpochMilli(timestampMs), ZoneOffset.UTC)
            val builder = CommitBuilder().apply {
                setTreeId(treeId)
                if (parentId != null) setParentId(parentId)
                author = ident
                committer = ident
                this.message = message
            }
            val newCommitId = inserter.insert(builder)
            inserter.flush()
            newCommitId
        }

        // Keep a safety backup ref pointing at the previous known-good commit.
        if (parentId != null) {
            val backup = repo.updateRef(BACKUP_LAST_GOOD_REF)
            backup.setNewObjectId(parentId)
            backup.isForceUpdate = true
            backup.update()
        }

        // Atomic compare-and-swap update on the active branch ref.
        val update = repo.updateRef(branchRefName)
        update.setNewObjectId(commitId)
        update.setExpectedOldObjectId(parentId ?: ObjectId.zeroId())
        val result = update.update()
        check(
            result == RefUpdate.Result.NEW ||
                result == RefUpdate.Result.FAST_FORWARD ||
                result == RefUpdate.Result.NO_CHANGE,
        ) {
            "Atomic ref update failed for $branchRefName: $result"
        }
        return commitId
    }

    private fun readDocumentFromFiles(
        repo: Repository,
        files: Map<String, ObjectId>,
        documentId: String,
    ): Document {
        val docPrefix = "docs/$documentId/"
        val metaId = files["${docPrefix}document.json"]
            ?: throw NoSuchElementException("Document '$documentId' not found")
        val meta = DocumentCodec.decodeDocumentMeta(BareTreeBuilder.readBlobBytes(repo, metaId))

        val layers = meta.layerOrder.mapNotNull { layerId ->
            val layerPrefix = "${docPrefix}layers/$layerId/"
            val layerMetaId = files["${layerPrefix}meta.json"] ?: return@mapNotNull null
            val layerMeta = DocumentCodec.decodeLayerMeta(BareTreeBuilder.readBlobBytes(repo, layerMetaId))
            when (layerMeta.kind) {
                LayerKind.STROKE -> {
                    val strokePrefix = "${layerPrefix}strokes/"
                    val chunkBytes = LinkedHashMap<String, ByteArray>()
                    for ((path, blobId) in files) {
                        if (path.startsWith(strokePrefix) && path.endsWith(".jsonl")) {
                            chunkBytes[path.removePrefix(strokePrefix)] = BareTreeBuilder.readBlobBytes(repo, blobId)
                        }
                    }
                    val tombstonesId = files["${layerPrefix}tombstones.jsonl"]
                    val tombstones = tombstonesId?.let {
                        DocumentCodec.decodeTombstones(BareTreeBuilder.readBlobBytes(repo, it))
                    }.orEmpty()

                    Layer.StrokeLayer(
                        id = layerMeta.id,
                        name = layerMeta.name,
                        visible = layerMeta.visible,
                        opacity = layerMeta.opacity,
                        blendMode = layerMeta.blendMode,
                        strokes = DocumentCodec.decodeStrokeChunks(chunkBytes),
                        tombstones = tombstones,
                    )
                }

                LayerKind.RASTER -> {
                    Layer.RasterLayer(
                        id = layerMeta.id,
                        name = layerMeta.name,
                        visible = layerMeta.visible,
                        opacity = layerMeta.opacity,
                        blendMode = layerMeta.blendMode,
                        tileSize = layerMeta.tileSize,
                    )
                }
            }
        }

        val ensuredLayers = if (layers.isEmpty()) listOf(Layer.StrokeLayer(name = "Sketch")) else layers
        val undoStack = files["${docPrefix}undo_stack.json"]?.let {
            DocumentCodec.decodeUndoStack(BareTreeBuilder.readBlobBytes(repo, it))
        } ?: space.owork.draw.engine.UndoStackSnapshot()
        return Document(meta = meta, layers = ensuredLayers, undoStack = undoStack)
    }

    private fun extractDocumentIds(files: Map<String, ObjectId>): List<String> =
        files.keys.asSequence()
            .filter { it.startsWith("docs/") && it.endsWith("/document.json") }
            .map { it.removePrefix("docs/").removeSuffix("/document.json") }
            .filter { '/' !in it }
            .distinct()
            .toList()

    private fun loadAllDocumentMetas(
        repo: Repository,
        files: Map<String, ObjectId>,
        override: DocumentMeta? = null,
    ): List<DocumentMeta> {
        val result = ArrayList<DocumentMeta>()
        for (docId in extractDocumentIds(files)) {
            if (override != null && docId == override.id) {
                result.add(override)
                continue
            }
            val metaId = files["docs/$docId/document.json"] ?: continue
            runCatching {
                DocumentCodec.decodeDocumentMeta(BareTreeBuilder.readBlobBytes(repo, metaId))
            }.onSuccess { result.add(it) }
        }
        return result
    }

    private fun resolveHeadCommit(repo: Repository): ObjectId? =
        repo.resolve(Constants.HEAD)

    private fun resolveCurrentBranchRefName(repo: Repository): String {
        val headRef = repo.exactRef(Constants.HEAD)
        return if (headRef != null && headRef.isSymbolic) {
            headRef.target.name
        } else {
            DEFAULT_BRANCH_REF
        }
    }

    private fun requireRepo(): Repository =
        repository ?: error("WorkspaceStore.initialize() must be called before use")

    private fun sanitizeVariationName(name: String): String {
        val cleaned = name.trim()
            .lowercase()
            .replace(Regex("[^a-z0-9._-]+"), "-")
            .trim('-', '.')
        require(cleaned.isNotEmpty()) { "Variation name cannot be blank" }
        return cleaned
    }

    private suspend fun <T> withRepoLock(block: () -> T): T = mutex.withLock {
        withContext(ioDispatcher) {
            workspaceDir.mkdirs()
            RandomAccessFile(lockFile, "rw").use { raf ->
                raf.channel.lock().use {
                    block()
                }
            }
        }
    }

    companion object {
        private const val DEFAULT_BRANCH_REF = "refs/heads/main"
        private const val BACKUP_LAST_GOOD_REF = "refs/backup/last-good"
        private const val ROOT_README = "README.md"
        private const val WORKSPACE_PALETTE = "library/palette.json"
        private const val NAMED_TRAILER = "Drawspace-Snapshot: named"
    }
}

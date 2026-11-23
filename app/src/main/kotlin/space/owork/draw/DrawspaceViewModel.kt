package space.owork.draw

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import space.owork.draw.engine.AppSettings
import space.owork.draw.engine.AppTheme
import space.owork.draw.engine.BrushSpec
import space.owork.draw.engine.CameraLocks
import space.owork.draw.engine.CanvasBackground
import space.owork.draw.engine.CanvasLockMode
import space.owork.draw.engine.CanvasMode
import space.owork.draw.engine.ColorPalette
import space.owork.draw.engine.CommandStack
import space.owork.draw.engine.Document
import space.owork.draw.engine.DocumentCodec
import space.owork.draw.engine.DocumentCommand
import space.owork.draw.engine.DocumentDelta
import space.owork.draw.engine.Stroke
import space.owork.draw.engine.ToolType
import space.owork.draw.engine.ViewportCamera
import space.owork.draw.renderer.DocumentPngExporter
import space.owork.draw.storage.CheckpointInfo
import space.owork.draw.storage.DocumentSummary
import space.owork.draw.storage.GitWorkspaceStore
import space.owork.draw.storage.VariationInfo
import space.owork.draw.storage.WorkspaceStore
import java.io.File

data class AppUiState(
    val isLoading: Boolean = true,
    val showSettings: Boolean = false,
    val settings: AppSettings = AppSettings(),
    val documents: List<DocumentSummary> = emptyList(),
    val variations: List<VariationInfo> = emptyList(),
    val activeDocument: Document? = null,
    val activeTool: ToolType = ToolType.PEN,
    val activeColorArgb: Int = DEFAULT_DARK_INK,
    val toolBrushes: Map<ToolType, BrushSpec> = defaultToolBrushes(),
    val palette: ColorPalette = ColorPalette(),
    val allowFingerDrawing: Boolean = true,
    val lockMode: CanvasLockMode = CanvasLockMode.UNLOCKED,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val cameraZoom: Float = 1f,
    val cameraRotationRad: Float = 0f,
    val history: List<CheckpointInfo> = emptyList(),
    val historyPreviews: Map<String, DocumentSummary> = emptyMap(),
    val saveStatus: String = "Saved",
    val snackbarMessage: String? = null,
) {
    val appTheme: AppTheme get() = settings.appTheme
    val effectiveCameraLocks: CameraLocks get() = lockMode.toCameraLocks(lockPan = settings.lockPan)

    val activeBrush: BrushSpec
        get() {
            val base = toolBrushes[activeTool] ?: BrushSpec.defaultFor(activeTool)
            return if (activeTool == ToolType.ERASER) base else base.copy(colorArgb = activeColorArgb)
        }

    companion object {
        const val DEFAULT_LIGHT_INK: Int = 0xFF181A1F.toInt()
        const val DEFAULT_DARK_INK: Int = 0xFFF7F7F5.toInt()

        fun defaultToolBrushes(): Map<ToolType, BrushSpec> =
            ToolType.entries.associateWith { BrushSpec.defaultFor(it) }
    }
}

class DrawspaceViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = application.getSharedPreferences("drawspace_prefs", Context.MODE_PRIVATE)
    private val workspaceDir = File(application.filesDir, "workspaces/default")
    private val exportDir = File(application.filesDir, "exports")
    private val store: WorkspaceStore = GitWorkspaceStore(workspaceDir)

    private val commandStack = CommandStack()
    private val pendingDeltas = ArrayList<DocumentDelta>()
    private val inFlightPreviewIds = HashSet<String>()
    private var strokesSinceCheckpoint = 0
    private var walFlushJob: Job? = null
    private var idleCheckpointJob: Job? = null
    private var backgroundSaveJob: Job? = null

    private val initialSettings = loadSavedSettings()
    private val _uiState = MutableStateFlow(
        AppUiState(
            settings = initialSettings,
            allowFingerDrawing = !initialSettings.stylusOnlyDefault,
            lockMode = initialSettings.defaultLockMode,
            activeColorArgb = if (initialSettings.appTheme == AppTheme.LIGHT) {
                AppUiState.DEFAULT_LIGHT_INK
            } else {
                AppUiState.DEFAULT_DARK_INK
            },
        ),
    )
    val uiState: StateFlow<AppUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            store.initialize()
            refreshWorkspace()
            _uiState.update { it.copy(isLoading = false) }
        }
    }

    fun openSettings() {
        _uiState.update { it.copy(showSettings = true) }
    }

    fun closeSettings() {
        _uiState.update { it.copy(showSettings = false) }
    }

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        _uiState.update { state ->
            val prev = state.settings
            val next = transform(prev)
            saveSettings(next)

            val nextAllowFinger = if (next.stylusOnlyDefault != prev.stylusOnlyDefault) {
                !next.stylusOnlyDefault
            } else {
                state.allowFingerDrawing
            }
            val nextLockMode = if (next.defaultLockMode != prev.defaultLockMode) {
                next.defaultLockMode
            } else {
                state.lockMode
            }
            val nextInk = adaptInkColor(state.activeColorArgb, state.activeDocument, next.appTheme)

            state.copy(
                settings = next,
                allowFingerDrawing = nextAllowFinger,
                lockMode = nextLockMode,
                activeColorArgb = nextInk,
            )
        }
    }

    fun createAndOpenDocument(title: String, mode: CanvasMode) {
        viewModelScope.launch {
            val s = _uiState.value.settings
            val bg = CanvasBackground(
                canvasTheme = null, // Default to following App Theme
                pattern = s.defaultPattern,
                spacingPx = s.defaultPatternSpacingPx,
            )
            val doc = store.createDocument(title, mode, background = bg)
            commandStack.clear()
            strokesSinceCheckpoint = 0
            refreshWorkspace()
            openDocumentInternal(doc)
        }
    }

    fun openDocument(documentId: String) {
        viewModelScope.launch {
            val doc = store.loadDocument(documentId)
            strokesSinceCheckpoint = 0
            openDocumentInternal(doc)
        }
    }

    fun renameActiveDocument(newTitle: String) {
        viewModelScope.launch {
            val doc = _uiState.value.activeDocument ?: return@launch
            val cleaned = newTitle.trim().ifEmpty { "Unnamed Sketch" }
            if (cleaned == doc.title) return@launch
            val renamed = doc.copy(meta = doc.meta.copy(title = cleaned, updatedAtMs = System.currentTimeMillis()))
            _uiState.update { it.copy(activeDocument = renamed, saveStatus = "Saving…") }
            store.checkpoint(renamed, label = "Renamed to $cleaned", isNamedSnapshot = false)
            val history = store.listHistory(renamed.id)
            _uiState.update { it.copy(history = history, saveStatus = "Saved") }
        }
    }

    fun closeDocumentToWorkspace() {
        viewModelScope.launch {
            checkpointIfDirty(label = null, isNamed = false)
            commandStack.clear()
            _uiState.update {
                it.copy(
                    activeDocument = null,
                    canUndo = false,
                    canRedo = false,
                    history = emptyList(),
                    historyPreviews = emptyMap(),
                )
            }
            inFlightPreviewIds.clear()
            refreshWorkspace()
        }
    }

    fun deleteDocument(documentId: String) {
        viewModelScope.launch {
            store.deleteDocument(documentId)
            refreshWorkspace()
        }
    }

    fun updateCanvasBackground(transform: (CanvasBackground) -> CanvasBackground) {
        val doc = _uiState.value.activeDocument ?: return
        val nextBg = transform(doc.meta.background)
        val appTheme = _uiState.value.appTheme
        val updated = doc.copy(
            meta = doc.meta.copy(
                background = nextBg,
                backgroundArgb = nextBg.resolvePaperArgb(appTheme),
                updatedAtMs = System.currentTimeMillis(),
            ),
        )
        _uiState.update { state ->
            state.copy(
                activeDocument = updated,
                activeColorArgb = adaptInkColor(state.activeColorArgb, updated, appTheme),
                saveStatus = "Autosaving…",
            )
        }
        backgroundSaveJob?.cancel()
        backgroundSaveJob = viewModelScope.launch {
            delay(800L)
            val latest = _uiState.value.activeDocument ?: return@launch
            store.checkpoint(latest, label = "Updated canvas background", isNamedSnapshot = false)
            val history = store.listHistory(latest.id)
            _uiState.update { it.copy(history = history, saveStatus = "Saved") }
        }
    }

    fun selectTool(tool: ToolType) {
        _uiState.update { it.copy(activeTool = tool) }
    }

    fun selectColor(argb: Int) {
        _uiState.update { state ->
            val nextTool = if (state.activeTool == ToolType.ERASER) ToolType.PEN else state.activeTool
            state.copy(activeColorArgb = argb, activeTool = nextTool)
        }
    }

    fun updateBrushSize(sizeWorldPx: Float) {
        _uiState.update { state ->
            val current = state.toolBrushes[state.activeTool] ?: BrushSpec.defaultFor(state.activeTool)
            val updated = state.toolBrushes + (state.activeTool to current.copy(sizeWorldPx = sizeWorldPx))
            state.copy(toolBrushes = updated)
        }
    }

    fun updateBrushOpacity(opacity: Float) {
        _uiState.update { state ->
            val current = state.toolBrushes[state.activeTool] ?: BrushSpec.defaultFor(state.activeTool)
            val updated = state.toolBrushes + (state.activeTool to current.copy(opacity = opacity))
            state.copy(toolBrushes = updated)
        }
    }

    fun toggleFingerDrawing() {
        _uiState.update { it.copy(allowFingerDrawing = !it.allowFingerDrawing) }
    }

    fun cycleLockMode() {
        _uiState.update { state ->
            val nextMode = state.lockMode.next()
            val updatedSettings = state.settings.copy(defaultLockMode = nextMode)
            saveSettings(updatedSettings)
            state.copy(
                lockMode = nextMode,
                settings = updatedSettings,
                snackbarMessage = "Canvas: ${nextMode.label}",
            )
        }
    }

    fun onCameraChanged(camera: ViewportCamera) {
        _uiState.update { it.copy(cameraZoom = camera.zoom, cameraRotationRad = camera.rotationRad) }
    }

    fun onStrokeCommitted(stroke: Stroke) {
        val doc = _uiState.value.activeDocument ?: return
        val layerId = doc.primaryStrokeLayer().id
        val (nextDoc, delta) = commandStack.execute(doc, DocumentCommand.AddStroke(layerId, stroke))
        applyMutation(nextDoc, delta)
    }

    fun onStrokesErased(strokeIds: List<String>) {
        if (strokeIds.isEmpty()) return
        val doc = _uiState.value.activeDocument ?: return
        val layerId = doc.primaryStrokeLayer().id
        val (nextDoc, delta) = commandStack.execute(doc, DocumentCommand.EraseStrokes(layerId, strokeIds))
        applyMutation(nextDoc, delta)
    }

    fun undo() {
        val doc = _uiState.value.activeDocument ?: return
        val result = commandStack.undo(doc) ?: return
        applyMutation(result.first, result.second)
    }

    fun redo() {
        val doc = _uiState.value.activeDocument ?: return
        val result = commandStack.redo(doc) ?: return
        applyMutation(result.first, result.second)
    }

    fun loadCheckpointPreview(checkpointId: String) {
        val docId = _uiState.value.activeDocument?.id ?: return
        if (checkpointId in _uiState.value.historyPreviews || !inFlightPreviewIds.add(checkpointId)) return
        viewModelScope.launch {
            runCatching {
                val snapDoc = store.loadDocumentAtCheckpoint(docId, checkpointId)
                val visible = snapDoc.visibleStrokes()
                DocumentSummary(
                    meta = snapDoc.meta,
                    strokeCount = visible.size,
                    hasUncommittedChanges = false,
                    previewStrokes = visible,
                    contentBounds = snapDoc.contentBounds(),
                )
            }.onSuccess { summary ->
                _uiState.update { state ->
                    if (state.activeDocument?.id != docId) state
                    else state.copy(historyPreviews = state.historyPreviews + (checkpointId to summary))
                }
            }
            inFlightPreviewIds.remove(checkpointId)
        }
    }

    fun saveNamedSnapshot(label: String) {
        viewModelScope.launch {
            val doc = _uiState.value.activeDocument ?: return@launch
            walFlushJob?.cancel()
            idleCheckpointJob?.cancel()
            pendingDeltas.clear()
            strokesSinceCheckpoint = 0
            _uiState.update { it.copy(saveStatus = "Saving snapshot…") }
            store.checkpoint(doc, label = label.ifBlank { "Snapshot" }, isNamedSnapshot = true)
            val history = store.listHistory(doc.id)
            _uiState.update {
                it.copy(
                    history = history,
                    saveStatus = "Snapshot saved",
                    snackbarMessage = "Saved snapshot \"$label\"",
                )
            }
        }
    }

    fun restoreCheckpoint(checkpoint: CheckpointInfo) {
        viewModelScope.launch {
            val currentDoc = _uiState.value.activeDocument ?: return@launch
            val restored = store.loadDocumentAtCheckpoint(currentDoc.id, checkpoint.id)
            commandStack.restore(restored.undoStack)
            pendingDeltas.clear()
            strokesSinceCheckpoint = 0
            store.checkpoint(restored, label = "Restored: ${checkpoint.label}", isNamedSnapshot = false)
            val history = store.listHistory(restored.id)
            _uiState.update {
                it.copy(
                    activeDocument = restored,
                    canUndo = commandStack.canUndo,
                    canRedo = commandStack.canRedo,
                    history = history,
                    saveStatus = "Restored",
                    snackbarMessage = "Restored \"${checkpoint.label}\"",
                )
            }
        }
    }

    fun createVariation(name: String) {
        viewModelScope.launch {
            checkpointIfDirty(label = null, isNamed = false)
            runCatching { store.createVariation(name, switchToIt = true) }
                .onSuccess { v ->
                    refreshWorkspace()
                    _uiState.update { it.copy(snackbarMessage = "Switched to variation \"${v.name}\"") }
                }
                .onFailure { err ->
                    _uiState.update { it.copy(snackbarMessage = err.message ?: "Could not create variation") }
                }
        }
    }

    fun switchVariation(name: String) {
        viewModelScope.launch {
            checkpointIfDirty(label = null, isNamed = false)
            store.switchVariation(name)
            refreshWorkspace()
            _uiState.update { it.copy(snackbarMessage = "Workspace variation: $name") }
        }
    }

    fun exportActiveDocumentPng() {
        viewModelScope.launch {
            val doc = _uiState.value.activeDocument ?: return@launch
            val safeTitle = doc.title.replace(Regex("[^a-zA-Z0-9._-]+"), "_").trim('_').ifEmpty { doc.id }
            val outFile = File(exportDir, "${safeTitle}_${System.currentTimeMillis()}.png")
            val exported = withContext(Dispatchers.IO) {
                DocumentPngExporter.exportPng(doc, outFile, theme = _uiState.value.appTheme)
            }
            _uiState.update { it.copy(snackbarMessage = "Exported PNG: ${exported.name}") }
        }
    }

    fun onAppBackgrounded() {
        viewModelScope.launch {
            checkpointIfDirty(label = null, isNamed = false)
        }
    }

    fun consumeSnackbar() {
        _uiState.update { it.copy(snackbarMessage = null) }
    }

    override fun onCleared() {
        store.close()
        super.onCleared()
    }

    private suspend fun openDocumentInternal(doc: Document) {
        commandStack.restore(doc.undoStack)
        inFlightPreviewIds.clear()
        val history = store.listHistory(doc.id)
        _uiState.update { state ->
            state.copy(
                activeDocument = doc,
                palette = doc.meta.palette,
                allowFingerDrawing = !state.settings.stylusOnlyDefault,
                lockMode = state.settings.defaultLockMode,
                activeColorArgb = adaptInkColor(state.activeColorArgb, doc, state.appTheme),
                canUndo = commandStack.canUndo,
                canRedo = commandStack.canRedo,
                history = history,
                historyPreviews = emptyMap(),
                saveStatus = "Saved",
            )
        }
    }

    private fun applyMutation(nextDoc: Document, delta: DocumentDelta) {
        pendingDeltas.add(delta)
        strokesSinceCheckpoint++
        _uiState.update {
            it.copy(
                activeDocument = nextDoc,
                canUndo = commandStack.canUndo,
                canRedo = commandStack.canRedo,
                saveStatus = "Autosaving…",
            )
        }

        walFlushJob?.cancel()
        walFlushJob = viewModelScope.launch {
            delay(1_200L)
            flushPendingWal(nextDoc.id)
        }

        idleCheckpointJob?.cancel()
        if (strokesSinceCheckpoint >= 40) {
            viewModelScope.launch { checkpointIfDirty(label = null, isNamed = false) }
        } else {
            idleCheckpointJob = viewModelScope.launch {
                delay(25_000L)
                checkpointIfDirty(label = null, isNamed = false)
            }
        }
    }

    private suspend fun flushPendingWal(docId: String) {
        val batch = ArrayList(pendingDeltas)
        pendingDeltas.clear()
        for (d in batch) {
            store.flushDelta(docId, d)
        }
        _uiState.update { it.copy(saveStatus = "Saved") }
    }

    private suspend fun checkpointIfDirty(label: String?, isNamed: Boolean) {
        val doc = _uiState.value.activeDocument ?: return
        if (strokesSinceCheckpoint == 0 && pendingDeltas.isEmpty() && !isNamed) return
        walFlushJob?.cancel()
        idleCheckpointJob?.cancel()
        pendingDeltas.clear()
        strokesSinceCheckpoint = 0
        store.checkpoint(doc, label = label, isNamedSnapshot = isNamed)
        val history = store.listHistory(doc.id)
        _uiState.update { it.copy(history = history, saveStatus = "Checkpointed") }
    }

    private suspend fun refreshWorkspace() {
        val docs = store.listDocuments()
        val variations = store.listVariations()
        _uiState.update { it.copy(documents = docs, variations = variations) }
    }

    private fun adaptInkColor(currentInk: Int, doc: Document?, appTheme: AppTheme): Int {
        val paperIsDark = doc?.meta?.background?.resolvePaperArgb(appTheme)
            ?.let { CanvasBackground.isColorDark(it) }
            ?: (appTheme != AppTheme.LIGHT)
        return when {
            paperIsDark && currentInk == AppUiState.DEFAULT_LIGHT_INK -> AppUiState.DEFAULT_DARK_INK
            !paperIsDark && currentInk == AppUiState.DEFAULT_DARK_INK -> AppUiState.DEFAULT_LIGHT_INK
            else -> currentInk
        }
    }

    private fun loadSavedSettings(): AppSettings {
        val json = prefs.getString(KEY_SETTINGS_JSON, null)
        if (json != null) {
            runCatching { DocumentCodec.compactJson.decodeFromString<AppSettings>(json) }
                .onSuccess { return it }
        }
        // Migrate legacy app_theme key if present
        val legacyTheme = prefs.getString("app_theme", null)
            ?.let { runCatching { AppTheme.valueOf(it) }.getOrNull() }
            ?: AppTheme.DARK
        return AppSettings(appTheme = legacyTheme)
    }

    private fun saveSettings(settings: AppSettings) {
        val encoded = DocumentCodec.compactJson.encodeToString(settings)
        prefs.edit().putString(KEY_SETTINGS_JSON, encoded).apply()
    }

    companion object {
        private const val KEY_SETTINGS_JSON = "app_settings_v1"
    }
}

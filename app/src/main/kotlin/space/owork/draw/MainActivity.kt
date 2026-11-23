package space.owork.draw

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import space.owork.draw.engine.AppTheme
import space.owork.draw.engine.CanvasBackground
import space.owork.draw.ui.CanvasScreen
import space.owork.draw.ui.SettingsScreen
import space.owork.draw.ui.WorkspaceScreen
import space.owork.draw.ui.theme.DrawspaceTheme

class MainActivity : ComponentActivity() {
    private val viewModel: DrawspaceViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val state by viewModel.uiState.collectAsStateWithLifecycle()

            val useDarkSystemIcons = remember(state.appTheme, state.activeDocument, state.showSettings) {
                val doc = state.activeDocument
                if (!state.showSettings && doc != null) {
                    val bgArgb = if (doc.canvasMode.bounds != null) {
                        doc.meta.background.resolveSurroundArgb(state.appTheme)
                    } else {
                        doc.meta.background.resolvePaperArgb(state.appTheme)
                    }
                    !CanvasBackground.isColorDark(bgArgb)
                } else {
                    state.appTheme == AppTheme.LIGHT
                }
            }
            LaunchedEffect(useDarkSystemIcons) {
                val controller = WindowCompat.getInsetsController(window, window.decorView)
                controller.isAppearanceLightStatusBars = useDarkSystemIcons
                controller.isAppearanceLightNavigationBars = useDarkSystemIcons
            }

            DrawspaceTheme(appTheme = state.appTheme) {
                val snackbarHostState = remember { SnackbarHostState() }

                LaunchedEffect(state.snackbarMessage) {
                    val msg = state.snackbarMessage ?: return@LaunchedEffect
                    snackbarHostState.showSnackbar(msg)
                    viewModel.consumeSnackbar()
                }

                BackHandler(enabled = state.showSettings) {
                    viewModel.closeSettings()
                }

                Scaffold(
                    snackbarHost = { SnackbarHost(snackbarHostState) },
                ) { _ ->
                    when {
                        state.isLoading -> {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator()
                            }
                        }

                        state.showSettings -> {
                            SettingsScreen(
                                settings = state.settings,
                                onBack = viewModel::closeSettings,
                                onUpdateSettings = viewModel::updateSettings,
                            )
                        }

                        state.activeDocument != null -> {
                            CanvasScreen(
                                state = state,
                                onBack = viewModel::closeDocumentToWorkspace,
                                onOpenSettings = viewModel::openSettings,
                                onRename = viewModel::renameActiveDocument,
                                onSelectTool = viewModel::selectTool,
                                onSelectColor = viewModel::selectColor,
                                onSizeChange = viewModel::updateBrushSize,
                                onOpacityChange = viewModel::updateBrushOpacity,
                                onToggleFingerDrawing = viewModel::toggleFingerDrawing,
                                onCycleLockMode = viewModel::cycleLockMode,
                                onUpdateBackground = viewModel::updateCanvasBackground,
                                onUndo = viewModel::undo,
                                onRedo = viewModel::redo,
                                onStrokeCommitted = viewModel::onStrokeCommitted,
                                onStrokesErased = viewModel::onStrokesErased,
                                onCameraChanged = viewModel::onCameraChanged,
                                onSaveSnapshot = viewModel::saveNamedSnapshot,
                                onRestoreCheckpoint = viewModel::restoreCheckpoint,
                                onRequestCheckpointPreview = viewModel::loadCheckpointPreview,
                                onExportPng = viewModel::exportActiveDocumentPng,
                            )
                        }

                        else -> {
                            WorkspaceScreen(
                                state = state,
                                onOpenSettings = viewModel::openSettings,
                                onOpenDocument = viewModel::openDocument,
                                onCreateDocument = viewModel::createAndOpenDocument,
                                onDeleteDocument = viewModel::deleteDocument,
                                onCreateVariation = viewModel::createVariation,
                                onSwitchVariation = viewModel::switchVariation,
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onStop() {
        viewModel.onAppBackgrounded()
        super.onStop()
    }
}

package com.veyarc.app

import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.veyarc.app.ui.theme.VeyarcTheme
import com.veyarc.automation.android.ReplayControllerWorkflow
import com.veyarc.automation.android.ReplayFloatingController
import com.veyarc.automation.android.ReplayWorkflowCatalog
import com.veyarc.data.storage.StoredRunRecord
import com.veyarc.data.storage.WorkflowRepository
import com.veyarc.data.storage.WorkflowRevisionDocument
import com.veyarc.data.storage.WorkflowSummary
import com.veyarc.feature.assistant.AssistantPreviewHost
import com.veyarc.feature.assistant.AssistantPreviewState
import com.veyarc.feature.editor.WorkflowEditorScreen
import com.veyarc.feature.editor.WorkflowEditorSession
import com.veyarc.feature.recorder.CoordinateRecorderScreen
import com.veyarc.feature.recorder.CoordinateRecordingRuntime
import com.veyarc.feature.runner.WorkflowRunController
import com.veyarc.feature.runner.WorkflowRunScreen
import com.veyarc.model.bundle.WorkflowBundle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private var resumeGeneration by mutableIntStateOf(0)
    private var selectedDestination by mutableStateOf(VeyarcDestination.HOME)
    private var fullScreenDestination by mutableStateOf<FullScreenDestination?>(null)
    private var workflowToRun by mutableStateOf<WorkflowBundle?>(null)
    private var workflowToEdit by mutableStateOf<WorkflowBundle?>(null)
    private var storedWorkflowToEdit by mutableStateOf<Pair<String, Int>?>(null)
    private var editorReturnToManager = false
    private var recordedJournalFileName by mutableStateOf<String?>(null)
    private var newWorkflowRequestId by mutableIntStateOf(0)
    private var newWorkflowFolderId by mutableStateOf<String?>(null)
    private var recordingFolderId by mutableStateOf<String?>(null)
    private var managerSelectionBar by mutableStateOf<ManagerSelectionBarState?>(null)
    private val runSession: MainRunSession by lazy {
        ViewModelProvider(this)[MainRunSession::class.java]
    }
    private val workflowRepository: WorkflowRepository
        get() = runSession.repository
    private val recordingSession: MainRecordingSession by lazy {
        ViewModelProvider(this)[MainRecordingSession::class.java]
    }
    private val editorSession: MainEditorSession by lazy {
        ViewModelProvider(this)[MainEditorSession::class.java]
    }
    private val pageState: MainPageState by lazy {
        ViewModelProvider(this)[MainPageState::class.java]
    }
    private val catalogScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var assistantSettingsRequest by mutableIntStateOf(0)
    private var runSettingsSection by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        selectedDestination =
            savedInstanceState
                ?.getString(SELECTED_DESTINATION_KEY)
                ?.let { saved -> VeyarcDestination.entries.firstOrNull { it.name == saved } }
                ?: VeyarcDestination.HOME
        fullScreenDestination =
            savedInstanceState
                ?.getString(FULL_SCREEN_DESTINATION_KEY)
                ?.let { saved -> FullScreenDestination.entries.firstOrNull { it.name == saved } }
        if (savedInstanceState?.getString(FULL_SCREEN_DESTINATION_KEY) == "ACCESSIBILITY") selectedDestination = VeyarcDestination.SETTINGS
        recordingFolderId = savedInstanceState?.getString(RECORDING_FOLDER_ID_KEY)
        editorReturnToManager = savedInstanceState?.getBoolean(EDITOR_RETURN_TO_MANAGER_KEY) ?: false
        runSettingsSection = savedInstanceState?.getString(RUN_SETTINGS_SECTION_KEY)?.takeIf { runSession.bundle != null }
        // Register the process-owned recording actions before the user leaves Veyarc. This lets
        // the fixed service-owned control center start a recording from a third-party app instead
        // of requiring the recorder screen to have been opened first.
        recordingSession.controller
        workflowToRun = runSession.bundle
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (fullScreenDestination == FullScreenDestination.RECORDER) {
                        recordingSession.controller.stop()
                        fullScreenDestination = null
                    } else if (runSettingsSection != null) {
                        returnToRunReview()
                    } else if (workflowToRun != null) {
                        leaveRunScreen()
                    } else if (selectedDestination != VeyarcDestination.HOME) {
                        managerSelectionBar = null
                        selectedDestination = VeyarcDestination.HOME
                    } else {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            },
        )
        setContent {
            val recoveryState by runSession.recoveryState.collectAsState()
            val preferencesFlow = androidx.compose.runtime.remember {
                AppPreferences.observe(this@MainActivity)
            }
            val uiPreferences by preferencesFlow.collectAsState(initial = AppUiPreferences())
            val systemDark = isSystemInDarkTheme()
            VeyarcTheme(
                darkTheme = when (uiPreferences.theme) {
                    ThemePreference.SYSTEM -> systemDark
                    ThemePreference.LIGHT -> false
                    ThemePreference.DARK -> true
                },
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    BackHandler(enabled = runSettingsSection != null, onBack = ::returnToRunReview)
                    val bundle = workflowToRun.takeIf { runSettingsSection == null }
                    when (fullScreenDestination) {
                        FullScreenDestination.RECORDER -> CoordinateRecorderScreen(
                            controller = recordingSession.controller,
                            onBack = {
                                recordingFolderId = null
                                fullScreenDestination = null
                            },
                            onEditWorkflow = { bundle, journalFileName ->
                                editorReturnToManager = false
                                workflowToEdit = bundle
                                storedWorkflowToEdit = null
                                recordedJournalFileName = journalFileName
                                recordingFolderId = null
                                selectedDestination = VeyarcDestination.EDITOR
                                fullScreenDestination = null
                            },
                            destinationFolderId = recordingFolderId,
                        )

                        null ->
                            if (bundle != null) {
                                val controller = requireNotNull(runSession.controller) {
                                    "A workflow run screen requires an active run controller"
                                }
                                key(controller) {
                                    WorkflowRunScreen(
                                        bundle = bundle,
                                        controller = controller,
                                        canStart = recoveryState == RunRecoveryState.READY,
                                        startBlockMessage = recoveryState.message,
                                        onStart = ::startRunAndRemember,
                                        onBack = ::leaveRunScreen,
                                        onOpenConnectionSettings = { openRunSettings("connections") },
                                        onOpenLatchSettings = { openRunSettings("latch") },
                                    )
                                }
                            } else {
                                VeyarcAppShell(
                                    selectedDestination = if (runSettingsSection == null) selectedDestination else VeyarcDestination.SETTINGS,
                                    onDestinationSelected = {
                                        if (runSettingsSection == null || it != VeyarcDestination.SETTINGS) {
                                            if (runSettingsSection != null) leaveRunScreen()
                                            managerSelectionBar = null
                                            selectedDestination = it
                                        }
                                    },
                                    selectionBar = managerSelectionBar,
                                    showNavigation = runSettingsSection != null || !(selectedDestination == VeyarcDestination.EDITOR && editorSession.assistant.isOpen),
                                ) { destination ->
                                    when (destination) {
                                        VeyarcDestination.HOME -> HomeDashboardScreen(
                                            pageState = pageState.home,
                                            repository = workflowRepository,
                                            refreshGeneration = resumeGeneration,
                                            onRecord = {
                                                recordingSession.controller.dismissReview()
                                                recordingFolderId = null
                                                fullScreenDestination = FullScreenDestination.RECORDER
                                            },
                                            onPlayWorkflow = ::playWorkflow,
                                        )

                                        VeyarcDestination.WORKFLOWS -> WorkflowManagerScreen(
                                            pageState = pageState.workflows,
                                            repository = workflowRepository,
                                            refreshGeneration = resumeGeneration,
                                            onEditWorkflow = ::editWorkflow,
                                            onPlayWorkflow = ::playWorkflow,
                                            onSelectionBarChange = { if (selectedDestination == VeyarcDestination.WORKFLOWS) managerSelectionBar = it },
                                            onWorkflowDeleted = ::workflowDeleted,
                                        )

                                        VeyarcDestination.EDITOR -> AssistantPreviewHost(
                                            enabled = true,
                                            state = editorSession.assistant,
                                            context = editorSession.state.assistantContext(),
                                            onOpenSettings = {
                                                assistantSettingsRequest++
                                                selectedDestination = VeyarcDestination.SETTINGS
                                            },
                                        ) { onAskAi ->
                                            WorkflowEditorScreen(
                                                repository = workflowRepository,
                                                session = editorSession.state,
                                                onAskAi = onAskAi?.let { { editorSession.assistant.open(editorSession.state.assistantContext()) } },
                                                onRunWorkflow = ::openStoredRunScreen,
                                                onOpenWorkflowManager = {
                                                    selectedDestination = VeyarcDestination.WORKFLOWS
                                                },
                                                onWorkflowClosed = {
                                                    workflowToEdit = null
                                                    storedWorkflowToEdit = null
                                                    recordedJournalFileName = null
                                                    if (editorReturnToManager) selectedDestination = VeyarcDestination.WORKFLOWS
                                                    editorReturnToManager = false
                                                },
                                                initialBundle = workflowToEdit,
                                                initialStoredWorkflowId = storedWorkflowToEdit?.first,
                                                initialStoredRevision = storedWorkflowToEdit?.second,
                                                initialRecordingJournalFileName = recordedJournalFileName,
                                                onInitialBundleConsumed = {
                                                    workflowToEdit = null
                                                    storedWorkflowToEdit = null
                                                    recordedJournalFileName = null
                                                },
                                                newWorkflowRequestId = newWorkflowRequestId,
                                                newWorkflowFolderId = newWorkflowFolderId,
                                                onNewWorkflowRequestConsumed = {
                                                    newWorkflowRequestId = 0
                                                    newWorkflowFolderId = null
                                                },
                                            )
                                        }

                                        VeyarcDestination.RUNS -> RunManagerScreen(
                                            pageState = pageState.runs,
                                            repository = workflowRepository,
                                            refreshGeneration = resumeGeneration,
                                            onOpenWorkflowRevision = ::editRunWorkflowRevision,
                                            onSelectionBarChange = { if (selectedDestination == VeyarcDestination.RUNS) managerSelectionBar = it },
                                        )

                                        VeyarcDestination.SETTINGS -> SettingsScreen(
                                            storageState = pageState.settings,
                                            requestedSection = runSettingsSection,
                                            assistant = editorSession.assistant,
                                            assistantConnectionRequestId = assistantSettingsRequest,
                                            onAssistantConnectionRequestConsumed = { assistantSettingsRequest = 0 },
                                            repository = workflowRepository,
                                            refreshGeneration = resumeGeneration,
                                            themePreference = uiPreferences.theme,
                                            onThemePreferenceChange = { preference ->
                                                catalogScope.launch {
                                                    AppPreferences.setTheme(this@MainActivity, preference)
                                                }
                                            },
                                        )
                                    }
                                }
                            }
                    }
                }
            }
        }
        if (shouldHandleControllerRequest(intent?.action)) {
            handleControllerIntent(intent)
        }
    }

    override fun onResume() {
        super.onResume()
        resumeGeneration += 1
        runSession.controller?.refreshReview()
        refreshReplayWorkflowCatalog()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleControllerIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(SELECTED_DESTINATION_KEY, selectedDestination.name)
        fullScreenDestination?.let { outState.putString(FULL_SCREEN_DESTINATION_KEY, it.name) }
        recordingFolderId?.let { outState.putString(RECORDING_FOLDER_ID_KEY, it) }
        outState.putBoolean(EDITOR_RETURN_TO_MANAGER_KEY, editorReturnToManager)
        runSettingsSection?.let { outState.putString(RUN_SETTINGS_SECTION_KEY, it) }
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        catalogScope.cancel()
        super.onDestroy()
    }

    private fun openRunScreen(bundle: WorkflowBundle, autoStart: Boolean = false) {
        runSession.open(bundle)
        workflowToRun = bundle
        if (autoStart) startControllerReplayWhenReady()
    }

    private fun openStoredRunScreen(revision: WorkflowRevisionDocument, autoStart: Boolean = false) {
        val metadataOnlyBundle = WorkflowBundle(revision.document, emptyList(), revision.producerVersion)
        runSession.openStored(metadataOnlyBundle)
        workflowToRun = metadataOnlyBundle
        if (autoStart) startControllerReplayWhenReady()
    }

    private fun playWorkflow(workflow: WorkflowSummary) {
        catalogScope.launch {
            val revision = runCatching {
                withContext(Dispatchers.IO) {
                    workflowRepository.loadRevisionDocument(workflow.workflowId, workflow.latestRevision)
                }
            }.getOrNull() ?: return@launch
            openStoredRunScreen(revision, autoStart = true)
        }
    }

    private fun workflowDeleted(workflowId: String) {
        if (workflowToEdit?.document?.id == workflowId) workflowToEdit = null
        if (storedWorkflowToEdit?.first == workflowId) storedWorkflowToEdit = null
        if (editorSession.assistant.context.workflowId == workflowId) {
            editorSession.assistant.stop()
            editorSession.assistant.close()
        }
        if (editorSession.state.draft?.workflowId != workflowId) return
        editorSession.state.close()
        recordedJournalFileName = null
        editorReturnToManager = false
    }

    private fun editWorkflow(workflow: WorkflowSummary) {
        editorReturnToManager = true
        workflowToEdit = null
        storedWorkflowToEdit = workflow.workflowId to workflow.latestRevision
        recordedJournalFileName = null
        selectedDestination = VeyarcDestination.EDITOR
    }

    private fun editRunWorkflowRevision(run: StoredRunRecord) {
        editorReturnToManager = false
        workflowToEdit = null
        storedWorkflowToEdit = run.workflowId to run.revision
        recordedJournalFileName = null
        selectedDestination = VeyarcDestination.EDITOR
    }

    /** Starts a controller-requested replay only after recovery and the normal safety checks pass. */
    private fun startControllerReplayWhenReady() {
        catalogScope.launch {
            val recovery = runSession.recoveryState.first { it != RunRecoveryState.RECOVERING }
            if (recovery != RunRecoveryState.READY || workflowToRun == null) return@launch
            val controller = runSession.controller ?: return@launch
            controller.refreshNotificationState()
            val state = controller.state.value
            // A service-owned emergency notification is mandatory. If it is unavailable, leave
            // the normal review screen visible rather than starting an unprotected replay.
            if (state.review.isRunnable && state.notificationReady) startRunAndRemember()
        }
    }

    private fun startRunAndRemember() {
        val document = workflowToRun?.document ?: return
        ReplayWorkflowCatalog.markPlayed(
            applicationContext,
            ReplayControllerWorkflow(document.id, document.revision, document.name),
        )
        runSession.startRun()
    }

    private fun leaveRunScreen() {
        runSession.leave()
        workflowToRun = null
        runSettingsSection = null
    }

    private fun openRunSettings(section: String) {
        runSettingsSection = section
        managerSelectionBar = null
    }

    private fun returnToRunReview() {
        runSession.controller?.refreshReview()
        runSettingsSection = null
    }

    private fun refreshReplayWorkflowCatalog() {
        catalogScope.launch {
            val catalog = runCatching {
                withContext(Dispatchers.IO) {
                    val summaries = workflowRepository.listWorkflowSummaries()
                    val workflows = summaries.map { summary ->
                        ReplayControllerWorkflow(
                            workflowId = summary.workflowId,
                            revision = summary.latestRevision,
                            name = summary.name,
                            updatedAtMs = summary.updatedAtMs,
                            isFavorite = summary.isFavorite,
                        )
                    }
                    val names = summaries.associateBy(WorkflowSummary::workflowId)
                    val lastPlayed = workflowRepository.listRunSummaries(limit = 1).firstOrNull()?.let { run ->
                        ReplayControllerWorkflow(
                            workflowId = run.workflowId,
                            revision = run.revision,
                            name = names[run.workflowId]?.name ?: run.name ?: "Workflow",
                        )
                    }
                    workflows to lastPlayed
                }
            }.getOrElse { emptyList<ReplayControllerWorkflow>() to null }
            ReplayWorkflowCatalog.replace(applicationContext, catalog.first, catalog.second)
        }
    }

    private fun handleControllerIntent(intent: Intent?) {
        if (intent?.action == AssistantReplyService.OPEN_CHAT) {
            intent.action = null
            if (fullScreenDestination == null && workflowToRun == null) {
                selectedDestination = VeyarcDestination.EDITOR
                editorSession.assistant.open(editorSession.assistant.context)
            }
            return
        }
        if (intent?.action != ReplayFloatingController.ACTION_OPEN_WORKFLOW) return
        intent.action = null
        val workflowId = intent.getStringExtra(ReplayFloatingController.EXTRA_WORKFLOW_ID)
            ?.takeIf { it.isNotBlank() }
        val revision = intent.getIntExtra(ReplayFloatingController.EXTRA_WORKFLOW_REVISION, -1)
        val autoStart = intent.getBooleanExtra(ReplayFloatingController.EXTRA_AUTO_START, false)
        if (workflowId == null || revision <= 0) {
            // The library action is intentionally separate from Replay-latest. CLEAR_TOP/
            // SINGLE_TOP can deliver it to an existing run screen, so explicitly return to the
            // editor when the user asks for the library.
            if (fullScreenDestination == FullScreenDestination.RECORDER) {
                recordingSession.controller.stop()
            }
            fullScreenDestination = null
            leaveRunScreen()
            selectedDestination = VeyarcDestination.WORKFLOWS
            return
        }
        catalogScope.launch {
            val revisionDocument = runCatching {
                withContext(Dispatchers.IO) {
                    workflowRepository.loadRevisionDocument(workflowId, revision)
                }
            }.getOrNull() ?: return@launch
            openStoredRunScreen(revisionDocument, autoStart = autoStart)
        }
    }

    private companion object {
        const val SELECTED_DESTINATION_KEY = "selected_destination"
        const val FULL_SCREEN_DESTINATION_KEY = "full_screen_destination"
        const val RECORDING_FOLDER_ID_KEY = "recording_folder_id"
        const val EDITOR_RETURN_TO_MANAGER_KEY = "editor_return_to_manager"
        const val RUN_SETTINGS_SECTION_KEY = "run_settings_section"
    }
}

internal fun shouldHandleControllerRequest(action: String?): Boolean = action == ReplayFloatingController.ACTION_OPEN_WORKFLOW

private enum class FullScreenDestination {
    RECORDER,
}

enum class RunRecoveryState(val message: String?) {
    RECOVERING("Recovering unfinished runs before a new run can start…"),
    READY(null),
    FAILED("Previous-run recovery failed. Restart Veyarc before starting a new run."),
}

class MainRunSession(application: Application) : AndroidViewModel(application) {
    private val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _recoveryState = MutableStateFlow(RunRecoveryState.RECOVERING)

    val repository = WorkflowRepository(application)
    val recoveryState: StateFlow<RunRecoveryState> = _recoveryState.asStateFlow()
    var bundle: WorkflowBundle? = null
        private set
    var controller: WorkflowRunController? = null
        private set

    init {
        sessionScope.launch {
            try {
                repository.recoverInterruptedRuns()
                runCatching { repository.reconcileStorage() }
                    .onFailure { Log.w(TAG, "Workflow asset maintenance did not complete", it) }
                _recoveryState.value = RunRecoveryState.READY
            } catch (_: Exception) {
                Log.e(TAG, "Could not recover unfinished workflow runs")
                _recoveryState.value = RunRecoveryState.FAILED
            }
        }
    }

    fun open(bundle: WorkflowBundle) {
        controller?.close("a different workflow was opened")
        controller = WorkflowRunController(getApplication(), repository, bundle.document, bundle.assets)
        this.bundle = bundle
    }

    fun openStored(bundle: WorkflowBundle) {
        controller?.close("a different workflow was opened")
        controller = WorkflowRunController(getApplication(), repository, bundle.document)
        this.bundle = bundle
    }

    fun leave() {
        controller?.close("you left the run screen")
        controller = null
        bundle = null
    }

    fun startRun() {
        if (_recoveryState.value == RunRecoveryState.READY) {
            controller?.start()
        }
    }

    override fun onCleared() {
        controller?.close("the activity was finished")
        sessionScope.cancel()
        repository.close()
        super.onCleared()
    }

    private companion object {
        const val TAG = "Veyarc.RunSession"
    }
}

class MainRecordingSession(application: Application) : AndroidViewModel(application) {
    // The controller is process-owned so destroying/recreating MainActivity does not stop a
    // recording that is intentionally running in another app.
    val controller = CoordinateRecordingRuntime.controller(application)

    override fun onCleared() {
        CoordinateRecordingRuntime.close()
        super.onCleared()
    }
}

class MainEditorSession(application: Application) : AndroidViewModel(application) {
    private val owner = (application as VeyarcApplication).editor
    val state = owner.state
    val assistant = owner.assistant
}

/** Keeps the active chat and its draft binding alive independently of activity/navigation lifetime. */
internal class EditorSessionOwner(application: Application) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val state = WorkflowEditorSession()
    private val chatRepository = com.veyarc.data.storage.ChatRepository(application)
    private val assistantDraft = AssistantDraftBridge(state)
    private val assistantConnections = com.veyarc.data.storage.AssistantConnectionStore(application)
    private val assistantAttachments = com.veyarc.feature.assistant.AssistantAttachmentImporter(application)
    val assistant = AssistantPreviewState(
        chatRepository,
        scope,
        compileCandidate = ::assistantCompilationIssues,
        applyCandidate = assistantDraft::apply,
        contextSource = assistantDraft::capture,
        attachments = assistantAttachments,
        applyImageCandidate = { binding, base, candidate, additions, sources -> assistantDraft.applyImages(binding, base, candidate, additions, sources, assistantAttachments.store) },
        providerCall = { request ->
            val key = withContext(Dispatchers.IO) {
                runCatching { com.veyarc.data.storage.AssistantKeyStore(application, request.connection.id).readForRequest() }.getOrNull()
                    ?: throw com.veyarc.feature.assistant.DeepSeekException(com.veyarc.feature.assistant.DeepSeekFailure.KEY_REQUIRED)
            }
            com.veyarc.feature.assistant.AssistantClient(request.connection, request.modelId).await(request, key)
        },
        onRequestStarted = { token -> AssistantReplyService.start(application, token) },
        onConnectionsChanged = { value -> withContext(Dispatchers.IO) { assistantConnections.save(value) } },
        modelDiscovery = { profile ->
            val key = withContext(Dispatchers.IO) {
                runCatching { com.veyarc.data.storage.AssistantKeyStore(application, profile.id).readForRequest() }.getOrNull()
                    ?: throw com.veyarc.feature.assistant.DeepSeekException(com.veyarc.feature.assistant.DeepSeekFailure.KEY_REQUIRED)
            }
            com.veyarc.feature.assistant.AssistantClient(profile).discoverModels(key)
        },
    )

    init {
        scope.launch {
            val preferences = AppPreferences.observe(application).first()
            try {
                val connections = withContext(Dispatchers.IO) { assistantConnections.loadOrMigrate(preferences.assistantModel.apiId, preferences.assistantEffort.name) }
                assistant.restoreConnections(connections)
            } catch (_: Exception) {
                assistant.connectionStorageFailed()
            }
        }
    }
}

package com.veyarc.app

import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import com.veyarc.data.storage.FolderSummary
import com.veyarc.data.storage.RunDashboardSummary
import com.veyarc.data.storage.WorkflowSummary

/** Keep successful metadata loads across tab changes, without keeping inactive screens alive. */
internal class MainPageState : ViewModel() {
    val home = HomeDashboardState()
    val workflows = WorkflowManagerState()
    val runs = RunManagerState()
    val settings = SettingsStorageState()
}

class HomeDashboardState {
    internal val dashboard = mutableStateOf<RunDashboardSummary?>(null)
    internal val workflows = mutableStateOf<List<WorkflowSummary>>(emptyList())
    internal val quickPlayWorkflow = mutableStateOf<WorkflowSummary?>(null)
}

internal class WorkflowManagerState {
    val workflows = mutableStateOf<List<ManagedWorkflow>>(emptyList())
    val folders = mutableStateOf<List<FolderSummary>>(emptyList())
    val loaded = mutableStateOf(false)
}

internal class RunManagerState {
    val runs = mutableStateOf<List<ManagedRun>>(emptyList())
    val workflows = mutableStateOf<List<WorkflowSummary>>(emptyList())
    val folders = mutableStateOf<List<FolderSummary>>(emptyList())
    val loaded = mutableStateOf(false)
}

class SettingsStorageState {
    internal val workflows = mutableStateOf<List<WorkflowSummary>>(emptyList())
    internal val runCount = mutableStateOf(0L)
    internal val workflowFolderCount = mutableStateOf(0)
    internal val runFolderCount = mutableStateOf(0)
}

package com.veyarc.app

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.veyarc.automation.android.AccessibilityGestureDispatcher
import com.veyarc.automation.android.MediaProjectionCaptureRuntime
import com.veyarc.automation.android.ReplayFloatingController
import com.veyarc.automation.android.ShizukuTouchProbe
import com.veyarc.data.storage.WorkflowRepository
import com.veyarc.data.storage.WorkflowSummary
import com.veyarc.model.bundle.BundleLimits
import com.veyarc.model.bundle.VeyarcBundle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

@Composable
fun SettingsScreen(
    repository: WorkflowRepository,
    refreshGeneration: Int,
    themePreference: ThemePreference,
    onThemePreferenceChange: (ThemePreference) -> Unit,
    reducedDensity: Boolean,
    onReducedDensityChange: (Boolean) -> Unit,
    onOpenDiagnostics: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val shizukuProbe = remember(context) { ShizukuTouchProbe(context) }
    var shizuku by remember { mutableStateOf(shizukuProbe.snapshot()) }
    var accessibilityEnabled by remember { mutableStateOf(false) }
    var accessibilityConnected by remember { mutableStateOf(false) }
    var notificationsEnabled by remember { mutableStateOf(true) }
    var latchEnabled by remember { mutableStateOf(ReplayFloatingController.isEnabled(context)) }
    var workflows by remember { mutableStateOf(emptyList<WorkflowSummary>()) }
    var runCount by remember { mutableStateOf(0L) }
    var workflowFolderCount by remember { mutableStateOf(0) }
    var runFolderCount by remember { mutableStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }
    var confirmClearHistory by remember { mutableStateOf(false) }
    var chooseExportWorkflow by remember { mutableStateOf(false) }
    var pendingExportWorkflow by remember { mutableStateOf<WorkflowSummary?>(null) }

    fun refreshCapabilities() {
        accessibilityEnabled = AccessibilityGestureDispatcher.isEnabled(context)
        accessibilityConnected = AccessibilityGestureDispatcher.isConnected
        notificationsEnabled =
            context.getSystemService(NotificationManager::class.java)?.areNotificationsEnabled() == true &&
            (
                Build.VERSION.SDK_INT < 33 ||
                    context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                )
        latchEnabled = ReplayFloatingController.isEnabled(context)
        shizukuProbe.refresh()
    }

    fun refreshStorage() {
        scope.launch {
            val snapshot = withContext(Dispatchers.IO) {
                val storedWorkflows = repository.listWorkflows()
                StorageSnapshot(
                    workflows = storedWorkflows,
                    runCount = repository.loadRunDashboard(recentLimit = 1).totalRuns,
                    workflowFolderCount = repository.listWorkflowFolders().size,
                    runFolderCount = repository.listRunFolders().size,
                )
            }
            workflows = snapshot.workflows
            runCount = snapshot.runCount
            workflowFolderCount = snapshot.workflowFolderCount
            runFolderCount = snapshot.runFolderCount
        }
    }

    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        refreshCapabilities()
    }
    val importBundle = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val bytes = requireNotNull(context.contentResolver.openInputStream(uri)) { "Could not open the selected file" }
                        .use { it.readAtMost(BundleLimits().maxBundleBytes) }
                    repository.saveRevision(VeyarcBundle.import(bytes))
                }
            }.onSuccess {
                message = "Workflow imported."
                refreshStorage()
            }.onFailure { error -> message = "Import failed: ${error.message ?: error.javaClass.simpleName}" }
        }
    }
    val exportBundle = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val workflow = pendingExportWorkflow
        if (uri == null || workflow == null) {
            pendingExportWorkflow = null
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val bytes = VeyarcBundle.export(repository.loadRevision(workflow.workflowId, workflow.latestRevision))
                    requireNotNull(context.contentResolver.openOutputStream(uri)) { "Could not open the destination" }
                        .use { it.write(bytes) }
                }
            }.onSuccess { message = "${workflow.name} exported." }
                .onFailure { error -> message = "Export failed: ${error.message ?: error.javaClass.simpleName}" }
            pendingExportWorkflow = null
        }
    }

    DisposableEffect(shizukuProbe) {
        shizukuProbe.setCallback { shizuku = it }
        val stopLatchObservation = ReplayFloatingController.observeEnabled(context) { latchEnabled = it }
        onDispose {
            stopLatchObservation()
            shizukuProbe.close()
        }
    }
    LaunchedEffect(refreshGeneration) {
        refreshCapabilities()
        refreshStorage()
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 24.dp),
    ) {
        item {
            Column(Modifier.fillMaxWidth().widthIn(max = 760.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("SETTINGS", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                Text("Settings", style = MaterialTheme.typography.headlineMedium)
                Text("Connections, controls, appearance, and local data.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            SettingsCard("Connections and permissions") {
                StatusRow("Accessibility service", if (accessibilityEnabled) "Enabled" else "Disabled")
                StatusRow("Service connection", if (accessibilityConnected) "Ready" else "Not connected")
                OutlinedButton(onClick = { AccessibilityGestureDispatcher.openAccessibilitySettings(context) }) {
                    Text("Open Accessibility settings")
                }
                HorizontalDivider()
                StatusRow("Shizuku", if (shizuku.binderAvailable) "Binder available" else "Not running")
                StatusRow("Veyarc permission", if (shizuku.permissionGranted) "Granted" else "Not granted")
                shizuku.serverUid?.let { StatusRow("Server identity", "UID $it") }
                Text(
                    if (shizuku.binderAvailable) {
                        "Shizuku provides the shell identity used by global coordinate recording and raw replay."
                    } else {
                        "Start Shizuku again after a device restart, then return here and refresh."
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = shizukuProbe::refresh) { Text("Refresh") }
                    if (shizuku.binderAvailable && !shizuku.permissionGranted) {
                        Button(onClick = shizukuProbe::requestPermission) { Text("Request permission") }
                    }
                }
                HorizontalDivider()
                StatusRow("Notifications", if (notificationsEnabled) "Emergency controls ready" else "Permission required")
                if (!notificationsEnabled && Build.VERSION.SDK_INT >= 33) {
                    Button(onClick = { notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }) {
                        Text("Allow notifications")
                    }
                }
                OutlinedButton(onClick = {
                    runCatching {
                        context.startActivity(
                            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                        )
                    }.onFailure { message = "Android notification settings are unavailable on this device." }
                }) { Text("Open notification settings") }
                Text(
                    "For an independent emergency control, edit Android Quick Settings and add the Veyarc tile.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HorizontalDivider()
                StatusRow("Screen capture", if (MediaProjectionCaptureRuntime.isActive) "Active for the current run" else "Inactive")
                Text(
                    "Visual conditions ask for Android screen-capture consent when each run starts. Veyarc cannot enable or retain that consent here.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item {
            SettingsCard("Appearance") {
                ThemePreference.entries.forEach { option ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = themePreference == option, onClick = { onThemePreferenceChange(option) })
                        Text(option.name.lowercase().replaceFirstChar(Char::uppercase))
                    }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Compact workflow editor", style = MaterialTheme.typography.titleMedium)
                        Text("Reduces editor spacing without changing Android's font scale.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = reducedDensity, onCheckedChange = onReducedDensityChange)
                }
                Text("Veyarc follows Android font scaling and display size settings.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            SettingsCard("Edge latch and emergency controls") {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Edge latch", style = MaterialTheme.typography.titleMedium)
                        Text("Shows recording and replay controls on the left or right edge.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(
                        checked = latchEnabled,
                        onCheckedChange = { enabled -> ReplayFloatingController.setEnabled(context, enabled) },
                    )
                }
                OutlinedButton(onClick = { ReplayFloatingController.resetPosition(context) }) { Text("Reset latch position") }
                Text(
                    "The latch only consumes touches inside its own visible controls. Replay elsewhere remains touch pass-through. Notifications and the Quick Settings tile remain independent stop controls.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item {
            SettingsCard("Local data and privacy") {
                Text("$runCount runs • ${workflows.size} workflows • $workflowFolderCount workflow folders • $runFolderCount run folders")
                Text(
                    "Veyarc stores workflows, recording journals, run details, and image templates locally. Stored revisions and recording journals are encrypted. Core beta does not upload recordings or screenshots.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = { importBundle.launch(arrayOf("application/zip", "application/octet-stream")) }) {
                    Text("Import workflow")
                }
                OutlinedButton(
                    enabled = workflows.isNotEmpty(),
                    onClick = { chooseExportWorkflow = true },
                ) { Text("Export workflow") }
                TextButton(enabled = runCount > 0, onClick = { confirmClearHistory = true }) {
                    Text("Clear finished run history")
                }
            }
        }
        item {
            SettingsCard("Diagnostics and compatibility") {
                val packageInfo = remember {
                    context.packageManager.getPackageInfo(context.packageName, 0)
                }
                @Suppress("DEPRECATION")
                StatusRow("Veyarc", "${packageInfo.versionName} (${packageInfo.versionCode})")
                StatusRow("Android", "${Build.VERSION.RELEASE} • API ${Build.VERSION.SDK_INT}")
                StatusRow("Device", "${Build.MANUFACTURER} ${Build.MODEL}")
                Button(onClick = onOpenDiagnostics) { Text("Open accessibility and input diagnostics") }
                OutlinedButton(onClick = { openUrl(context, "https://github.com/DhanaKresnawijaya237/Veyarc/blob/main/docs/android-beta-guide.md") }) {
                    Text("Android beta guide")
                }
                OutlinedButton(onClick = { openUrl(context, "https://github.com/DhanaKresnawijaya237/Veyarc/blob/main/docs/android-device-compatibility.md") }) {
                    Text("Device compatibility notes")
                }
            }
        }
        message?.let { status -> item { Text(status, color = MaterialTheme.colorScheme.primary) } }
    }

    if (confirmClearHistory) {
        AlertDialog(
            onDismissRequest = { confirmClearHistory = false },
            title = { Text("Clear finished run history?") },
            text = { Text("This permanently deletes $runCount completed, failed, cancelled, or interrupted run records. An active run is never deleted.") },
            confirmButton = {
                Button(onClick = {
                    confirmClearHistory = false
                    scope.launch {
                        runCatching { withContext(Dispatchers.IO) { repository.clearRunHistory() } }
                            .onSuccess { deleted ->
                                message = "$deleted run records deleted."
                                refreshStorage()
                            }
                            .onFailure { error -> message = "Could not clear history: ${error.message ?: error.javaClass.simpleName}" }
                    }
                }) { Text("Clear history") }
            },
            dismissButton = { TextButton(onClick = { confirmClearHistory = false }) { Text("Cancel") } },
        )
    }
    if (chooseExportWorkflow) {
        AlertDialog(
            onDismissRequest = { chooseExportWorkflow = false },
            title = { Text("Export workflow") },
            text = {
                LazyColumn {
                    items(workflows, key = WorkflowSummary::workflowId) { workflow ->
                        TextButton(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                chooseExportWorkflow = false
                                pendingExportWorkflow = workflow
                                exportBundle.launch("${workflow.name.safeFileName()}.veyarc")
                            },
                        ) { Text(workflow.name, modifier = Modifier.fillMaxWidth()) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { chooseExportWorkflow = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SettingsCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth().widthIn(max = 760.dp)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            content()
        }
    }
}

@Composable
private fun StatusRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label)
        Text(value, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private data class StorageSnapshot(
    val workflows: List<WorkflowSummary>,
    val runCount: Long,
    val workflowFolderCount: Int,
    val runFolderCount: Int,
)

private fun String.safeFileName(): String = replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('_').ifBlank { "workflow" }

private fun java.io.InputStream.readAtMost(maxBytes: Long): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8 * 1024)
    while (true) {
        val read = read(buffer)
        if (read < 0) return output.toByteArray()
        check(output.size().toLong() + read <= maxBytes) { "Workflow bundle is larger than ${maxBytes / 1024 / 1024} MB" }
        output.write(buffer, 0, read)
    }
}

private fun openUrl(context: android.content.Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
}

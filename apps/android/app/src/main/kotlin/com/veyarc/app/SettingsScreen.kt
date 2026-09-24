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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
    var expandedSection by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmShizukuDownload by remember { mutableStateOf(false) }
    var chooseShizukuSource by remember { mutableStateOf(false) }

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
                val storedWorkflows = repository.listWorkflowSummaries()
                StorageSnapshot(
                    workflows = storedWorkflows,
                    runCount = repository.countRuns(),
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
                    val bytes = repository.exportRevision(workflow.workflowId, workflow.latestRevision)
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
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 20.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth().widthIn(max = 760.dp).heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Settings", style = MaterialTheme.typography.headlineSmall)
            }
        }
        item {
            val ready = accessibilityEnabled && accessibilityConnected && shizuku.binderAvailable && shizuku.permissionGranted && notificationsEnabled
            SettingsCard(
                title = "Connections and permissions",
                summary = if (ready) "Ready" else "Needs setup",
                summaryActive = ready,
                expanded = expandedSection == "connections",
                onToggle = { expandedSection = if (expandedSection == "connections") null else "connections" },
            ) {
                StatusRow(
                    "Accessibility",
                    if (accessibilityEnabled) "Enabled" else "Disabled",
                    accessibilityEnabled,
                    onClick = { AccessibilityGestureDispatcher.openAccessibilitySettings(context) },
                )
                StatusRow("Service", if (accessibilityConnected) "Ready" else "Not connected", accessibilityConnected)
                HorizontalDivider()
                StatusRow(
                    "Shizuku",
                    if (shizuku.binderAvailable) "Running" else "Not running",
                    shizuku.binderAvailable,
                    onClick = {
                        val launchIntent = context.packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)
                        if (launchIntent == null) {
                            confirmShizukuDownload = true
                        } else {
                            runCatching { context.startActivity(launchIntent) }
                                .onFailure { message = "Shizuku could not be opened." }
                        }
                    },
                )
                StatusRow("Permission", if (shizuku.permissionGranted) "Granted" else "Not granted", shizuku.permissionGranted)
                shizuku.serverUid?.let { StatusRow("Server identity", "UID $it") }
                if (shizuku.binderAvailable && !shizuku.permissionGranted) {
                    Button(onClick = shizukuProbe::requestPermission) { Text("Request permission") }
                }
                HorizontalDivider()
                StatusRow(
                    "Notifications",
                    if (notificationsEnabled) "Ready" else "Permission required",
                    notificationsEnabled,
                    onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                            )
                        }.onFailure { message = "Android notification settings are unavailable on this device." }
                    },
                )
                Text("Add the Veyarc Quick Settings tile as an emergency stop.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                HorizontalDivider()
                StatusRow("Screen capture", if (MediaProjectionCaptureRuntime.isActive) "Active" else "Inactive", MediaProjectionCaptureRuntime.isActive)
                Text("Android asks for screen-capture consent when a visual-condition run starts.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            SettingsCard(
                title = "Appearance",
                summary = themePreference.name.lowercase().replaceFirstChar(Char::uppercase),
                expanded = expandedSection == "appearance",
                onToggle = { expandedSection = if (expandedSection == "appearance") null else "appearance" },
            ) {
                Column {
                    ThemePreference.entries.forEach { option ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = themePreference == option, onClick = { onThemePreferenceChange(option) })
                            Text(option.name.lowercase().replaceFirstChar(Char::uppercase))
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Compact workflow editor", style = MaterialTheme.typography.titleMedium)
                    }
                    Switch(checked = reducedDensity, onCheckedChange = onReducedDensityChange)
                }
            }
        }
        item {
            SettingsCard(
                title = "Edge latch",
                summary = null,
                summaryActive = latchEnabled,
                expanded = expandedSection == "latch",
                onToggle = { expandedSection = if (expandedSection == "latch") null else "latch" },
                trailing = {
                    Switch(
                        checked = latchEnabled,
                        onCheckedChange = { enabled -> ReplayFloatingController.setEnabled(context, enabled) },
                    )
                },
            ) {
                OutlinedButton(onClick = { ReplayFloatingController.resetPosition(context) }) { Text("Reset latch position") }
                Text(
                    "The latch uses only its visible area. Notifications and the Quick Settings tile remain emergency controls.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item {
            SettingsCard(
                title = "Local data",
                summary = "${workflows.size} workflows • $runCount runs",
                expanded = expandedSection == "data",
                onToggle = { expandedSection = if (expandedSection == "data") null else "data" },
            ) {
                Text("$workflowFolderCount workflow folders • $runFolderCount run folders")
                Text(
                    "Workflows, journals, run details, and templates stay on this device. Revisions and journals are encrypted.",
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
            val packageInfo = remember { context.packageManager.getPackageInfo(context.packageName, 0) }
            SettingsCard(
                title = "Diagnostics",
                summary = "Veyarc ${packageInfo.versionName}",
                expanded = expandedSection == "diagnostics",
                onToggle = { expandedSection = if (expandedSection == "diagnostics") null else "diagnostics" },
            ) {
                @Suppress("DEPRECATION")
                StatusRow("Veyarc", "${packageInfo.versionName} (${packageInfo.versionCode})")
                StatusRow("Android", "${Build.VERSION.RELEASE} • API ${Build.VERSION.SDK_INT}")
                StatusRow("Device", "${Build.MANUFACTURER} ${Build.MODEL}")
                Button(onClick = onOpenDiagnostics) { Text("Open diagnostics") }
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
    if (confirmShizukuDownload) {
        AlertDialog(
            onDismissRequest = { confirmShizukuDownload = false },
            title = { Text("Install Shizuku?") },
            text = { Text("Shizuku is not installed. Choose a download source?") },
            confirmButton = {
                TextButton(onClick = {
                    confirmShizukuDownload = false
                    chooseShizukuSource = true
                }) { Text("Choose source") }
            },
            dismissButton = { TextButton(onClick = { confirmShizukuDownload = false }) { Text("Cancel") } },
        )
    }
    if (chooseShizukuSource) {
        AlertDialog(
            onDismissRequest = { chooseShizukuSource = false },
            title = { Text("Download Shizuku") },
            text = {
                Column {
                    SHIZUKU_DOWNLOAD_SOURCES.forEach { source ->
                        TextButton(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                chooseShizukuSource = false
                                openUrl(context, source.url)
                            },
                        ) { Text(source.label, modifier = Modifier.fillMaxWidth()) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { chooseShizukuSource = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SettingsCard(
    title: String,
    summary: String?,
    expanded: Boolean,
    onToggle: () -> Unit,
    summaryActive: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(Modifier.fillMaxWidth().widthIn(max = 760.dp)) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 64.dp)
                    .clickable(onClick = onToggle)
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                summary?.let {
                    Text(
                        it,
                        color = if (summaryActive) Color(0xFF2E7D32) else MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                trailing?.invoke()
            }
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(tween(140)) + fadeIn(tween(100)),
                exit = shrinkVertically(tween(120)) + fadeOut(tween(80)),
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    content = content,
                )
            }
        }
    }
}

@Composable
private fun StatusRow(
    label: String,
    value: String,
    active: Boolean? = null,
    onClick: (() -> Unit)? = null,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, modifier = if (onClick == null) Modifier else Modifier.clickable(onClick = onClick))
        Text(
            value,
            color = when (active) {
                true -> Color(0xFF2E7D32)
                false -> MaterialTheme.colorScheme.error
                null -> MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
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

private data class DownloadSource(val label: String, val url: String)

private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
private val SHIZUKU_DOWNLOAD_SOURCES = listOf(
    DownloadSource("Google Play", "https://play.google.com/store/apps/details?id=$SHIZUKU_PACKAGE"),
    DownloadSource("GitHub releases", "https://github.com/RikkaApps/Shizuku/releases"),
    DownloadSource("IzzyOnDroid", "https://apt.izzysoft.de/fdroid/index/apk/$SHIZUKU_PACKAGE"),
)

@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

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
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.Policy
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.SettingsBrightness
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.veyarc.automation.android.AccessibilityGestureDispatcher
import com.veyarc.automation.android.EdgeLatchPlayTarget
import com.veyarc.automation.android.ReplayFloatingController
import com.veyarc.automation.android.ShizukuTouchProbe
import com.veyarc.data.storage.WorkflowRepository
import com.veyarc.data.storage.WorkflowSummary
import com.veyarc.feature.assistant.AssistantMarkdown
import com.veyarc.feature.assistant.AssistantPanel
import com.veyarc.feature.assistant.SettingsChoiceTile
import com.veyarc.model.bundle.BundleLimits
import com.veyarc.model.bundle.VeyarcBundle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

@Composable
fun SettingsScreen(
    assistant: com.veyarc.feature.assistant.AssistantPreviewState? = null,
    assistantConnectionRequestId: Int = 0,
    onAssistantConnectionRequestConsumed: () -> Unit = {},
    repository: WorkflowRepository,
    refreshGeneration: Int,
    themePreference: ThemePreference,
    onThemePreferenceChange: (ThemePreference) -> Unit,
    modifier: Modifier = Modifier,
    storageState: SettingsStorageState = remember { SettingsStorageState() },
) {
    val context = LocalContext.current
    val assistantKeys = remember(context) { com.veyarc.data.storage.AssistantKeyStore(context.applicationContext) }
    val scope = rememberCoroutineScope()
    val shizukuProbe = remember(context) { ShizukuTouchProbe(context) }
    var shizuku by remember { mutableStateOf(shizukuProbe.snapshot()) }
    var accessibilityEnabled by remember { mutableStateOf(false) }
    var accessibilityConnected by remember { mutableStateOf(false) }
    var notificationsEnabled by remember { mutableStateOf(true) }
    var latchEnabled by remember { mutableStateOf(ReplayFloatingController.isEnabled(context)) }
    var latchPlayTarget by remember { mutableStateOf(ReplayFloatingController.playTarget(context)) }
    var workflows by storageState.workflows
    var runCount by storageState.runCount
    var workflowFolderCount by storageState.workflowFolderCount
    var runFolderCount by storageState.runFolderCount
    var storageOperation by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var confirmClearHistory by remember { mutableStateOf(false) }
    var chooseExportWorkflow by remember { mutableStateOf(false) }
    var pendingExportWorkflow by remember { mutableStateOf<WorkflowSummary?>(null) }
    var expandedSection by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(assistantConnectionRequestId) {
        if (assistantConnectionRequestId > 0) {
            expandedSection = "assistant"
            onAssistantConnectionRequestConsumed()
        }
    }
    var confirmShizukuDownload by remember { mutableStateOf(false) }
    var chooseShizukuSource by remember { mutableStateOf(false) }
    var openGuide by remember { mutableStateOf<Pair<String, String>?>(null) }
    var noticesVisible by remember { mutableStateOf(false) }

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
            runCatching {
                withContext(Dispatchers.IO) {
                    val storedWorkflows = repository.listWorkflowSummaries()
                    StorageSnapshot(
                        workflows = storedWorkflows,
                        runCount = repository.countRuns(),
                        workflowFolderCount = repository.listWorkflowFolders().size,
                        runFolderCount = repository.listRunFolders().size,
                    )
                }
            }.onSuccess { snapshot ->
                workflows = snapshot.workflows
                runCount = snapshot.runCount
                workflowFolderCount = snapshot.workflowFolderCount
                runFolderCount = snapshot.runFolderCount
            }.onFailure { error ->
                if (error is kotlinx.coroutines.CancellationException) throw error
                message = "Local data could not be loaded."
            }
        }
    }

    val importBundle = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            storageOperation = "Importing workflow…"
            runCatching {
                withContext(Dispatchers.IO) {
                    val bytes = requireNotNull(context.contentResolver.openInputStream(uri)) { "Could not open the selected file" }
                        .use { it.readAtMost(BundleLimits().maxBundleBytes) }
                    repository.saveRevision(VeyarcBundle.import(bytes))
                }
            }.onSuccess {
                message = "Workflow imported."
                refreshStorage()
            }.onFailure { error ->
                if (error is kotlinx.coroutines.CancellationException) throw error
                message = "Import failed: ${error.message ?: error.javaClass.simpleName}"
            }
            storageOperation = null
        }
    }
    val exportBundle = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val workflow = pendingExportWorkflow
        if (uri == null || workflow == null) {
            pendingExportWorkflow = null
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            storageOperation = "Exporting workflow…"
            runCatching {
                withContext(Dispatchers.IO) {
                    val bytes = repository.exportRevision(workflow.workflowId, workflow.latestRevision)
                    requireNotNull(context.contentResolver.openOutputStream(uri)) { "Could not open the destination" }
                        .use { it.write(bytes) }
                }
            }.onSuccess { message = "${workflow.name} exported." }
                .onFailure { error ->
                    if (error is kotlinx.coroutines.CancellationException) throw error
                    message = "Export failed: ${error.message ?: error.javaClass.simpleName}"
                }
            storageOperation = null
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

    CompositionLocalProvider(LocalRippleConfiguration provides null) {
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
            if (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) {
                item {
                    SettingsCard(
                        title = "AI connection",
                        summary = if (assistant?.providerEnabled == true) "Enabled for this session" else "Disabled",
                        summaryActive = assistant?.providerEnabled == true,
                        expanded = expandedSection == "assistant",
                        onToggle = { expandedSection = if (expandedSection == "assistant") null else "assistant" },
                    ) {
                        AssistantConnectionSettings(assistantKeys, assistant, onHelp = { openGuide = "AI chat help" to "ai-chat-guide.md" })
                    }
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
                }
            }
            item {
                SettingsCard(
                    title = "Appearance",
                    summary = themePreference.name.lowercase().replaceFirstChar(Char::uppercase),
                    expanded = expandedSection == "appearance",
                    onToggle = { expandedSection = if (expandedSection == "appearance") null else "appearance" },
                ) {
                    Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ThemePreference.entries.forEach { option ->
                            SettingsChoiceTile(
                                label = option.name.lowercase().replaceFirstChar(Char::uppercase),
                                icon = when (option) {
                                    ThemePreference.SYSTEM -> Icons.Outlined.SettingsBrightness
                                    ThemePreference.LIGHT -> Icons.Outlined.LightMode
                                    ThemePreference.DARK -> Icons.Outlined.DarkMode
                                },
                                selected = themePreference == option,
                                onClick = { onThemePreferenceChange(option) },
                                modifier = Modifier.weight(1f),
                            )
                        }
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
                        IconButton(onClick = { ReplayFloatingController.resetPosition(context) }) {
                            Icon(Icons.Outlined.RestartAlt, "Reset latch position")
                        }
                        Switch(
                            checked = latchEnabled,
                            onCheckedChange = { enabled -> ReplayFloatingController.setEnabled(context, enabled) },
                        )
                    },
                ) {
                    Text("Play button", style = MaterialTheme.typography.titleMedium)
                    Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        EdgeLatchPlayTarget.entries.forEach { option ->
                            SettingsChoiceTile(
                                label = when (option) {
                                    EdgeLatchPlayTarget.MOST_RECENTLY_PLAYED -> "Last played"
                                    EdgeLatchPlayTarget.LAST_SAVED -> "Last saved"
                                    EdgeLatchPlayTarget.LATEST_FAVORITED -> "Favorite"
                                },
                                icon = when (option) {
                                    EdgeLatchPlayTarget.MOST_RECENTLY_PLAYED -> Icons.Outlined.History
                                    EdgeLatchPlayTarget.LAST_SAVED -> Icons.Outlined.Save
                                    EdgeLatchPlayTarget.LATEST_FAVORITED -> Icons.Outlined.StarOutline
                                },
                                selected = latchPlayTarget == option,
                                onClick = {
                                    latchPlayTarget = option
                                    ReplayFloatingController.setPlayTarget(context, option)
                                },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
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
                        "Stored on this device. Revisions and journals are encrypted.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    storageOperation?.let { operation ->
                        androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(operation, style = MaterialTheme.typography.bodySmall)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(modifier = Modifier.weight(1f), enabled = storageOperation == null, onClick = { importBundle.launch(arrayOf("application/zip", "application/octet-stream")) }) {
                            Text("Import workflow", textAlign = TextAlign.Center)
                        }
                        OutlinedButton(
                            modifier = Modifier.weight(1f),
                            enabled = workflows.isNotEmpty() && storageOperation == null,
                            onClick = { chooseExportWorkflow = true },
                        ) { Text("Export workflow", textAlign = TextAlign.Center) }
                    }
                    TextButton(modifier = Modifier.align(Alignment.CenterHorizontally), enabled = runCount > 0 && storageOperation == null, onClick = { confirmClearHistory = true }) {
                        Text("Clear finished run history")
                    }
                }
            }
            item {
                val packageInfo = remember { context.packageManager.getPackageInfo(context.packageName, 0) }
                SettingsCard(
                    title = "About",
                    summary = if (expandedSection == "about") null else packageInfo.versionName,
                    expanded = expandedSection == "about",
                    onToggle = { expandedSection = if (expandedSection == "about") null else "about" },
                ) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest) {
                            Image(painterResource(R.drawable.veyarc_logo), null, Modifier.size(60.dp).padding(10.dp))
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("Veyarc", style = MaterialTheme.typography.titleLarge)
                            @Suppress("DEPRECATION")
                            Text("${packageInfo.versionName} · Build ${packageInfo.versionCode}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    StatusRow("Android", "${Build.VERSION.RELEASE} • API ${Build.VERSION.SDK_INT}")
                    StatusRow("Device", "${Build.MANUFACTURER} ${Build.MODEL}")
                    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                        Column {
                            AboutLink("Android guide", Icons.Outlined.HelpOutline) { openGuide = "Android guide" to "android-beta-guide.md" }
                            AboutLink("Device compatibility notes", Icons.Outlined.Info) { openGuide = "Device compatibility notes" to "android-device-compatibility.md" }
                            AboutLink("Third-party notices", Icons.Outlined.Policy) { noticesVisible = true }
                        }
                    }
                }
            }
            message?.let { status -> item { Text(status, color = MaterialTheme.colorScheme.primary) } }
        }

        if (noticesVisible) {
            AlertDialog(
                onDismissRequest = { noticesVisible = false },
                title = { Text("CommonMark · BSD 2-Clause") },
                text = { Text(COMMONMARK_LICENSE, Modifier.verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodySmall) },
                confirmButton = { TextButton(onClick = { noticesVisible = false }) { Text("Done") } },
            )
        }
        if (confirmClearHistory) {
            AlertDialog(
                onDismissRequest = { confirmClearHistory = false },
                title = { Text("Clear finished run history?") },
                text = { Text("Permanently deletes $runCount finished run records. Keeps active runs.") },
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
        openGuide?.let { (title, assetName) ->
            val guide by produceState<String?>(initialValue = if (assetName == "ai-chat-guide.md") AI_CHAT_HELP else null, key1 = assetName) {
                if (value == null) {
                    value = withContext(Dispatchers.IO) {
                        runCatching { context.assets.open(assetName).bufferedReader().use { it.readText() } }
                            .getOrElse { "This guide could not be loaded." }
                    }
                }
            }
            val guideLines = remember(guide) { guide?.lines()?.filter(String::isNotBlank).orEmpty() }
            val guideList = rememberLazyListState()
            val guideContent: @Composable () -> Unit = {
                LazyColumn(state = guideList, overscrollEffect = null, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (guide == null) item { androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth()) }
                    items(guideLines) { line ->
                        when {
                            line.startsWith("# ") -> Unit

                            assetName == "android-beta-guide.md" -> AssistantMarkdown(line)

                            line.startsWith("## ") -> Text(line.removePrefix("## "), style = MaterialTheme.typography.titleMedium)

                            line.startsWith("- ") -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("•", style = MaterialTheme.typography.bodyMedium)
                                Text(line.removePrefix("- "), style = MaterialTheme.typography.bodyMedium)
                            }

                            else -> Text(line, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
            if (assetName == "ai-chat-guide.md") {
                AssistantPanel(title, onDismiss = { openGuide = null }, scrollBody = false, bodyState = guideList, content = guideContent)
            } else {
                Dialog(onDismissRequest = { openGuide = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
                    Card(Modifier.fillMaxWidth(0.94f).fillMaxHeight(0.9f)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                                TextButton(onClick = { openGuide = null }) { Text("Close") }
                            }
                            guideContent()
                        }
                    }
                }
            }
        }
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
            com.veyarc.feature.editor.ExpansionScrollAnchor(expanded)
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
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = if (onClick == null) Modifier else Modifier.clickable(role = Role.Button, onClick = onClick), color = if (onClick == null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary)
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

@Composable
private fun AboutLink(label: String, icon: ImageVector, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Icon(Icons.Outlined.ChevronRight, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
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

private val AI_CHAT_HELP = """
    ## Get started
    - Add a DeepSeek key and enable AI in Settings.
    - In the Editor, tap Ask AI or Create with AI.

    ## Review changes
    - Expand a changes card to compare Before and After.
    - Apply updates your draft. Undo is in the editor.
    - Save and Run are separate. Check AI changes first.

    ## Reply controls
    - Copy copies text. Branch starts a new chat.
    - Resend is a paid retry. Stop cancels, but may still cost.

    ## Need help?
    - Draft changed? Send a new message.
    - AI off after restart? Enable it in Settings.
    - Key or balance error? Check your DeepSeek account.
    - See Privacy and costs for shared data and charges.
""".trimIndent()

private val COMMONMARK_LICENSE = """
    Copyright (c) 2015, Atlassian Pty Ltd
    All rights reserved.

    Redistribution and use in source and binary forms, with or without
    modification, are permitted provided that the following conditions are met:

    * Redistributions of source code must retain the above copyright notice, this
      list of conditions and the following disclaimer.

    * Redistributions in binary form must reproduce the above copyright notice,
      this list of conditions and the following disclaimer in the documentation
      and/or other materials provided with the distribution.

    THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
    AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
    IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
    DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
    FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
    DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
    SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
    CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
    OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
    OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
""".trimIndent()

private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
private val SHIZUKU_DOWNLOAD_SOURCES = listOf(
    DownloadSource("Google Play", "https://play.google.com/store/apps/details?id=$SHIZUKU_PACKAGE"),
    DownloadSource("GitHub releases", "https://github.com/RikkaApps/Shizuku/releases"),
    DownloadSource("IzzyOnDroid", "https://apt.izzysoft.de/fdroid/index/apk/$SHIZUKU_PACKAGE"),
)

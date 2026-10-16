package com.veyarc.app

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.NetworkCheck
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.veyarc.data.storage.AssistantConnection
import com.veyarc.data.storage.AssistantConnections
import com.veyarc.data.storage.AssistantKeyStore
import com.veyarc.feature.assistant.AssistantPanel
import com.veyarc.feature.assistant.DeepSeekEffort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun AssistantConnectionSettings(
    store: AssistantKeyStore,
    assistant: com.veyarc.feature.assistant.AssistantPreviewState? = null,
    layout: @Composable (@Composable () -> Unit, @Composable () -> Unit) -> Unit = { toggle, connections ->
        Column {
            toggle()
            connections()
        }
    },
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current.applicationContext
    val profiles = assistant?.connections ?: AssistantConnections(listOf(AssistantConnection.deepSeek()), AssistantConnection.DEEPSEEK_ID)
    var hasKeys by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    var busy by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var keyProfile by remember { mutableStateOf<AssistantConnection?>(null) }
    var removingKey by remember { mutableStateOf<AssistantConnection?>(null) }
    var removingProfile by remember { mutableStateOf<AssistantConnection?>(null) }
    var enabling by rememberSaveable { mutableStateOf(false) }
    var testing by rememberSaveable { mutableStateOf(false) }
    var modelProfile by remember { mutableStateOf<AssistantConnection?>(null) }
    var editProfile by remember { mutableStateOf<AssistantConnection?>(null) }
    var adding by remember { mutableStateOf(false) }
    var addingModel by remember { mutableStateOf(false) }
    var keyRevision by remember { mutableStateOf(0) }
    var expandedProviders by rememberSaveable { mutableStateOf(emptyList<String>()) }
    fun keyStore(profile: AssistantConnection) = if (profile.id == AssistantConnection.DEEPSEEK_ID) store else AssistantKeyStore(context, profile.id)
    LaunchedEffect(profiles.connections.map { it.id }) {
        val revision = keyRevision
        busy = true
        var unreadable = false
        val readKeys = withContext(Dispatchers.IO) {
            profiles.connections.associate { profile ->
                profile.id to runCatching { keyStore(profile).hasKey() }.getOrElse {
                    unreadable = true
                    false
                }
            }
        }
        if (revision == keyRevision) {
            hasKeys = readKeys
            if (unreadable) error = "A stored key could not be read. Replace or remove it."
            busy = false
        }
    }
    fun save(value: AssistantConnections, after: () -> Unit = {}) {
        if (busy || assistant == null) return
        busy = true
        scope.launch {
            try {
                if (assistant.saveConnections(value)) after()
            } finally {
                busy = false
            }
        }
    }
    fun replace(profile: AssistantConnection, after: () -> Unit = {}) = save(profiles.copy(connections = profiles.connections.map { if (it.id == profile.id) profile else it }), after)
    val currentDefault = profiles.connections.first { it.id == profiles.defaultConnectionId }
    val enabled = assistant?.providerEnabled == true
    val canEnable = !busy && hasKeys[currentDefault.id] == true && assistant?.usesProvider == true && assistant.connectionsReady
    layout({
        SettingsEnableSwitch("Enable AI", checked = enabled, enabled = enabled || canEnable, modifier = Modifier.testTag("assistant-enable"), onCheckedChange = {
            if (it) enabling = true else assistant?.disableProvider()
        })
    }, {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val canTest = enabled && (assistant?.testingConnection == true || (!busy && hasKeys.values.any { it } && assistant?.isResponding == false && assistant.connectionsReady))
            Row(
                Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(12.dp))
                    .clickable(enabled = canTest, role = Role.Button) { if (assistant?.testingConnection == true) assistant.stop() else testing = true }
                    .semantics { contentDescription = "Test all connections" }.testTag("assistant-test-connections")
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Test API Connections", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = if (canTest) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f))
                if (assistant?.testingConnection == true) {
                    CircularProgressIndicator(Modifier.size(22.dp).semantics { contentDescription = "Stop connection test" }, strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Outlined.NetworkCheck, null, tint = if (canTest) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f))
                }
            }
            assistant?.connectionTestStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            profiles.connections.forEach { profile ->
                val expanded = profile.id in expandedProviders
                Surface(Modifier.fillMaxWidth().padding(bottom = 12.dp).testTag("connection-group-${profile.id}"), shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            Modifier.fillMaxWidth().semantics {
                                stateDescription = if (expanded) "Expanded" else "Collapsed"
                                contentDescription = "${if (expanded) "Collapse" else "Expand"} ${profile.name}"
                            }.clickable(role = Role.Button) { expandedProviders = if (expanded) expandedProviders - profile.id else expandedProviders + profile.id }
                                .padding(vertical = 8.dp).testTag(if (profile.id == AssistantConnection.DEEPSEEK_ID) "deepseek-provider-section" else "provider-${profile.id}"),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            ProviderLogo(profile.catalogProviderId, Modifier.size(48.dp))
                            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                Text(profile.name, style = MaterialTheme.typography.titleMedium)
                                if (profiles.defaultConnectionId == profile.id) Text("Default", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            }
                            if (assistant != null) {
                                IconButton(onClick = { save(profiles.copy(defaultConnectionId = profile.id)) }, enabled = !busy && assistant.connectionsReady) {
                                    Icon(Icons.Outlined.Star, if (profiles.defaultConnectionId == profile.id) "Default for new chats" else "Use ${profile.name} for new chats", tint = if (profiles.defaultConnectionId == profile.id) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                IconButton(onClick = { editProfile = profile }, enabled = !busy && assistant.connectionsReady) { Icon(Icons.Outlined.Edit, "Edit ${profile.name}", tint = MaterialTheme.colorScheme.onSurface.copy(alpha = if (assistant.connectionsReady) 1f else 0.38f)) }
                                IconButton(onClick = { removingProfile = profile }, enabled = !busy && profiles.connections.size > 1 && assistant.connectionsReady) { Icon(Icons.Outlined.DeleteOutline, "Remove ${profile.name} connection", tint = MaterialTheme.colorScheme.onSurface.copy(alpha = if (profiles.connections.size > 1 && assistant.connectionsReady) 1f else 0.38f)) }
                            }
                        }
                        AnimatedVisibility(expanded, enter = expandVertically(tween(140)) + fadeIn(tween(100)), exit = shrinkVertically(tween(120)) + fadeOut(tween(80))) {
                            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                HorizontalDivider()
                                if (assistant != null) {
                                    Surface(
                                        Modifier.fillMaxWidth().clickable(enabled = !busy && assistant.connectionsReady) {
                                            assistant.stopModelDiscovery()
                                            modelProfile = profile
                                        }.testTag("manage-models-${profile.id}"),
                                        shape = RoundedCornerShape(16.dp),
                                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                                    ) {
                                        Row(Modifier.padding(horizontal = 14.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                                            Column(Modifier.weight(1f)) {
                                                Text("Models", style = MaterialTheme.typography.bodyMedium)
                                                Text("${profile.models.size} added", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                            Icon(Icons.Outlined.Tune, "Manage ${profile.name} models", tint = MaterialTheme.colorScheme.primary)
                                        }
                                    }
                                }
                                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text("Default model", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                    Text(profile.defaultModel.name, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                if (profile.defaultModel.efforts.size > 1) {
                                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Text("Default effort", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                        Text(DeepSeekEffort.entries.firstOrNull { it.name == profile.defaultEffort }?.label ?: "Off", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Outlined.Key, null)
                                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                        Text("API key", style = MaterialTheme.typography.bodyMedium)
                                        Text(if (hasKeys[profile.id] == true) "Key saved" else "No API key", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    IconButton(onClick = { keyProfile = profile }, enabled = !busy) { Icon(if (hasKeys[profile.id] == true) Icons.Outlined.Edit else Icons.Outlined.Add, if (hasKeys[profile.id] == true) "Replace API key" else "Add API key", tint = MaterialTheme.colorScheme.onSurface) }
                                    IconButton(onClick = { removingKey = profile }, enabled = !busy) { Icon(Icons.Outlined.DeleteOutline, "Remove API key", tint = MaterialTheme.colorScheme.onSurface) }
                                }
                            }
                        }
                        com.veyarc.feature.editor.ExpansionScrollAnchor(expanded)
                        assistant?.connectionTestResults?.get(profile.id)?.let { result ->
                            Text(result, style = MaterialTheme.typography.bodySmall, color = if (result.startsWith("Not connected")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            if (assistant != null) {
                HorizontalDivider(Modifier.padding(vertical = 12.dp))
                OutlinedButton(onClick = { adding = true }, enabled = !busy && assistant.connectionsReady, modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp).testTag("assistant-add-connection")) {
                    Icon(Icons.Outlined.Add, null, Modifier.size(20.dp))
                    Text("Add connection", Modifier.padding(start = 8.dp))
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            assistant?.connectionError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    })
    modelProfile?.let { selected ->
        val profile = profiles.connections.first { it.id == selected.id }
        ConnectionModels(
            profile, busy, assistant?.discoveringModels == true, assistant?.discoveredModels.orEmpty(), assistant?.modelDiscoveryStatus, assistant?.connectionError,
            onFind = if (profile.canDiscoverModels && hasKeys[profile.id] == true && assistant?.supportsModelDiscovery == true) ({ assistant.discoverModels(profile.id) }) else null,
            onCustom = {
                assistant?.stopModelDiscovery()
                modelProfile = null
                addingModel = true
                editProfile = profile
            }, onUpdate = { updated, after -> replace(updated, after) }, onDismiss = {
                assistant?.stopModelDiscovery()
                modelProfile = null
            },
        )
    }
    if (adding || editProfile != null) {
        ConnectionEditor(editProfile, busy, newModel = addingModel, onManageModels = { profile ->
            editProfile = null
            assistant?.stopModelDiscovery()
            modelProfile = profile
        }, onDismiss = {
            if (addingModel) modelProfile = editProfile
            addingModel = false
            adding = false
            editProfile = null
        }) { profile ->
            val list = if (editProfile == null) profiles.connections + profile else profiles.connections.map { if (it.id == profile.id) profile else it }
            save(profiles.copy(connections = list)) {
                val newConnection = adding
                if (addingModel) modelProfile = profile
                addingModel = false
                adding = false
                editProfile = null
                if (newConnection) keyProfile = profile
            }
        }
    }
    if (enabling || testing) {
        val isTest = testing
        AlertDialog(onDismissRequest = {
            enabling = false
            testing = false
        }, title = { Text(if (isTest) "Test all connections?" else "Enable AI?") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (isTest) "• Sends a small test through each saved key. No chat or workflow data." else "• Sends chat and attachments to the connection you choose.")
                Text("• Your API account pays. Stop may still cost.")
                Text(if (isTest) "• Tests the connection, not workflow quality." else "• Approve edits to save a revision. AI turns off on restart.")
            }
        }, confirmButton = {
            TextButton(onClick = {
                enabling = false
                testing = false
                if (isTest) assistant?.testConnection() else assistant?.enableProvider()
            }) { Text(if (isTest) "Send test request" else "Enable for this session") }
        }, dismissButton = {
            TextButton(onClick = {
                enabling = false
                testing = false
            }) { Text("Cancel") }
        })
    }
    keyProfile?.let { profile ->
        AssistantKeyDialog(title = "${profile.name} API key", onDismiss = { keyProfile = null }, onSave = { value ->
            keyProfile = null
            keyRevision++
            assistant?.disableProvider()
            busy = true
            scope.launch {
                try {
                    withContext(Dispatchers.IO) { keyStore(profile).save(value) }
                    hasKeys = hasKeys + (profile.id to true)
                    error = null
                } catch (_: Exception) {
                    error = "Could not save the key. Check storage and try again."
                } finally {
                    busy = false
                }
            }
        })
    }
    removingKey?.let { profile ->
        AlertDialog(onDismissRequest = { removingKey = null }, title = { Text("Remove API key?") }, text = { Text("Chats stay. Revoke the key with your provider to stop all use.") }, confirmButton = {
            TextButton(onClick = {
                removingKey = null
                keyRevision++
                assistant?.disableProvider()
                busy = true
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) { keyStore(profile).remove() }
                        hasKeys = hasKeys + (profile.id to false)
                        error = null
                    } catch (_: Exception) {
                        error = "Could not remove the key. Try again."
                    } finally {
                        busy = false
                    }
                }
            }) { Text("Remove key") }
        }, dismissButton = { TextButton(onClick = { removingKey = null }) { Text("Cancel") } })
    }
    removingProfile?.let { profile ->
        AlertDialog(onDismissRequest = { removingProfile = null }, title = { Text("Remove ${profile.name}?") }, text = { Text("Removes this connection and its local key. Chats stay.") }, confirmButton = {
            TextButton(onClick = {
                val remaining = profiles.connections.filterNot { it.id == profile.id }
                save(profiles.copy(connections = remaining, defaultConnectionId = if (profiles.defaultConnectionId == profile.id) remaining.first().id else profiles.defaultConnectionId)) {
                    removingProfile = null
                    scope.launch { runCatching { withContext(Dispatchers.IO) { keyStore(profile).remove() } }.onFailure { error = "Connection removed, but its local key could not be deleted." } }
                }
            }) { Text("Remove connection") }
        }, dismissButton = { TextButton(onClick = { removingProfile = null }) { Text("Cancel") } })
    }
}

@Composable
internal fun SettingsEnableSwitch(label: String, checked: Boolean, enabled: Boolean = true, modifier: Modifier = Modifier, onCheckedChange: (Boolean) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val offset by animateDpAsState(if (checked) 40.dp else 0.dp, tween(140), label = "Settings switch thumb")
    val track by animateColorAsState(if (checked) colors.primary.copy(alpha = 0.2f) else colors.surfaceContainerHighest, tween(140), label = "Settings switch track")
    Box(
        modifier.width(84.dp).height(44.dp).alpha(if (enabled) 1f else 0.45f).clip(RoundedCornerShape(22.dp)).background(track)
            .toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
            .semantics { contentDescription = label },
    ) {
        Text(if (checked) "On" else "Off", Modifier.width(40.dp).align(if (checked) Alignment.CenterStart else Alignment.CenterEnd), style = MaterialTheme.typography.labelSmall, color = if (checked) colors.primary else colors.onSurfaceVariant, textAlign = TextAlign.Center, maxLines = 1)
        Box(Modifier.padding(4.dp).offset(x = offset).size(36.dp).background(if (checked) colors.primary else colors.onSurfaceVariant, CircleShape), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.PowerSettingsNew, null, Modifier.size(18.dp), tint = if (checked) colors.onPrimary else colors.surface)
        }
    }
}

@Composable
internal fun AssistantKeyDialog(title: String, description: String = "Encrypted on this device.", saveLabel: String = "Save key", onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var key by remember { mutableStateOf("") }
    var tooLong by remember { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val context = LocalContext.current
    DisposableEffect(lifecycle, context) {
        val window = context.activity()?.window
        val wasSecure = window?.attributes?.flags?.and(WindowManager.LayoutParams.FLAG_SECURE) != 0
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                key = ""
                onDismiss()
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            key = ""
            lifecycle.removeObserver(observer)
            if (!wasSecure) window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(description)
                OutlinedTextField(
                    value = key,
                    onValueChange = {
                        if (it.length <= AssistantKeyStore.MAX_KEY_LENGTH) {
                            key = it
                            tooLong = false
                        } else {
                            tooLong = true
                        }
                    },
                    modifier = Modifier.fillMaxWidth().testTag("assistant-key-entry"),
                    label = { Text("API key") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false, imeAction = ImeAction.Done),
                )
                if (tooLong || (key.isNotEmpty() && !AssistantKeyStore.isValidEntry(key))) Text("Use 1 to 4096 visible ASCII characters without spaces.", color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(enabled = !tooLong && AssistantKeyStore.isValidEntry(key), onClick = {
                val value = key
                key = ""
                onSave(value)
            }) { Text(saveLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

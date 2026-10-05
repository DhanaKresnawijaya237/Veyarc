package com.veyarc.app

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.NetworkCheck
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.veyarc.data.storage.AssistantConnection
import com.veyarc.data.storage.AssistantConnections
import com.veyarc.data.storage.AssistantKeyStore
import com.veyarc.data.storage.AssistantModelConfig
import com.veyarc.data.storage.AssistantProtocol
import com.veyarc.feature.assistant.AssistantPanel
import com.veyarc.feature.assistant.DeepSeekEffort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun AssistantConnectionSettings(store: AssistantKeyStore, assistant: com.veyarc.feature.assistant.AssistantPreviewState? = null, onHelp: (() -> Unit)? = null) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current.applicationContext
    val uri = LocalUriHandler.current
    val profiles = assistant?.connections ?: AssistantConnections(listOf(AssistantConnection.deepSeek()), AssistantConnection.DEEPSEEK_ID)
    var hasKeys by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    var busy by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var keyProfile by remember { mutableStateOf<AssistantConnection?>(null) }
    var removingKey by remember { mutableStateOf<AssistantConnection?>(null) }
    var removingProfile by remember { mutableStateOf<AssistantConnection?>(null) }
    var enabling by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var privacyVisible by remember { mutableStateOf(false) }
    var modelProfile by remember { mutableStateOf<AssistantConnection?>(null) }
    var effortProfile by remember { mutableStateOf<AssistantConnection?>(null) }
    var editProfile by remember { mutableStateOf<AssistantConnection?>(null) }
    var adding by remember { mutableStateOf(false) }
    var collapsed by rememberSaveable { mutableStateOf(emptyList<String>()) }
    fun keyStore(profile: AssistantConnection) = if (profile.id == AssistantConnection.DEEPSEEK_ID) store else AssistantKeyStore(context, profile.id)
    LaunchedEffect(profiles.connections.map { it.id }) {
        busy = true
        hasKeys = withContext(Dispatchers.IO) {
            profiles.connections.associate { profile -> profile.id to runCatching { keyStore(profile).hasKey() }.getOrDefault(false) }
        }
        busy = false
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
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        profiles.connections.forEach { profile ->
            val expanded = profile.id !in collapsed
            Row(
                Modifier.fillMaxWidth().semantics {
                    stateDescription = if (expanded) "Expanded" else "Collapsed"
                    contentDescription = "${if (expanded) "Collapse" else "Expand"} ${profile.name}"
                }.clickable(role = Role.Button) { collapsed = if (expanded) collapsed + profile.id else collapsed - profile.id }
                    .padding(vertical = 8.dp).testTag(if (profile.id == AssistantConnection.DEEPSEEK_ID) "deepseek-provider-section" else "provider-${profile.id}"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                    if (profile.protocol == AssistantProtocol.DEEPSEEK) {
                        Icon(painterResource(R.drawable.ic_deepseek), null, Modifier.padding(12.dp).size(32.dp).testTag("deepseek-logo"), tint = Color.Unspecified)
                    } else {
                        androidx.compose.foundation.layout.Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) { Text(profile.name.take(1).uppercase(), style = MaterialTheme.typography.titleLarge) }
                    }
                }
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(profile.name, style = MaterialTheme.typography.titleMedium)
                    if (profiles.defaultConnectionId == profile.id) Text("Default", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
                if (assistant != null) {
                    IconButton(onClick = { editProfile = profile }, enabled = !busy && assistant.connectionsReady) { Icon(Icons.Outlined.Edit, "Edit ${profile.name}") }
                    IconButton(onClick = { removingProfile = profile }, enabled = !busy && profiles.connections.size > 1 && assistant.connectionsReady) { Icon(Icons.Outlined.DeleteOutline, "Remove ${profile.name} connection") }
                }
            }
            AnimatedVisibility(expanded, enter = expandVertically(tween(140)) + fadeIn(tween(100)), exit = shrinkVertically(tween(120)) + fadeOut(tween(80))) {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    HorizontalDivider()
                    TextButton(onClick = { modelProfile = profile }, modifier = Modifier.fillMaxWidth(), enabled = assistant?.usesProvider == true && !busy) {
                        Text("Default model", Modifier.weight(1f))
                        Text(profile.defaultModel.name, Modifier.padding(end = 8.dp))
                    }
                    if (profile.defaultModel.efforts.size > 1) {
                        TextButton(onClick = { effortProfile = profile }, modifier = Modifier.fillMaxWidth(), enabled = assistant?.usesProvider == true && !busy) {
                            Text("Default effort", Modifier.weight(1f))
                            Text(DeepSeekEffort.entries.firstOrNull { it.name == profile.defaultEffort }?.label ?: "Off", Modifier.padding(end = 8.dp))
                        }
                    }
                    if (profiles.defaultConnectionId != profile.id && assistant != null) TextButton(onClick = { save(profiles.copy(defaultConnectionId = profile.id)) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Use for new chats") }
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Key, null)
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text("API key", style = MaterialTheme.typography.bodyMedium)
                            Text(if (hasKeys[profile.id] == true) "Key saved" else "No API key", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick = { keyProfile = profile }, enabled = !busy) { Text(if (hasKeys[profile.id] == true) "Replace API key" else "Add API key") }
                        IconButton(onClick = { removingKey = profile }, enabled = !busy) { Icon(Icons.Outlined.DeleteOutline, "Remove API key") }
                    }
                }
            }
        }
        if (assistant != null) {
            TextButton(onClick = { adding = true }, enabled = !busy && assistant.connectionsReady, modifier = Modifier.fillMaxWidth().testTag("assistant-add-connection")) {
                Icon(Icons.Outlined.Add, null, Modifier.size(20.dp))
                Text("Add connection", Modifier.padding(start = 8.dp))
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        assistant?.connectionError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        HorizontalDivider()
        Row(
            Modifier.fillMaxWidth().clickable(enabled = enabled || canEnable) {
                if (enabled) assistant?.disableProvider() else enabling = true
            }.padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(if (enabled) "Disable AI" else "Enable AI", style = MaterialTheme.typography.bodyLarge)
                Text(if (enabled) "Enabled until app restart" else "AI disabled", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = { if (assistant?.testingConnection == true) assistant.stop() else testing = true }, enabled = assistant?.testingConnection == true || (!busy && hasKeys[currentDefault.id] == true && enabled && assistant?.isResponding == false)) {
                if (assistant?.testingConnection == true) {
                    CircularProgressIndicator(Modifier.size(22.dp).semantics { contentDescription = "Stop connection test" }, strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Outlined.NetworkCheck, "Test connection")
                }
            }
            Switch(checked = enabled, enabled = enabled || canEnable, onCheckedChange = { if (it) enabling = true else assistant?.disableProvider() })
        }
        assistant?.connectionTestStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        TextButton(onClick = { privacyVisible = true }, modifier = Modifier.fillMaxWidth()) { Text("Privacy and costs", Modifier.weight(1f)) }
        if (onHelp != null) TextButton(onClick = onHelp, modifier = Modifier.fillMaxWidth()) { Text("AI chat help", Modifier.weight(1f)) }
    }
    modelProfile?.let { profile ->
        AssistantPanel("Default model", onDismiss = { modelProfile = null }) {
            profile.models.forEach { model ->
                val selected = model.id == profile.defaultModelId
                Row(
                    Modifier.fillMaxWidth().selectable(selected, role = Role.RadioButton, onClick = {
                        replace(profile.copy(defaultModelId = model.id, defaultEffort = if (profile.defaultEffort in model.efforts) profile.defaultEffort else "OFF")) { modelProfile = null }
                    }).padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected, onClick = null)
                    Text("${profile.name} ${model.name}", Modifier.padding(start = 12.dp))
                }
            }
        }
    }
    effortProfile?.let { profile ->
        AssistantPanel("Default effort", onDismiss = { effortProfile = null }) {
            DeepSeekEffort.entries.filter { it.name in profile.defaultModel.efforts }.forEach { effort ->
                Row(Modifier.fillMaxWidth().selectable(profile.defaultEffort == effort.name, role = Role.RadioButton, onClick = { replace(profile.copy(defaultEffort = effort.name)) { effortProfile = null } }).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(profile.defaultEffort == effort.name, onClick = null)
                    Text(effort.label, Modifier.padding(start = 12.dp))
                }
            }
        }
    }
    if (adding || editProfile != null) {
        ConnectionEditor(editProfile, busy, onDismiss = {
            adding = false
            editProfile = null
        }) { profile ->
            val list = if (editProfile == null) profiles.connections + profile else profiles.connections.map { if (it.id == profile.id) profile else it }
            save(profiles.copy(connections = list)) {
                val newConnection = adding
                adding = false
                editProfile = null
                if (newConnection) keyProfile = profile
            }
        }
    }
    if (privacyVisible) {
        AssistantPanel("Privacy and costs", onDismiss = { privacyVisible = false }) {
            Text("• Sends chat, workflow text and files you attach.")
            Text("• Recordings and run history stay on this device.")
            Text("• Your API account pays. Stop may still cost.")
            Text("• Deleting locally doesn't erase provider records.")
            if (currentDefault.protocol == AssistantProtocol.DEEPSEEK) {
                TextButton(onClick = { uri.openUri("https://cdn.deepseek.com/policies/en-US/deepseek-privacy-policy.html") }) { Text("Provider privacy policy") }
                TextButton(onClick = { uri.openUri("https://cdn.deepseek.com/policies/en-US/deepseek-open-platform-terms-of-service.html") }) { Text("Provider API terms") }
                TextButton(onClick = { uri.openUri("https://api-docs.deepseek.com/quick_start/pricing/") }) { Text("Provider pricing") }
            } else {
                Text("Check your provider's privacy policy and pricing.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    if (enabling || testing) {
        val isTest = testing
        AlertDialog(onDismissRequest = {
            enabling = false
            testing = false
        }, title = { Text(if (isTest) "Test ${currentDefault.name}?" else "Enable AI?") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (isTest) "• Sends a paid test to ${currentDefault.name}. No chat or workflow data." else "• Sends chat and attachments to the connection you choose.")
                Text("• Your API account pays. Stop may still cost.")
                Text(if (isTest) "• Tests the connection, not workflow quality." else "• Review changes. Save separately. AI turns off on restart.")
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
private fun ConnectionEditor(existing: AssistantConnection?, busy: Boolean, onDismiss: () -> Unit, onSave: (AssistantConnection) -> Unit) {
    val id = remember { existing?.id ?: java.util.UUID.randomUUID().toString() }
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var address by remember { mutableStateOf(existing?.baseUrl ?: "") }
    var modelId by remember { mutableStateOf(existing?.defaultModelId ?: "") }
    var protocol by remember { mutableStateOf(existing?.protocol ?: AssistantProtocol.OPENAI_COMPATIBLE) }
    var advanced by remember { mutableStateOf(false) }
    var vision by remember { mutableStateOf(existing?.defaultModel?.vision ?: false) }
    var jsonMode by remember { mutableStateOf(existing?.defaultModel?.jsonOutput ?: false) }
    var thinking by remember { mutableStateOf(existing?.defaultModel?.efforts?.size?.let { it > 1 } ?: false) }
    val model = if (protocol == AssistantProtocol.DEEPSEEK) AssistantConnection.deepSeek().models.firstOrNull { it.id == modelId.trim() } else AssistantModelConfig(modelId.trim(), existing?.models?.firstOrNull { it.id == modelId.trim() }?.name ?: modelId.trim(), vision, jsonMode, if (thinking) listOf("OFF", "LOW", "HIGH") else listOf("OFF"))
    val candidate = model?.let { selected ->
        AssistantConnection(id, name.trim(), address.trim().trimEnd('/'), protocol, existing?.models.orEmpty().filterNot { it.id == selected.id } + selected, selected.id, existing?.defaultEffort?.takeIf { it in selected.efforts } ?: "OFF")
    }
    val valid = candidate != null && runCatching { candidate.validate() }.isSuccess
    AssistantPanel(if (existing == null) "Add connection" else "Edit connection", onDismiss = onDismiss) {
        if (existing == null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                TextButton(onClick = {
                    name = "DeepSeek"
                    address = "https://api.deepseek.com"
                    modelId = "deepseek-flash"
                    protocol = AssistantProtocol.DEEPSEEK
                }) { Text("DeepSeek") }
                TextButton(onClick = {
                    name = "OpenAI"
                    address = "https://api.openai.com/v1"
                    modelId = ""
                    protocol = AssistantProtocol.OPENAI_COMPATIBLE
                }) { Text("OpenAI") }
                TextButton(onClick = {
                    name = ""
                    address = ""
                    modelId = ""
                    protocol = AssistantProtocol.OPENAI_COMPATIBLE
                }) { Text("Custom") }
            }
        }
        OutlinedTextField(name, { if (it.length <= 80) name = it }, label = { Text("Name") }, singleLine = true, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), modifier = Modifier.fillMaxWidth().testTag("connection-name"))
        OutlinedTextField(address, { if (it.length <= 2048) address = it }, label = { Text("API address") }, placeholder = { Text("https://example.com/v1") }, singleLine = true, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), readOnly = protocol == AssistantProtocol.DEEPSEEK, modifier = Modifier.fillMaxWidth().testTag("connection-address"))
        OutlinedTextField(modelId, { if (it.length <= 256) modelId = it }, label = { Text("Model ID") }, singleLine = true, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), modifier = Modifier.fillMaxWidth().testTag("connection-model"))
        if (protocol == AssistantProtocol.OPENAI_COMPATIBLE) {
            Text("Use an OpenAI-compatible Chat Completions address.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { advanced = !advanced }) { Text("Advanced") }
            AnimatedVisibility(advanced, enter = expandVertically(tween(140)), exit = shrinkVertically(tween(120))) {
                Column {
                    ConnectionCapability("Images", vision) { vision = it }
                    ConnectionCapability("JSON mode", jsonMode) { jsonMode = it }
                    ConnectionCapability("Reasoning effort", thinking) { thinking = it }
                }
            }
        }
        Button(onClick = { onSave(requireNotNull(candidate)) }, enabled = valid && !busy, modifier = Modifier.fillMaxWidth().testTag("connection-save")) { Text(if (busy) "Saving" else "Save connection") }
    }
}

@Composable
private fun ConnectionCapability(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(role = Role.Checkbox) { onChange(!checked) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        com.veyarc.feature.assistant.RoundedSelectionCheckbox(checked, onCheckedChange = null)
        Text(label, Modifier.padding(start = 12.dp))
    }
}

@Composable
private fun AssistantKeyDialog(title: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
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
                Text("Encrypted on this device.")
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
            }) { Text("Save key") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

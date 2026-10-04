package com.veyarc.app

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
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
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.veyarc.data.storage.AssistantKeyStore
import com.veyarc.feature.assistant.AssistantPanel
import com.veyarc.feature.assistant.DeepSeekModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun AssistantConnectionSettings(store: AssistantKeyStore, assistant: com.veyarc.feature.assistant.AssistantPreviewState? = null, onHelp: (() -> Unit)? = null) {
    val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current
    var hasKey by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf(false) }
    var enabling by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var privacyVisible by remember { mutableStateOf(false) }
    var modelVisible by remember { mutableStateOf(false) }
    LaunchedEffect(store) {
        try {
            hasKey = withContext(Dispatchers.IO) { store.hasKey() }
        } catch (_: Exception) {
            error = "Stored key unavailable. Replace or remove it."
        } finally {
            busy = false
        }
    }
    val enabled = assistant?.providerEnabled == true
    val canEnable = !busy && hasKey && assistant?.usesProvider == true
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                Icon(painterResource(R.drawable.ic_deepseek), null, Modifier.padding(12.dp).size(32.dp).testTag("deepseek-logo"), tint = Color.Unspecified)
            }
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text("DeepSeek", style = MaterialTheme.typography.titleMedium)
                Text("Your API key", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        HorizontalDivider()
        TextButton(onClick = { modelVisible = true }, modifier = Modifier.fillMaxWidth(), enabled = assistant?.usesProvider == true) {
            Text("Model", Modifier.weight(1f))
            Text((assistant?.selectedModel ?: DeepSeekModel.FLASH).shortLabel, Modifier.padding(end = 8.dp))
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Key, null)
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text("API key", style = MaterialTheme.typography.bodyMedium)
                Text(if (hasKey) "Key saved" else "No API key", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = { editing = true }, enabled = !busy) { Text(if (hasKey || error != null) "Replace API key" else "Add API key") }
            if (hasKey || error != null) IconButton(onClick = { removing = true }, enabled = !busy) { Icon(Icons.Outlined.DeleteOutline, "Remove API key") }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
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
            Switch(checked = enabled, enabled = enabled || canEnable, onCheckedChange = {
                if (it) enabling = true else assistant?.disableProvider()
            })
        }
        OutlinedButton(onClick = { testing = true }, modifier = Modifier.fillMaxWidth(), enabled = !busy && hasKey && enabled && assistant?.isResponding == false) { Text("Test connection") }
        if (assistant?.testingConnection == true) {
            Text("Testing connection…", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { assistant.stop() }) { Text("Stop connection test") }
        }
        assistant?.connectionTestStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        HorizontalDivider(Modifier.padding(top = 8.dp))
        TextButton(onClick = { privacyVisible = true }, modifier = Modifier.fillMaxWidth()) {
            Text("Privacy and costs", Modifier.weight(1f))
        }
        if (onHelp != null) {
            TextButton(onClick = onHelp, modifier = Modifier.fillMaxWidth()) {
                Text("AI chat help", Modifier.weight(1f))
            }
        }
    }
    if (modelVisible && assistant != null) {
        AssistantPanel("Select model", onDismiss = { modelVisible = false }) {
            DeepSeekModel.entries.forEach { model ->
                val selected = assistant.selectedModel == model
                Row(
                    Modifier.fillMaxWidth().selectable(selected = selected, enabled = assistant.canChange && !assistant.isResponding, role = Role.RadioButton, onClick = {
                        assistant.setModel(model)
                        modelVisible = false
                    }).padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = selected, onClick = null)
                    Text(model.label, Modifier.padding(start = 12.dp))
                }
            }
        }
    }
    if (privacyVisible) {
        AssistantPanel("Privacy and costs", onDismiss = { privacyVisible = false }) {
            Text("• Sends chat, workflow text and files you attach.")
            Text("• Recordings and run history stay on this device.")
            Text("• Your API account pays. No cap; Stop may still cost.")
            Text("• Avoid secrets. Retention/training guarantees are unverified.")
            Text("• Deleting locally doesn't erase DeepSeek records.")
            TextButton(onClick = { uri.openUri("https://cdn.deepseek.com/policies/en-US/deepseek-privacy-policy.html") }) { Text("Provider privacy policy") }
            TextButton(onClick = { uri.openUri("https://cdn.deepseek.com/policies/en-US/deepseek-open-platform-terms-of-service.html") }) { Text("Provider API terms") }
            TextButton(onClick = { uri.openUri("https://api-docs.deepseek.com/quick_start/pricing/") }) { Text("Provider pricing") }
        }
    }

    if (enabling || testing) {
        val isTest = testing
        AlertDialog(
            onDismissRequest = {
                enabling = false
                testing = false
            },
            title = { Text(if (isTest) "Test connection?" else "Enable DeepSeek?") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(if (isTest) "• Sends a paid test. No chat or workflow data." else "• Sends chat, workflow text and files you attach.")
                    Text("• Your API account pays. No cap; Stop may still cost.")
                    Text("• Avoid secrets. Provider retention and training guarantees are unverified.")
                    Text(if (isTest) "• Tests the connection, not workflow quality." else "• Review changes. Save separately. AI turns off on restart.")
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    enabling = false
                    testing = false
                    if (isTest) assistant?.testConnection() else assistant?.enableProvider()
                }) { Text(if (isTest) "Send test request" else "Enable for this session") }
            },
            dismissButton = {
                TextButton(onClick = {
                    enabling = false
                    testing = false
                }) { Text("Cancel") }
            },
        )
    }
    if (editing) {
        AssistantKeyDialog(onDismiss = { editing = false }, onSave = { value ->
            // Copy only into this short-lived write; never save the entry in activity or chat state.
            editing = false
            assistant?.disableProvider()
            busy = true
            scope.launch {
                try {
                    withContext(Dispatchers.IO) { store.save(value) }
                    hasKey = true
                    error = null
                } catch (_: Exception) {
                    error = "Could not save the key. Check storage and try again."
                } finally {
                    busy = false
                }
            }
        })
    }
    if (removing) {
        AlertDialog(
            onDismissRequest = { removing = false },
            title = { Text("Remove API key?") },
            text = { Text("Removes the local key. Chats stay. Revoke it at DeepSeek to stop all use.") },
            confirmButton = {
                TextButton(onClick = {
                    removing = false
                    assistant?.disableProvider()
                    busy = true
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) { store.remove() }
                            hasKey = false
                            error = null
                        } catch (_: Exception) {
                            error = "Could not remove the key. Try again."
                        } finally {
                            busy = false
                        }
                    }
                }) { Text("Remove key") }
            },
            dismissButton = { TextButton(onClick = { removing = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun AssistantKeyDialog(onDismiss: () -> Unit, onSave: (String) -> Unit) {
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
        title = { Text("DeepSeek API key") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Encrypted on this device. Unsaved entry is discarded when you leave.")
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
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
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

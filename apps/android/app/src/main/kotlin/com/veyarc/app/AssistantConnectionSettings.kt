package com.veyarc.app

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.veyarc.data.storage.AssistantKeyStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun AssistantConnectionSettings(store: AssistantKeyStore, assistant: com.veyarc.feature.assistant.AssistantPreviewState? = null) {
    val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current
    var hasKey by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf(false) }
    var enabling by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    LaunchedEffect(store) {
        try {
            hasKey = withContext(Dispatchers.IO) { store.hasKey() }
        } catch (_: Exception) {
            error = "Stored key unavailable. Replace or remove it."
        } finally {
            busy = false
        }
    }
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("AI connection", style = MaterialTheme.typography.titleMedium)
        Text("DeepSeek · deepseek-flash")
        Text(if (assistant?.providerEnabled == true) "AI enabled for this app session" else "AI disabled")
        Text(if (hasKey) "API key saved on this device · Not tested" else "No usable API key loaded")
        Text("Saving a key does not send anything. Enable AI for this session before sending messages or testing the connection. Restarting the app requires enabling it again.")
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        OutlinedButton(onClick = { editing = true }, enabled = !busy) { Text(if (hasKey || error != null) "Replace API key" else "Add API key") }
        TextButton(onClick = { removing = true }, enabled = !busy && (hasKey || error != null)) { Text("Remove API key") }
        Button(onClick = { testing = true }, enabled = !busy && hasKey && assistant?.providerEnabled == true && !assistant.isResponding) { Text("Test connection") }
        if (assistant?.providerEnabled == true) {
            Button(onClick = assistant::disableProvider) { Text("Disable AI") }
        } else {
            Button(onClick = { enabling = true }, enabled = !busy && hasKey && assistant?.usesProvider == true) { Text("Enable AI") }
        }
        if (assistant?.testingConnection == true) {
            Text("Testing connection…")
            TextButton(onClick = { assistant.stop() }) { Text("Stop connection test") }
        }
        assistant?.connectionTestStatus?.let { Text(it) }
        Text("When connected, your messages and shared workflow text go to DeepSeek and are billed to your API account. Images and recordings are excluded. Provider retention and training policies apply; no zero-retention guarantee is made.")
        TextButton(onClick = { uri.openUri("https://cdn.deepseek.com/policies/en-US/deepseek-privacy-policy.html") }) { Text("Provider privacy policy") }
        TextButton(onClick = { uri.openUri("https://cdn.deepseek.com/policies/en-US/deepseek-open-platform-terms-of-service.html") }) { Text("Provider API terms") }
    }
    if (enabling || testing) {
        val isTest = testing
        AlertDialog(
            onDismissRequest = {
                enabling = false
                testing = false
            },
            title = { Text(if (isTest) "Test DeepSeek connection?" else "Enable DeepSeek for this session?") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(if (isTest) "This sends a short fixed prompt to DeepSeek using your saved API key. It contains no chat history or workflow and can incur a small charge." else "When you press Send, your message, completed DeepSeek replies from this chat, and the captured workflow text go directly to DeepSeek using your saved key. Workflow text includes packages, coordinates, labels, variables, and image reference names. Images, recordings, run history, and other workflows are excluded.")
                    Text("DeepSeek bills your API account. Veyarc cannot enforce an account spending cap; stopping may not prevent charges. Provider retention and training policies apply, with no zero-retention guarantee. Deleting local data does not delete provider records.")
                    Text("Responses can be wrong. Review proposals before Apply, then Save separately. Enabling AI sends nothing by itself and lasts until you disable it, replace/remove the key, or restart the app.")
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
            title = { Text("Remove saved API key?") },
            text = { Text("This removes the key from Veyarc. It does not revoke it at DeepSeek or delete provider records. Chat and workflows are kept.") },
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
                Text("Stored encrypted on this device. The key is never shown again. Unsaved input is discarded when this form closes or the app backgrounds.")
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

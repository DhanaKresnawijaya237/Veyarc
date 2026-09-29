package com.veyarc.app

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
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
    var privacyVisible by remember { mutableStateOf(false) }
    LaunchedEffect(store) {
        try {
            hasKey = withContext(Dispatchers.IO) { store.hasKey() }
        } catch (_: Exception) {
            error = "Stored key unavailable. Replace or remove it."
        } finally {
            busy = false
        }
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("DeepSeek", style = MaterialTheme.typography.titleMedium)
        Text(if (assistant?.providerEnabled == true) "Enabled until app restart" else "AI disabled")
        Text(if (hasKey) "Key saved" else "No API key")
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { editing = true }, enabled = !busy) { Text(if (hasKey || error != null) "Replace API key" else "Add API key") }
            if (hasKey || error != null) TextButton(onClick = { removing = true }, enabled = !busy) { Text("Remove API key") }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (assistant?.providerEnabled == true) {
                Button(onClick = assistant::disableProvider) { Text("Disable AI") }
            } else {
                Button(onClick = { enabling = true }, enabled = !busy && hasKey && assistant?.usesProvider == true) { Text("Enable AI") }
            }
            OutlinedButton(onClick = { testing = true }, enabled = !busy && hasKey && assistant?.providerEnabled == true && !assistant.isResponding) { Text("Test connection") }
        }
        if (assistant?.testingConnection == true) {
            Text("Testing connection…")
            TextButton(onClick = { assistant.stop() }) { Text("Stop connection test") }
        }
        assistant?.connectionTestStatus?.let { Text(it) }
        TextButton(onClick = { privacyVisible = true }) { Text("Privacy and costs") }
    }
    if (privacyVisible) {
        AlertDialog(
            onDismissRequest = { privacyVisible = false },
            title = { Text("Privacy and costs") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Shared: messages, completed chat exchanges, and workflow text.")
                    Text("Workflow text includes apps, coordinates, labels, variables, and image names.")
                    Text("No image files, recordings, run history, or other workflows.")
                    Text("Your API account pays. No spending cap; Stop may still cost.")
                    Text("No verified retention or training guarantees. Avoid secrets.")
                    Text("Deleting local data does not delete DeepSeek records.")
                    Text("deepseek-flash · Saving or enabling sends nothing. AI turns off on restart or key changes.")
                    TextButton(onClick = { uri.openUri("https://cdn.deepseek.com/policies/en-US/deepseek-privacy-policy.html") }) { Text("Provider privacy policy") }
                    TextButton(onClick = { uri.openUri("https://cdn.deepseek.com/policies/en-US/deepseek-open-platform-terms-of-service.html") }) { Text("Provider API terms") }
                    TextButton(onClick = { uri.openUri("https://api-docs.deepseek.com/quick_start/pricing/") }) { Text("Provider pricing") }
                }
            },
            confirmButton = { TextButton(onClick = { privacyVisible = false }) { Text("Done") } },
        )
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
                    Text(if (isTest) "Sends a short test prompt. No chat or workflow is shared." else "Send shares your message, completed chat exchanges, and workflow text with DeepSeek. No images or recordings.")
                    Text("Your API account pays. Stopping may still cost money. Veyarc cannot cap spending.")
                    Text("Avoid sensitive text. Retention and training guarantees are unverified.")
                    Text(if (isTest) "A successful test does not validate a workflow." else "AI can be wrong. Review changes before Apply. Save separately. Enabled until app restart.")
                    TextButton(onClick = { privacyVisible = true }) { Text("Details") }
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
            text = { Text("Removes it from this device only. Chats stay. Revoke the key through DeepSeek to stop its use elsewhere.") },
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

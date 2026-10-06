package com.veyarc.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.veyarc.data.storage.AssistantConnection
import com.veyarc.data.storage.AssistantModelConfig
import com.veyarc.data.storage.AssistantProtocol
import com.veyarc.data.storage.AssistantProviderCatalog
import com.veyarc.feature.assistant.AssistantPanel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun ConnectionModels(profile: AssistantConnection, busy: Boolean, discovering: Boolean, discovered: List<AssistantModelConfig>, discoveryStatus: String?, saveError: String?, onFind: (() -> Unit)?, onCustom: () -> Unit, onUpdate: (AssistantConnection, () -> Unit) -> Unit, onDismiss: () -> Unit) {
    var adding by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf(emptySet<String>()) }
    var catalog by remember { mutableStateOf<List<AssistantModelConfig>?>(null) }
    LaunchedEffect(profile.catalogProviderId) {
        catalog = withContext(Dispatchers.IO) {
            val native = if (profile.catalogProviderId == "deepseek") AssistantConnection.deepSeek().models.map { it.copy(protocol = AssistantProtocol.DEEPSEEK) } else emptyList()
            (native + AssistantProviderCatalog.models(profile)).distinctBy { it.id }.map(::listedModelOptions)
        }
    }
    val available = (profile.models + catalog.orEmpty() + discovered).distinctBy { it.id }
    val models = (if (adding) available else profile.models).filter { it.name.contains(search, true) || it.id.contains(search, true) }
    val list = rememberLazyListState()
    LaunchedEffect(adding) { list.scrollToItem(0) }
    AssistantPanel(
        if (adding) "Add models" else "Models",
        onDismiss,
        subtitle = profile.name,
        scrollBody = false,
        bodyState = list,
        onBack = if (adding) {
            (
                {
                    adding = false
                    search = ""
                    picked = emptySet()
                }
                )
        } else {
            null
        },
        backDescription = "Back to saved models",
    ) {
        Column(Modifier.fillMaxWidth().heightIn(max = 520.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!adding) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("${profile.models.size} models", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FilledTonalButton(onClick = {
                        adding = true
                        search = ""
                    }, enabled = !busy) {
                        Icon(Icons.Outlined.Add, null, Modifier.size(18.dp))
                        Text("Add models", Modifier.padding(start = 6.dp))
                    }
                }
            }
            LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false).testTag("connection-model-list"), state = list, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (adding) {
                    item(key = "controls-actions") {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            if (profile.catalogProviderId == null && onFind != null) TextButton(onClick = onFind, enabled = !busy && !discovering) { Text("Find models") }
                            TextButton(onClick = onCustom, enabled = !busy && !discovering) { Text("Custom model") }
                        }
                    }
                }
                if (adding || models.size > 12 || search.isNotEmpty()) {
                    item(key = "controls-search") {
                        OutlinedTextField(search, { search = it }, label = { Text("Search models") }, singleLine = true, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), modifier = Modifier.fillMaxWidth())
                    }
                }
                if (adding && (catalog == null || discovering)) {
                    item(key = "controls-loading") {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Text("Loading models…", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                if (adding && discoveryStatus != null) item(key = "controls-discovery-status") { Text(discoveryStatus, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (saveError != null) item(key = "controls-save-error") { Text(saveError, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                items(models, key = { "model-${it.id}" }) { model ->
                    val saved = profile.models.any { it.id == model.id }
                    val selected = if (adding) saved || model.id in picked else model.id == profile.defaultModelId
                    val enabled = !busy && !discovering && (!adding || !saved)
                    val modifier = Modifier.fillMaxWidth().testTag("managed-model-${model.id}").then(
                        if (adding) {
                            Modifier.toggleable(selected, enabled = enabled, role = Role.Checkbox) { picked = if (it) picked + model.id else picked - model.id }
                        } else {
                            Modifier.selectable(selected, enabled = enabled, role = Role.RadioButton) { onUpdate(profile.copy(defaultModelId = model.id, defaultEffort = profile.defaultEffort.takeIf { it in model.efforts } ?: model.efforts.first()), {}) }
                        },
                    )
                    Surface(modifier, shape = RoundedCornerShape(16.dp), color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer) {
                        Row(Modifier.padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp).heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(model.name, style = MaterialTheme.typography.bodyMedium)
                                if (model.name != model.id) Text(model.id, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (adding) {
                                if (saved) {
                                    Text("Added", Modifier.padding(horizontal = 10.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                } else {
                                    Icon(if (selected) Icons.Outlined.Check else Icons.Outlined.Add, null, Modifier.padding(10.dp).size(20.dp), tint = MaterialTheme.colorScheme.primary)
                                }
                            } else {
                                if (selected) Text("Default", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                                IconButton(onClick = { onUpdate(profile.copy(defaultModelId = model.id, defaultEffort = profile.defaultEffort.takeIf { it in model.efforts } ?: model.efforts.first()), {}) }, enabled = enabled) { Icon(Icons.Outlined.Star, "Use ${model.name} as default", tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) }
                                IconButton(onClick = { onUpdate(profile.copy(models = profile.models.filterNot { it.id == model.id }), {}) }, enabled = enabled && !selected) { Icon(Icons.Outlined.DeleteOutline, "Remove model ${model.name}") }
                            }
                        }
                    }
                }
                if (models.isEmpty() && catalog != null && !discovering) item { Text("No models found", Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            if (adding) {
                Button(onClick = {
                    onUpdate(profile.copy(models = profile.models + available.filter { it.id in picked && profile.models.none { saved -> saved.id == it.id } })) {
                        picked = emptySet()
                        search = ""
                        adding = false
                    }
                }, enabled = !busy && !discovering && picked.isNotEmpty(), modifier = Modifier.fillMaxWidth().testTag("add-selected-models")) { Text(if (picked.isEmpty()) "Select models" else "Add ${picked.size} ${if (picked.size == 1) "model" else "models"}") }
            }
        }
    }
}

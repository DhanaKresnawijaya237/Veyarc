package com.veyarc.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.veyarc.data.storage.AssistantCompatibility
import com.veyarc.data.storage.AssistantConnection
import com.veyarc.data.storage.AssistantModelConfig
import com.veyarc.data.storage.AssistantProtocol
import com.veyarc.data.storage.AssistantProviderCatalog
import com.veyarc.feature.assistant.AssistantClient
import com.veyarc.feature.assistant.AssistantPanel
import com.veyarc.feature.assistant.DeepSeekEffort
import com.veyarc.feature.assistant.DeepSeekException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.util.UUID

@Composable
internal fun ConnectionEditor(existing: AssistantConnection?, busy: Boolean, newModel: Boolean = false, onManageModels: (AssistantConnection) -> Unit, onDismiss: () -> Unit, onSave: (AssistantConnection) -> Unit) {
    val id = remember { existing?.id ?: UUID.randomUUID().toString() }
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var address by remember { mutableStateOf(existing?.baseUrl.orEmpty()) }
    var providerId by remember { mutableStateOf(existing?.catalogProviderId) }
    var protocol by remember { mutableStateOf(existing?.protocol ?: AssistantProtocol.OPENAI_COMPATIBLE) }
    var model by remember { mutableStateOf(if (newModel) AssistantModelConfig("") else existing?.defaultModel ?: AssistantModelConfig("")) }
    var defaultEffort by remember { mutableStateOf(existing?.defaultEffort ?: "OFF") }
    var compatibility by remember { mutableStateOf(existing?.compatibility ?: AssistantCompatibility()) }
    var apiVersion by remember { mutableStateOf(existing?.apiVersion.orEmpty()) }
    var accountId by remember { mutableStateOf(existing?.baseUrl?.let { Regex("/accounts/([^/]+)/|cloudflare.com/v1/([^/]+)/").find(it)?.groupValues?.drop(1)?.firstOrNull { value -> value.isNotEmpty() } }.orEmpty()) }
    var gatewayId by remember { mutableStateOf(if (existing?.providerId == "cloudflare-ai-gateway") existing.baseUrl.substringAfter("/v1/").substringAfter('/').substringBefore('/') else "") }
    var advanced by remember { mutableStateOf(false) }
    var choosingProvider by remember { mutableStateOf(false) }
    var choosingModel by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var discoveryJob by remember { mutableStateOf<Job?>(null) }
    var discoveryGeneration by remember { mutableStateOf(0) }
    var discoveryStatus by remember { mutableStateOf<String?>(null) }
    var discovered by remember { mutableStateOf(emptyList<AssistantModelConfig>()) }
    var probeKey by remember { mutableStateOf(false) }
    var modelOverrides by remember { mutableStateOf(false) }
    var kwargs by remember(model.id, modelOverrides) { mutableStateOf((if (modelOverrides) model.compatibility else compatibility).chatTemplateKwargs?.toString().orEmpty()) }
    var providerCatalog by remember { mutableStateOf(emptyList<com.veyarc.data.storage.AssistantProvider>()) }
    LaunchedEffect(Unit) { providerCatalog = withContext(Dispatchers.IO) { AssistantProviderCatalog.providers } }
    val catalog = providerCatalog.firstOrNull { it.id == providerId }
    val nativeDeepSeek = if (providerId == "deepseek") AssistantConnection.deepSeek().models.map { it.copy(protocol = AssistantProtocol.DEEPSEEK) } else emptyList()
    val suppliedModels = nativeDeepSeek + catalog?.models.orEmpty()
    val available = (suppliedModels + existing?.models.orEmpty() + discovered).distinctBy { it.id }
    val suppliedModel = suppliedModels.firstOrNull { it.id == model.id }?.let(::listedModelOptions)
    val customModel = suppliedModel == null
    val configuredModel = suppliedModel?.copy(name = model.name, maxOutputTokens = model.maxOutputTokens ?: suppliedModel.maxOutputTokens) ?: model
    val selectedProtocol = configuredModel.protocol ?: protocol
    LaunchedEffect(address, selectedProtocol) {
        discoveryGeneration++
        discoveryJob?.cancel()
        discoveryJob = null
        discovered = emptyList()
        discoveryStatus = null
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                discoveryGeneration++
                discoveryJob?.cancel()
                discoveryJob = null
                probeKey = false
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            discoveryJob?.cancel()
            lifecycle.removeObserver(observer)
        }
    }
    val validEffort = defaultEffort.takeIf { it in configuredModel.efforts }
        ?: configuredModel.efforts.firstOrNull { suppliedModels.firstOrNull { listed -> listed.id == model.id }?.effortValues?.get(defaultEffort) == configuredModel.effortValues[it] }
        ?: configuredModel.efforts.first()
    val candidate = AssistantConnection(
        id, name.trim(), address.trim().trimEnd('/'), protocol,
        existing?.models.orEmpty().filterNot { it.id == model.id } + configuredModel,
        if (newModel && existing != null) existing.defaultModelId else model.id,
        if (newModel && existing != null) existing.defaultEffort else validEffort,
        providerId, compatibility, apiVersion.takeIf { it.isNotBlank() },
    )
    val tokenLimitValid = suppliedModel?.maxOutputTokens?.let { configuredModel.maxOutputTokens!! <= it } ?: true
    val valid = runCatching { candidate.validate() }.isSuccess && tokenLimitValid && (providerId == null || catalog != null)
    fun choose(value: AssistantModelConfig) {
        model = value
        defaultEffort = defaultEffort.takeIf { it in value.efforts } ?: value.efforts.first()
    }
    fun updateCompat(value: AssistantCompatibility) {
        if (modelOverrides) model = model.copy(compatibility = value) else compatibility = value
    }
    val compat = if (modelOverrides) model.compatibility else compatibility
    AssistantPanel(
        if (newModel) {
            "Add model"
        } else if (existing == null) {
            "Add connection"
        } else {
            "Edit connection"
        },
        onDismiss = onDismiss,
    ) {
        if (!newModel) {
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                contentColor = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.fillMaxWidth().testTag("connection-provider").then(
                    if (existing == null) {
                        Modifier.clickable {
                            search = ""
                            choosingProvider = true
                        }
                    } else {
                        Modifier
                    },
                ),
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    ProviderLogo(providerId, Modifier.size(44.dp))
                    Text(catalog?.name ?: existing?.name ?: "Custom provider", Modifier.weight(1f).padding(start = 12.dp), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                }
            }
            OutlinedTextField(name, { if (it.length <= 80) name = it }, label = { Text("Name") }, singleLine = true, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), modifier = Modifier.fillMaxWidth().testTag("connection-name"))
            OutlinedTextField(address, { if (it.length <= 2048) address = it }, label = { Text("API address") }, placeholder = { Text("https://example.com/v1") }, singleLine = true, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), modifier = Modifier.fillMaxWidth().testTag("connection-address"))
            if (providerId in listOf("cloudflare-workers-ai", "cloudflare-ai-gateway")) {
                fun cloudflareAddress() {
                    address = catalog?.baseUrl.orEmpty().replace("{CLOUDFLARE_ACCOUNT_ID}", accountId.ifBlank { "{CLOUDFLARE_ACCOUNT_ID}" }).replace("{CLOUDFLARE_GATEWAY_ID}", gatewayId.ifBlank { "{CLOUDFLARE_GATEWAY_ID}" })
                }
                OutlinedTextField(accountId, {
                    if (it.length <= 64 && it.all { char -> char.isLetterOrDigit() || char in "_-" }) {
                        accountId = it
                        cloudflareAddress()
                    }
                }, label = { Text("Cloudflare account ID") }, singleLine = true, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), modifier = Modifier.fillMaxWidth())
                if (providerId == "cloudflare-ai-gateway") {
                    OutlinedTextField(gatewayId, {
                        if (it.length <= 64 && it.all { char -> char.isLetterOrDigit() || char in "_-" }) {
                            gatewayId = it
                            cloudflareAddress()
                        }
                    }, label = { Text("Gateway ID") }, singleLine = true, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), modifier = Modifier.fillMaxWidth())
                }
            }
            if (address != existing?.baseUrl && existing != null) Text("Your saved key will be sent to this address.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (providerId == "azure-openai-responses") OutlinedTextField(apiVersion, { apiVersion = it.take(40) }, label = { Text("API version (optional)") }, singleLine = true, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), modifier = Modifier.fillMaxWidth())
        }
        EditorCategory(
            "Model",
            action = if (existing != null && !newModel) {
                (
                    {
                        IconButton(onClick = { onManageModels(existing) }, enabled = !busy) { Icon(Icons.Outlined.Tune, "Manage models", tint = MaterialTheme.colorScheme.primary) }
                    }
                    )
            } else {
                null
            },
        ) {
            if (providerId == null && selectedProtocol in listOf(AssistantProtocol.OPENAI_COMPATIBLE, AssistantProtocol.OPENAI_RESPONSES)) {
                TextButton(onClick = { probeKey = true }, enabled = discoveryJob == null && runCatching { AssistantConnection.validateBaseUrl(address.trim().trimEnd('/')) }.isSuccess, modifier = Modifier.fillMaxWidth().testTag("draft-find-models")) { Text(if (discoveryJob == null) "Find models" else "Loading models…") }
                discoveryStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            if (available.isNotEmpty()) {
                Surface(
                    Modifier.fillMaxWidth().testTag("choose-connection-model").clickable {
                        search = ""
                        choosingModel = true
                    },
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(if (newModel) "Choose model" else "Default model", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(model.name.ifBlank { "Choose model" }, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
            OutlinedTextField(model.id, { if (it.length <= 256) choose(available.firstOrNull { saved -> saved.id == it.trim() } ?: AssistantModelConfig(it.trim())) }, label = { Text(if (providerId == "azure-openai-responses") "Deployment / model ID" else "Model ID") }, singleLine = true, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), modifier = Modifier.fillMaxWidth().testTag("connection-model"))
            if (!newModel && configuredModel.efforts.size > 1) {
                Text("Default effort", style = MaterialTheme.typography.bodyMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    configuredModel.efforts.forEach { effort -> EditorChoice(DeepSeekEffort.valueOf(effort).label, effort == validEffort, Modifier.testTag("default-effort-$effort")) { defaultEffort = effort } }
                }
            }
        }
        TextButton(onClick = { advanced = !advanced }, enabled = providerId == null || catalog != null, modifier = Modifier.fillMaxWidth()) { Text("Advanced") }
        AnimatedVisibility(advanced, enter = expandVertically(tween(140)), exit = shrinkVertically(tween(120))) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (customModel) {
                    EditorCategory("Protocol") {
                        AssistantProtocol.entries.filter { it != AssistantProtocol.DEEPSEEK || selectedProtocol == AssistantProtocol.DEEPSEEK || providerId == "deepseek" }.forEach { format ->
                            EditorChoice(protocolLabel(format), selectedProtocol == format) {
                                if (selectedProtocol != format) {
                                    if (!newModel) protocol = format
                                    model = model.copy(protocol = format, basePath = "")
                                }
                            }
                        }
                    }
                }
                EditorCategory("Model capabilities") {
                    OutlinedTextField(model.name, { if (it.length <= 256) model = model.copy(name = it) }, label = { Text("Display name") }, singleLine = true, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), modifier = Modifier.fillMaxWidth())
                    if (customModel) {
                        OutlinedTextField(model.basePath, { if (it.length <= 512) model = model.copy(basePath = it) }, label = { Text("API subpath (optional)") }, placeholder = { Text("/v1") }, singleLine = true, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), modifier = Modifier.fillMaxWidth())
                        ConnectionCapability("Images", model.vision) { model = model.copy(vision = it) }
                        ConnectionCapability("JSON mode", model.jsonOutput) { model = model.copy(jsonOutput = it) }
                    } else {
                        Text(protocolLabel(selectedProtocol), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            CapabilityBadge(if (configuredModel.vision) "Text and images" else "Text only")
                            if (configuredModel.jsonOutput) CapabilityBadge("JSON mode")
                            if (configuredModel.reasoning) CapabilityBadge("Thinking")
                        }
                    }
                    OutlinedTextField(model.maxOutputTokens?.toString().orEmpty(), { if (it.isEmpty() || it.toIntOrNull()?.let { number -> number > 0 } == true) model = model.copy(maxOutputTokens = it.toIntOrNull()) }, label = { Text("Maximum output tokens (optional)") }, singleLine = true, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), modifier = Modifier.fillMaxWidth())
                    suppliedModel?.maxOutputTokens?.let { Text("Up to $it output tokens", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    if (!tokenLimitValid) Text("Use ${suppliedModel?.maxOutputTokens} tokens or fewer.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    if (configuredModel.contextWindow != null) Text("Context: ${configuredModel.contextWindow} tokens", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (customModel) {
                    EditorCategory("Thinking and effort") {
                        DeepSeekEffort.entries.forEach { effort ->
                            ConnectionCapability(effort.label, effort.name in model.efforts) { checked ->
                                val levels = (if (checked) model.efforts + effort.name else model.efforts - effort.name).ifEmpty { listOf("DEFAULT") }
                                model = model.copy(efforts = levels, reasoning = levels.any { it !in listOf("OFF", "DEFAULT") }, effortValues = model.effortValues.filterKeys { it in levels })
                            }
                        }
                        model.efforts.filter { it != "DEFAULT" }.forEach { effort ->
                            OutlinedTextField(model.effortValues[effort] ?: if (model.effortValues.containsKey(effort) || effort == "OFF") "" else effort.lowercase(), { value ->
                                if (value.length <= 40) model = model.copy(effortValues = model.effortValues + (effort to value.takeIf { it.isNotBlank() }))
                            }, label = { Text("${DeepSeekEffort.valueOf(effort).label} · API value") }, placeholder = { Text("Empty: omit parameter") }, singleLine = true, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), modifier = Modifier.fillMaxWidth())
                        }
                        if (selectedProtocol == AssistantProtocol.OPENAI_RESPONSES) ConnectionCapability("Reasoning summaries", model.reasoningSummary) { model = model.copy(reasoningSummary = it) }
                        if (selectedProtocol == AssistantProtocol.GOOGLE_GENERATIVE_AI) {
                            EditorChoice("Thinking levels", model.thinkingMode == "LEVEL") { model = model.copy(thinkingMode = "LEVEL") }
                            EditorChoice("Token budget", model.thinkingMode != "LEVEL") { model = model.copy(thinkingMode = "BUDGET") }
                        }
                    }
                    EditorCategory("Request compatibility") {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            TextButton(onClick = { modelOverrides = false }) { Text("Connection", color = if (!modelOverrides) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) }
                            TextButton(onClick = { modelOverrides = true }) { Text("This model", color = if (modelOverrides) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                        Text("Auto uses provider defaults. Model overrides take priority.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (selectedProtocol in listOf(AssistantProtocol.OPENAI_COMPATIBLE, AssistantProtocol.DEEPSEEK)) {
                            CompatChoice("Developer role", compat.developerRole) { updateCompat(compat.copy(developerRole = it)) }
                            CompatChoice("Reasoning effort parameter", compat.reasoningEffort) { updateCompat(compat.copy(reasoningEffort = it)) }
                            CompatChoice("Store parameter", compat.store) { updateCompat(compat.copy(store = it)) }
                            CompatChoice("Stream usage", compat.streamingUsage) { updateCompat(compat.copy(streamingUsage = it)) }
                            CompatChoice("Assistant reasoning field", compat.assistantReasoningContent) { updateCompat(compat.copy(assistantReasoningContent = it)) }
                            Text("Output token field", style = MaterialTheme.typography.labelLarge)
                            listOf(null, "max_tokens", "max_completion_tokens").forEach { value -> EditorChoice(value ?: "Auto", compat.maxTokensField == value) { updateCompat(compat.copy(maxTokensField = value)) } }
                            Text("Thinking format", style = MaterialTheme.typography.labelLarge)
                            (listOf<String?>(null) + AssistantCompatibility.THINKING_FORMATS).forEach { value -> EditorChoice(value ?: "Auto", compat.thinkingFormat == value) { updateCompat(compat.copy(thinkingFormat = value)) } }
                            if (compat.thinkingFormat in listOf("chat-template", "qwen-chat-template")) {
                                OutlinedTextField(kwargs, { value ->
                                    if (value.length <= 4096) {
                                        kwargs = value
                                        if (value.isBlank()) {
                                            updateCompat(compat.copy(chatTemplateKwargs = null))
                                        } else {
                                            (runCatching { Json.parseToJsonElement(value) as JsonObject }.getOrNull())?.let { updateCompat(compat.copy(chatTemplateKwargs = it)) }
                                        }
                                    }
                                }, label = { Text("Chat template JSON") }, isError = kwargs.isNotBlank() && runCatching { Json.parseToJsonElement(kwargs) as JsonObject }.isFailure, modifier = Modifier.fillMaxWidth())
                            }
                        }
                        if (selectedProtocol == AssistantProtocol.ANTHROPIC_MESSAGES) CompatChoice("Adaptive thinking", compat.adaptiveThinking) { updateCompat(compat.copy(adaptiveThinking = it)) }
                    }
                }
            }
        }
        if (!valid && model.id.isNotBlank() && address.isNotBlank()) Text("Check the HTTPS address, model and effort settings.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        Button(onClick = { onSave(candidate) }, enabled = valid && !busy, modifier = Modifier.fillMaxWidth().testTag("connection-save")) {
            Text(
                if (busy) {
                    "Saving"
                } else if (newModel) {
                    "Save model"
                } else {
                    "Save connection"
                },
            )
        }
    }
    if (probeKey) {
        AssistantKeyDialog("API key for model list", description = "Used only to load models. Not saved.", saveLabel = "Find models", onDismiss = { probeKey = false }, onSave = { key ->
            probeKey = false
            val endpoint = address.trim().trimEnd('/')
            val probe = AssistantConnection(id, name.ifBlank { "Custom" }, endpoint, selectedProtocol, listOf(AssistantModelConfig("discovery-only")), "discovery-only")
            val token = ++discoveryGeneration
            discoveryJob = scope.launch {
                try {
                    val models = AssistantClient(probe).discoverModels(key)
                    if (token == discoveryGeneration && address.trim().trimEnd('/') == endpoint && selectedProtocol == probe.protocol) {
                        discovered = models
                        discoveryStatus = "Choose a model. Its features still need to be set."
                        search = ""
                        choosingModel = true
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    if (token == discoveryGeneration) discoveryStatus = (error as? DeepSeekException)?.reason?.message ?: "Couldn't load models."
                } finally {
                    if (token == discoveryGeneration) discoveryJob = null
                }
            }
        })
    }
    if (choosingProvider) {
        val providers = providerCatalog.filter { it.name.contains(search, true) || it.id.contains(search, true) }
        AssistantPanel("Provider", onDismiss = { choosingProvider = false }, scrollBody = false) {
            Column(Modifier.heightIn(max = 520.dp)) {
                OutlinedTextField(search, { search = it }, label = { Text("Search providers") }, singleLine = true, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), modifier = Modifier.fillMaxWidth())
                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 12.dp)) {
                    if (providerCatalog.isEmpty()) item { Text("Loading providers…", Modifier.padding(12.dp)) }
                    item {
                        ProviderOption(null, "Custom", "Your own API address", providerId == null, onClick = {
                            providerId = null
                            name = ""
                            address = ""
                            protocol = AssistantProtocol.OPENAI_COMPATIBLE
                            compatibility = AssistantCompatibility()
                            apiVersion = ""
                            accountId = ""
                            gatewayId = ""
                            choose(AssistantModelConfig(""))
                            choosingProvider = false
                        })
                        HorizontalDivider(Modifier.padding(vertical = 10.dp))
                    }
                    items(providers.size, key = { providers[it].id }) { index ->
                        val provider = providers[index]
                        ProviderOption(provider.id, provider.name, provider.baseUrl.substringAfter("https://").substringBefore('/'), providerId == provider.id, onClick = {
                            val preset = if (provider.id == "deepseek") AssistantConnection.deepSeek().copy(id = id) else provider.connection(id)
                            providerId = provider.id
                            name = provider.name
                            address = provider.baseUrl
                            protocol = preset.protocol
                            compatibility = preset.compatibility
                            apiVersion = preset.apiVersion.orEmpty()
                            choose(preset.defaultModel)
                            choosingProvider = false
                        })
                    }
                }
            }
        }
    }
    if (choosingModel) {
        val models = available.filter { it.id.contains(search, true) || it.name.contains(search, true) }
        val listState = androidx.compose.foundation.lazy.rememberLazyListState()
        AssistantPanel("Choose model", onDismiss = { choosingModel = false }, subtitle = name, scrollBody = false, bodyState = listState) {
            Column(Modifier.heightIn(max = 520.dp)) {
                OutlinedTextField(search, { search = it }, label = { Text("Search models") }, singleLine = true, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), modifier = Modifier.fillMaxWidth())
                LazyColumn(state = listState, modifier = Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(models.size, key = { models[it].id }) { index ->
                        val value = models[index]
                        Surface(
                            Modifier.fillMaxWidth().selectable(value.id == model.id, role = Role.RadioButton) {
                                choose(value)
                                choosingModel = false
                            },
                            shape = RoundedCornerShape(16.dp),
                            color = if (value.id == model.id) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                        ) {
                            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(value.name, style = MaterialTheme.typography.bodyLarge)
                                    Text(if (value.vision) "Text and images" else "Text only", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                if (value.id == model.id) Icon(Icons.Outlined.Check, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }
        }
    }
}

// Adaptive thinking sends a level, unlike budget thinking where Minimal and Low have different budgets.
internal fun listedModelOptions(model: AssistantModelConfig): AssistantModelConfig {
    if (model.catalogCompatibility.adaptiveThinking != true) return model
    val efforts = model.efforts.filter { level ->
        val wire = model.effortValues[level]
        wire == null || model.efforts.none { other -> other != level && other.equals(wire, ignoreCase = true) }
    }
    return model.copy(efforts = efforts, effortValues = model.effortValues.filterKeys { it in efforts })
}

private fun protocolLabel(protocol: AssistantProtocol) = when (protocol) {
    AssistantProtocol.DEEPSEEK -> "DeepSeek Chat Completions"
    AssistantProtocol.OPENAI_COMPATIBLE -> "Chat Completions"
    AssistantProtocol.OPENAI_RESPONSES -> "OpenAI Responses"
    AssistantProtocol.ANTHROPIC_MESSAGES -> "Claude Messages"
    AssistantProtocol.GOOGLE_GENERATIVE_AI -> "Gemini Generate Content"
}

@Composable private fun EditorCategory(title: String, action: (@Composable () -> Unit)? = null, content: @Composable () -> Unit) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                action?.invoke()
            }
            content()
        }
    }
}

@Composable private fun EditorChoice(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val color by animateColorAsState(if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh, tween(100), label = "Option color")
    Surface(modifier.selectable(selected, role = Role.RadioButton, onClick = onClick), shape = RoundedCornerShape(14.dp), color = color) {
        Box(Modifier.heightIn(min = 48.dp).padding(horizontal = 16.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
        }
    }
}

@Composable private fun CompatChoice(label: String, value: Boolean?, onChange: (Boolean?) -> Unit) {
    Text(label, style = MaterialTheme.typography.bodyMedium)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(null to "Auto", true to "Yes", false to "No").forEach { (choice, name) ->
            EditorChoice(name, choice == value, Modifier.weight(1f)) { onChange(choice) }
        }
    }
}

@Composable private fun ConnectionCapability(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val color by animateColorAsState(if (checked) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh, tween(100), label = "Capability color")
    Surface(Modifier.fillMaxWidth().testTag("capability-$label").toggleable(checked, role = Role.Switch, onValueChange = onChange), shape = RoundedCornerShape(14.dp), color = color) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Text(if (checked) "On" else "Off", style = MaterialTheme.typography.labelLarge, color = if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable private fun CapabilityBadge(label: String) {
    Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Text(label, Modifier.padding(horizontal = 12.dp, vertical = 8.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable private fun ProviderOption(id: String?, name: String, detail: String, selected: Boolean, onClick: () -> Unit) {
    Surface(Modifier.fillMaxWidth().testTag("provider-choice-${id ?: "custom"}").selectable(selected, role = Role.RadioButton, onClick = onClick), shape = RoundedCornerShape(18.dp), color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            ProviderLogo(id, Modifier.size(48.dp))
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (selected) Icon(Icons.Outlined.Check, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
        }
    }
}

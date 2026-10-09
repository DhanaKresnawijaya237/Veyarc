package com.veyarc.data.storage

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import java.net.URI
import java.util.UUID

@Serializable
enum class AssistantProtocol { DEEPSEEK, OPENAI_COMPATIBLE, OPENAI_RESPONSES, ANTHROPIC_MESSAGES, GOOGLE_GENERATIVE_AI }

/** Only switches used by Veyarc's text/image transport; tool and cache switches do not apply. */
@Serializable
data class AssistantCompatibility(
    val developerRole: Boolean? = null,
    val reasoningEffort: Boolean? = null,
    val store: Boolean? = null,
    val streamingUsage: Boolean? = null,
    val maxTokensField: String? = null,
    val thinkingFormat: String? = null,
    val assistantReasoningContent: Boolean? = null,
    val adaptiveThinking: Boolean? = null,
    val chatTemplateKwargs: JsonObject? = null,
) {
    fun over(fallback: AssistantCompatibility) = AssistantCompatibility(
        developerRole ?: fallback.developerRole, reasoningEffort ?: fallback.reasoningEffort,
        store ?: fallback.store, streamingUsage ?: fallback.streamingUsage,
        maxTokensField ?: fallback.maxTokensField, thinkingFormat ?: fallback.thinkingFormat,
        assistantReasoningContent ?: fallback.assistantReasoningContent, adaptiveThinking ?: fallback.adaptiveThinking,
        chatTemplateKwargs ?: fallback.chatTemplateKwargs,
    )
    fun validate() {
        require(maxTokensField == null || maxTokensField in listOf("max_tokens", "max_completion_tokens"))
        require(thinkingFormat == null || thinkingFormat in THINKING_FORMATS)
        require(chatTemplateKwargs == null || chatTemplateKwargs.toString().length <= 4096)
    }
    companion object {
        val THINKING_FORMATS = listOf("openai", "deepseek", "openrouter", "together", "zai", "qwen", "chat-template", "qwen-chat-template", "string-thinking", "ant-ling", "mistral")
    }
}

@Serializable
data class AssistantModelConfig(
    val id: String,
    val name: String = id,
    val vision: Boolean = false,
    val jsonOutput: Boolean = false,
    val efforts: List<String> = listOf("OFF"),
    val reasoningSummary: Boolean = false,
    val reasoning: Boolean = efforts.any { it !in listOf("OFF", "DEFAULT") },
    val effortValues: Map<String, String?> = emptyMap(),
    val protocol: AssistantProtocol? = null,
    val basePath: String = "",
    val contextWindow: Int? = null,
    val maxOutputTokens: Int? = null,
    val thinkingMode: String? = null,
    val compatibility: AssistantCompatibility = AssistantCompatibility(),
    val catalogCompatibility: AssistantCompatibility = AssistantCompatibility(),
) {
    fun validate() {
        require(id.isNotBlank() && id.length <= 256 && id.none(Char::isISOControl))
        require(name.isNotBlank() && name.length <= 256 && name.none(Char::isISOControl))
        require(efforts.isNotEmpty() && efforts.distinct() == efforts && efforts.all { it in EFFORTS })
        require(effortValues.keys.all { it in efforts } && effortValues.values.all { it == null || (it.isNotBlank() && it.length <= 40 && it.none(Char::isISOControl)) })
        require(effortValues.all { (level, value) -> level in listOf("OFF", "DEFAULT") || value != null })
        require(basePath.length <= 512 && (basePath.isEmpty() || (basePath.startsWith('/') && !basePath.startsWith("//") && basePath.none { it.isWhitespace() || it.isISOControl() || it in "?#" } && basePath.split('/').none { it == "." || it == ".." })))
        require(contextWindow == null || contextWindow > 0)
        require(maxOutputTokens == null || maxOutputTokens > 0)
        require(thinkingMode == null || thinkingMode in listOf("LEVEL", "BUDGET"))
        compatibility.validate()
        catalogCompatibility.validate()
    }

    companion object {
        val EFFORTS = listOf("DEFAULT", "OFF", "ON", "MINIMAL", "LOW", "MEDIUM", "HIGH", "XHIGH", "MAX")
    }
}

@Serializable
data class AssistantConnection(
    val id: String,
    val name: String,
    val baseUrl: String,
    val protocol: AssistantProtocol = AssistantProtocol.OPENAI_COMPATIBLE,
    val models: List<AssistantModelConfig>,
    val defaultModelId: String,
    val defaultEffort: String = "OFF",
    val providerId: String? = null,
    val compatibility: AssistantCompatibility = AssistantCompatibility(),
    val apiVersion: String? = null,
) {
    fun validate() {
        require(validId(id))
        require(name.isNotBlank() && name.length <= 80 && name.none(Char::isISOControl))
        validateBaseUrl(baseUrl)
        require(models.isNotEmpty() && models.map { it.id }.distinct().size == models.size)
        models.forEach(AssistantModelConfig::validate)
        require(models.any { it.id == defaultModelId && defaultEffort in it.efforts })
        require(providerId == null || providerId.matches(Regex("[a-z0-9][a-z0-9-]{0,79}")))
        require(apiVersion == null || apiVersion.matches(Regex("[a-zA-Z0-9-]{1,40}")))
        compatibility.validate()
    }

    val defaultModel: AssistantModelConfig get() = models.first { it.id == defaultModelId }

    // Older saved presets predate providerId. Recognize their explicit protocol and official address.
    val catalogProviderId: String? get() = providerId ?: when {
        protocol == AssistantProtocol.DEEPSEEK && baseUrl.trimEnd('/') == "https://api.deepseek.com" -> "deepseek"
        protocol == AssistantProtocol.OPENAI_RESPONSES && baseUrl.trimEnd('/') == "https://api.openai.com/v1" -> "openai"
        else -> null
    }
    val canDiscoverModels: Boolean get() = catalogProviderId != null || protocolFor(defaultModel) in listOf(AssistantProtocol.DEEPSEEK, AssistantProtocol.OPENAI_COMPATIBLE, AssistantProtocol.OPENAI_RESPONSES)
    fun protocolFor(model: AssistantModelConfig) = model.protocol ?: protocol
    fun baseUrlFor(model: AssistantModelConfig) = baseUrl.trimEnd('/') + model.basePath
    fun compatibilityFor(model: AssistantModelConfig) = model.compatibility.over(compatibility.over(model.catalogCompatibility))

    companion object {
        const val DEEPSEEK_ID = "deepseek"
        fun validId(id: String): Boolean = id == DEEPSEEK_ID || runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)
        fun validateBaseUrl(url: String) {
            require(url.length <= 2048 && url.none { it.isWhitespace() || it.isISOControl() })
            val uri = URI(url)
            require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null)
            require(uri.port == -1 || uri.port in 1..65535)
        }
        fun deepSeek(modelId: String = "deepseek-flash", effort: String = "OFF"): AssistantConnection = AssistantConnection(
            DEEPSEEK_ID,
            "DeepSeek",
            "https://api.deepseek.com",
            AssistantProtocol.DEEPSEEK,
            listOf(
                AssistantModelConfig("deepseek-flash", "V4.1 Flash", vision = true, jsonOutput = true, efforts = listOf("OFF", "LOW", "HIGH", "MAX")),
                AssistantModelConfig("deepseek-v4-pro", "V4 Pro", jsonOutput = true, efforts = listOf("OFF", "LOW", "HIGH", "MAX")),
            ),
            modelId,
            effort,
            providerId = "deepseek",
        ).also { it.validate() }

        fun openAI(id: String = UUID.randomUUID().toString()): AssistantConnection = AssistantConnection(
            id,
            "OpenAI",
            "https://api.openai.com/v1",
            AssistantProtocol.OPENAI_RESPONSES,
            listOf(AssistantModelConfig("gpt-5.4", "GPT-5.4", vision = true, jsonOutput = true, efforts = listOf("OFF", "LOW", "MEDIUM", "HIGH", "XHIGH"), reasoningSummary = true)),
            "gpt-5.4",
            providerId = "openai",
        ).also { it.validate() }
    }
}

@Serializable
data class AssistantConnections(val connections: List<AssistantConnection>, val defaultConnectionId: String, val version: Int = 1) {
    fun validate() {
        require(version == 1 && connections.isNotEmpty())
        require(connections.map { it.id }.distinct().size == connections.size)
        connections.forEach(AssistantConnection::validate)
        require(connections.any { it.id == defaultConnectionId })
    }
}

package com.veyarc.feature.assistant

import com.veyarc.data.storage.AssistantConnection
import com.veyarc.data.storage.AssistantModelConfig
import com.veyarc.data.storage.AssistantProtocol
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal fun AssistantModelConfig.wireEffort(effort: DeepSeekEffort): String? = when {
    effort == DeepSeekEffort.DEFAULT -> null
    effortValues.containsKey(effort.name) -> effortValues[effort.name]
    effort == DeepSeekEffort.OFF -> null
    else -> effort.name.lowercase()
}

internal object ChatCompletions {
    fun encode(messages: List<JsonObject>, streaming: Boolean, model: AssistantModelConfig, effort: DeepSeekEffort, connection: AssistantConnection, maxTokens: Int?): ByteArray {
        val compat = connection.compatibilityFor(model)
        val legacyDeepSeek = connection.protocolFor(model) == AssistantProtocol.DEEPSEEK
        val enabled = model.reasoning && effort !in listOf(DeepSeekEffort.OFF, DeepSeekEffort.DEFAULT)
        val wire = model.wireEffort(effort)
        return buildJsonObject {
            put("model", model.id)
            put(
                "messages",
                JsonArray(
                    messages.map { message ->
                        JsonObject(
                            message.toMutableMap().apply {
                                if (message["role"] == JsonPrimitive("system") && model.reasoning && compat.developerRole == true) put("role", JsonPrimitive("developer"))
                                if (message["role"] == JsonPrimitive("assistant") && enabled && compat.assistantReasoningContent == true) put("reasoning_content", JsonPrimitive(""))
                            },
                        )
                    },
                ),
            )
            if (maxTokens != null) {
                put(compat.maxTokensField ?: "max_tokens", maxTokens)
            } else if (legacyDeepSeek) {
                put("max_tokens", DeepSeekRequest.MAX_OUTPUT_TOKENS)
            }
            if (compat.store == true) put("store", false)
            if (streaming && compat.streamingUsage == true) put("stream_options", buildJsonObject { put("include_usage", true) })
            if (model.reasoning && effort != DeepSeekEffort.DEFAULT) {
                when (compat.thinkingFormat ?: if (legacyDeepSeek) "deepseek" else "openai") {
                    "deepseek", "zai" -> {
                        if (enabled || !model.effortValues.containsKey("OFF") || model.effortValues["OFF"] != null) {
                            put(
                                "thinking",
                                buildJsonObject {
                                    put("type", if (enabled) "enabled" else "disabled")
                                    if (enabled && compat.thinkingFormat == "zai") put("clear_thinking", false)
                                },
                            )
                        }
                        if (enabled && compat.reasoningEffort != false && wire != null) put("reasoning_effort", wire)
                    }

                    "qwen" -> put("enable_thinking", enabled)

                    "qwen-chat-template", "chat-template" -> put(
                        "chat_template_kwargs",
                        compat.chatTemplateKwargs?.let { kwargs ->
                            JsonObject(
                                kwargs.mapValues { (_, value) ->
                                    when ((value as? JsonObject)?.get("\$var")) {
                                        JsonPrimitive("thinking.enabled") -> JsonPrimitive(enabled)
                                        JsonPrimitive("thinking.effort") -> wire?.let(::JsonPrimitive) ?: JsonNull
                                        else -> value
                                    }
                                },
                            )
                        } ?: buildJsonObject {
                            put("enable_thinking", enabled)
                            put("preserve_thinking", true)
                        },
                    )

                    "openrouter", "ant-ling" -> if (enabled || (model.effortValues["OFF"] != null && compat.thinkingFormat == "openrouter")) put("reasoning", buildJsonObject { put("effort", wire ?: "none") })

                    "together" -> {
                        put("reasoning", buildJsonObject { put("enabled", enabled) })
                        if (enabled && compat.reasoningEffort == true && wire != null) put("reasoning_effort", wire)
                    }

                    "string-thinking" -> if (wire != null) put("thinking", wire)

                    "mistral" -> if (enabled) {
                        if (compat.reasoningEffort == true) put("reasoning_effort", wire ?: "high") else put("prompt_mode", "reasoning")
                    }

                    else -> if (wire != null && compat.reasoningEffort != false) put("reasoning_effort", wire)
                }
            }
            if (model.jsonOutput) put("response_format", buildJsonObject { put("type", "json_object") })
            put("stream", streaming)
        }.toString().toByteArray(Charsets.UTF_8)
    }
}

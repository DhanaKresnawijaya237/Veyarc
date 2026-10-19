package com.veyarc.feature.assistant

import com.veyarc.data.storage.AssistantProtocol
import com.veyarc.data.storage.AssistantTokenUsage
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Optional provider metadata must never turn a valid reply into a failed request. */
internal fun decodeTokenUsage(fields: JsonObject?, protocol: AssistantProtocol): AssistantTokenUsage? {
    if (fields == null) return null
    return runCatching {
        fun JsonObject.count(key: String): Long? {
            val value = get(key) ?: return null
            require(value is JsonPrimitive && !value.isString)
            return value.longOrNull ?: error("Invalid usage count")
        }
        fun detail(key: String): JsonObject = fields[key] as? JsonObject ?: JsonObject(emptyMap())
        val usage = when (protocol) {
            AssistantProtocol.ANTHROPIC_MESSAGES -> {
                val cached = fields.count("cache_read_input_tokens") ?: 0
                val written = fields.count("cache_creation_input_tokens") ?: 0
                AssistantTokenUsage((fields.count("input_tokens") ?: return null) + cached + written, fields.count("output_tokens") ?: return null, cached, written)
            }

            AssistantProtocol.GOOGLE_GENERATIVE_AI -> {
                val thoughts = fields.count("thoughtsTokenCount")
                AssistantTokenUsage(fields.count("promptTokenCount") ?: return null, (fields.count("candidatesTokenCount") ?: return null) + (thoughts ?: 0), fields.count("cachedContentTokenCount") ?: 0, reasoning = thoughts)
            }

            AssistantProtocol.OPENAI_RESPONSES -> AssistantTokenUsage(
                fields.count("input_tokens") ?: return null,
                fields.count("output_tokens") ?: return null,
                detail("input_tokens_details").count("cached_tokens") ?: 0,
                reasoning = detail("output_tokens_details").count("reasoning_tokens"),
            )

            else -> AssistantTokenUsage(
                fields.count("prompt_tokens") ?: return null,
                fields.count("completion_tokens") ?: return null,
                fields.count("prompt_cache_hit_tokens") ?: detail("prompt_tokens_details").count("cached_tokens") ?: 0,
                reasoning = detail("completion_tokens_details").count("reasoning_tokens"),
            )
        }
        usage.also(AssistantTokenUsage::validate)
    }.getOrNull()
}

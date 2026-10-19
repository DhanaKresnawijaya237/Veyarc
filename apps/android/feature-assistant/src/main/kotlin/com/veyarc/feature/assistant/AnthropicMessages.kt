package com.veyarc.feature.assistant

import com.veyarc.data.storage.AssistantCompatibility
import com.veyarc.data.storage.AssistantModelConfig
import com.veyarc.feature.assistant.AssistantClient.Companion.string
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.put
import java.io.InputStream

internal object AnthropicMessages {
    fun encode(messages: List<JsonObject>, streaming: Boolean, model: AssistantModelConfig, effort: DeepSeekEffort, compat: AssistantCompatibility, maxTokens: Int?): ByteArray {
        val enabled = model.reasoning && effort !in listOf(DeepSeekEffort.OFF, DeepSeekEffort.DEFAULT)
        val budget = when (effort) {
            DeepSeekEffort.MINIMAL -> 1024
            DeepSeekEffort.LOW -> 2048
            DeepSeekEffort.MEDIUM -> 8192
            else -> 16384
        }
        val limit = model.maxOutputTokens ?: 8192
        val output = if (enabled && compat.adaptiveThinking != true) minOf(limit, (maxTokens ?: limit) + budget) else minOf(maxTokens ?: limit, limit)
        return buildJsonObject {
            put("model", model.id)
            put("system", messages.first().string("content"))
            put(
                "messages",
                JsonArray(
                    messages.drop(1).map { message ->
                        val content = message.getValue("content")
                        if (content !is JsonArray) {
                            message
                        } else {
                            buildJsonObject {
                                put("role", message.string("role"))
                                put(
                                    "content",
                                    JsonArray(
                                        content.map { value ->
                                            val part = value as JsonObject
                                            if (part.string("type") == "text") {
                                                part
                                            } else {
                                                buildJsonObject {
                                                    require(part.string("type") == "image_url")
                                                    val data = (part["image_url"] as JsonObject).string("url")
                                                    require(data.startsWith("data:image/png;base64,"))
                                                    put("type", "image")
                                                    put(
                                                        "source",
                                                        buildJsonObject {
                                                            put("type", "base64")
                                                            put("media_type", "image/png")
                                                            put("data", data.substringAfter(','))
                                                        },
                                                    )
                                                }
                                            }
                                        },
                                    ),
                                )
                            }
                        }
                    },
                ),
            )
            put("max_tokens", output)
            put("stream", streaming)
            if (model.reasoning && effort != DeepSeekEffort.DEFAULT) {
                if (enabled) {
                    put(
                        "thinking",
                        buildJsonObject {
                            put("type", if (compat.adaptiveThinking == true) "adaptive" else "enabled")
                            if (compat.adaptiveThinking != true) put("budget_tokens", minOf(budget, output - 512).also { require(it >= 1024) })
                        },
                    )
                    if (compat.adaptiveThinking == true) model.wireEffort(effort)?.let { wire -> put("output_config", buildJsonObject { put("effort", if (effort == DeepSeekEffort.MINIMAL && !model.effortValues.containsKey("MINIMAL")) "low" else wire) }) }
                } else if (model.effortValues["OFF"] != null || !model.effortValues.containsKey("OFF")) {
                    put("thinking", buildJsonObject { put("type", "disabled") })
                }
            }
        }.toString().toByteArray(Charsets.UTF_8)
    }

    fun decode(root: JsonObject, request: AssistantRequest): ByteArray {
        header(root, request)
        require(root["stop_reason"] == JsonPrimitive("end_turn"))
        val output = AssistantOutput(request)
        for (value in root["content"] as JsonArray) {
            val part = value as JsonObject
            when (part.string("type")) {
                "text" -> output.text(part.string("text"))
                "thinking" -> output.thinking(part.string("thinking"))
                "redacted_thinking" -> Unit
                else -> error("Unsupported content")
            }
        }
        val bytes = output.finish()
        request.captureUsage(root["usage"] as? JsonObject)
        return bytes
    }

    private fun header(root: JsonObject, request: AssistantRequest) {
        require(root.string("type") == "message" && root.string("role") == "assistant")
        require(root.string("id").isNotBlank() && root.string("id").length <= 256)
        require(AssistantClient.validWireModel(root.string("model"), request))
        require(root["error"] == null || root["error"] == JsonNull)
    }

    fun read(input: InputStream, request: AssistantRequest, cancellation: DeepSeekCancellation): ByteArray {
        val output = AssistantOutput(request)
        var started = false
        var stopped = false
        var index = -1
        var active: String? = null
        var initialUsage: JsonObject? = null
        try {
            return AssistantSse.read(input, cancellation, DeepSeekStream.MAX_EVENT_BYTES) { name, payload ->
                val event = AssistantClient.parseObject(payload.toByteArray(Charsets.UTF_8))
                val type = event.string("type")
                require(name == null || name == type)
                when (type) {
                    "ping" -> Unit

                    "message_start" -> {
                        require(!started)
                        val message = event["message"] as JsonObject
                        header(message, request)
                        require((message["content"] as JsonArray).isEmpty() && message["stop_reason"] == JsonNull)
                        started = true
                        initialUsage = message["usage"] as? JsonObject
                    }

                    "content_block_start" -> {
                        require(started && !stopped && active == null)
                        require((event["index"] as JsonPrimitive).int == ++index)
                        val block = event["content_block"] as JsonObject
                        active = block.string("type").also { require(it in listOf("text", "thinking", "redacted_thinking")) }
                        if (active == "text") output.text(block.string("text"))
                        if (active == "thinking") output.thinking(block.string("thinking"))
                    }

                    "content_block_delta" -> {
                        require(started && !stopped && active != null && (event["index"] as JsonPrimitive).int == index)
                        val delta = event["delta"] as JsonObject
                        when (delta.string("type")) {
                            "text_delta" -> {
                                require(active == "text")
                                output.text(delta.string("text"))
                            }

                            "thinking_delta" -> {
                                require(active == "thinking")
                                output.thinking(delta.string("thinking"))
                            }

                            "signature_delta" -> require(active == "thinking")

                            else -> error("Unsupported delta")
                        }
                    }

                    "content_block_stop" -> {
                        require(active != null && (event["index"] as JsonPrimitive).int == index)
                        active = null
                    }

                    "message_delta" -> {
                        require(started && active == null)
                        (event["usage"] as? JsonObject)?.let { usage -> request.captureUsage(JsonObject(initialUsage.orEmpty() + usage)) }
                        val reason = (event["delta"] as JsonObject)["stop_reason"]
                        if (reason != null && reason != JsonNull) {
                            require(!stopped && reason == JsonPrimitive("end_turn"))
                            stopped = true
                        }
                    }

                    "message_stop" -> {
                        require(started && stopped && active == null)
                        return@read output.finish()
                    }

                    "error" -> throw DeepSeekException(DeepSeekFailure.UNAVAILABLE)

                    else -> error("Unsupported event")
                }
                null
            }
        } finally {
            output.publish()
        }
    }
}

package com.veyarc.feature.assistant

import com.veyarc.data.storage.AssistantModelConfig
import com.veyarc.feature.assistant.AssistantClient.Companion.string
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.InputStream

internal object GoogleContent {
    fun encode(messages: List<JsonObject>, streaming: Boolean, model: AssistantModelConfig, effort: DeepSeekEffort, maxTokens: Int?): ByteArray = buildJsonObject {
        put("systemInstruction", buildJsonObject { put("parts", JsonArray(listOf(buildJsonObject { put("text", messages.first().string("content")) }))) })
        put(
            "contents",
            JsonArray(
                messages.drop(1).map { message ->
                    buildJsonObject {
                        put("role", if (message.string("role") == "assistant") "model" else "user")
                        val content = message.getValue("content")
                        val parts = if (content is JsonArray) {
                            content.map { value ->
                                val part = value as JsonObject
                                buildJsonObject {
                                    if (part.string("type") == "text") {
                                        put("text", part.string("text"))
                                    } else {
                                        require(part.string("type") == "image_url")
                                        val data = (part["image_url"] as JsonObject).string("url")
                                        require(data.startsWith("data:image/png;base64,"))
                                        put(
                                            "inlineData",
                                            buildJsonObject {
                                                put("mimeType", "image/png")
                                                put("data", data.substringAfter(','))
                                            },
                                        )
                                    }
                                }
                            }
                        } else {
                            listOf(buildJsonObject { put("text", message.string("content")) })
                        }
                        put("parts", JsonArray(parts))
                    }
                },
            ),
        )
        put(
            "generationConfig",
            buildJsonObject {
                if (maxTokens != null) put("maxOutputTokens", maxTokens)
                if (model.jsonOutput) put("responseMimeType", "application/json")
                if (model.reasoning && effort != DeepSeekEffort.DEFAULT) {
                    put(
                        "thinkingConfig",
                        buildJsonObject {
                            if (effort != DeepSeekEffort.OFF) put("includeThoughts", true)
                            val wire = model.wireEffort(effort)
                            if (model.thinkingMode == "LEVEL") {
                                put("thinkingLevel", wire ?: if (effort == DeepSeekEffort.OFF) "MINIMAL" else effort.name)
                            } else {
                                put(
                                    "thinkingBudget",
                                    wire?.toIntOrNull() ?: when (effort) {
                                        DeepSeekEffort.OFF -> 0
                                        DeepSeekEffort.MINIMAL -> 1024
                                        DeepSeekEffort.LOW -> 2048
                                        DeepSeekEffort.MEDIUM -> 8192
                                        else -> 24576
                                    },
                                )
                            }
                        },
                    )
                }
            },
        )
    }.toString().toByteArray(Charsets.UTF_8)

    private fun consume(root: JsonObject, request: AssistantRequest, output: AssistantOutput): Boolean {
        require(root["error"] == null)
        val feedback = root["promptFeedback"] as? JsonObject
        require(feedback?.get("blockReason") == null)
        root["modelVersion"]?.let { require(AssistantClient.validWireModel((it as JsonPrimitive).content, request)) }
        val candidate = (root["candidates"] as JsonArray).single() as JsonObject
        require(candidate["index"] == null || candidate["index"] == JsonPrimitive(0))
        (candidate["content"] as? JsonObject)?.let { content ->
            require(content["role"] == null || content["role"] == JsonPrimitive("model"))
            for (value in content["parts"] as JsonArray) {
                val part = value as JsonObject
                require(part.keys.all { it in listOf("text", "thought", "thoughtSignature") })
                val text = part.string("text")
                if ((part["thought"] as? JsonPrimitive)?.booleanOrNull == true) output.thinking(text) else output.text(text)
            }
        }
        val reason = candidate["finishReason"]
        require(reason == null || reason == JsonPrimitive("STOP"))
        return reason == JsonPrimitive("STOP")
    }
    fun decode(root: JsonObject, request: AssistantRequest): ByteArray {
        val output = AssistantOutput(request)
        require(consume(root, request, output))
        return output.finish()
    }
    fun read(input: InputStream, request: AssistantRequest, cancellation: DeepSeekCancellation): ByteArray {
        val output = AssistantOutput(request)
        var responseId: JsonPrimitive? = null
        var model: JsonPrimitive? = null
        try {
            return AssistantSse.read(input, cancellation, DeepSeekStream.MAX_EVENT_BYTES) { name, payload ->
                require(name == null || name == "message")
                val root = AssistantClient.parseObject(payload.toByteArray(Charsets.UTF_8))
                (root["responseId"] as? JsonPrimitive)?.let {
                    require(responseId == null || responseId == it)
                    responseId = it
                }
                (root["modelVersion"] as? JsonPrimitive)?.let {
                    require(model == null || model == it)
                    model = it
                }
                if (consume(root, request, output)) output.finish() else null
            }
        } finally {
            output.publish()
        }
    }
}

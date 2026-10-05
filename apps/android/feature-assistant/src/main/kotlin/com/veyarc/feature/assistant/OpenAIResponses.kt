package com.veyarc.feature.assistant

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

/** Stateless Responses API. Output prose may stream; workflow candidates still require final validation. */
internal object OpenAIResponses {
    fun encode(messages: List<JsonObject>, streaming: Boolean, model: AssistantModelConfig, effort: DeepSeekEffort, maxTokens: Int? = null): ByteArray = buildJsonObject {
        put("model", model.id)
        put("instructions", messages.first().string("content"))
        put(
            "input",
            JsonArray(
                messages.drop(1).map { message ->
                    val content = message.getValue("content")
                    if (content !is JsonArray) {
                        message
                    } else {
                        buildJsonObject {
                            require(message.string("role") == "user")
                            put("role", "user")
                            put(
                                "content",
                                JsonArray(
                                    content.map { value ->
                                        val part = value as JsonObject
                                        buildJsonObject {
                                            when (part.string("type")) {
                                                "text" -> {
                                                    put("type", "input_text")
                                                    put("text", part.string("text"))
                                                }

                                                "image_url" -> {
                                                    put("type", "input_image")
                                                    put("image_url", (part["image_url"] as JsonObject).string("url"))
                                                    put("detail", "auto")
                                                }

                                                else -> error("Unsupported attachment")
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
        put("store", false)
        put("stream", streaming)
        if (maxTokens != null) put("max_output_tokens", maxTokens)
        if (model.efforts.any { it != "OFF" } && effort != DeepSeekEffort.DEFAULT) {
            put(
                "reasoning",
                buildJsonObject {
                    (model.wireEffort(effort) ?: "none".takeIf { effort == DeepSeekEffort.OFF && model.effortValues.isEmpty() })?.let { put("effort", it) }
                    if (effort != DeepSeekEffort.OFF && model.reasoningSummary) put("summary", "auto")
                },
            )
        }
        if (model.jsonOutput) put("text", buildJsonObject { put("format", buildJsonObject { put("type", "json_object") }) })
    }.toString().toByteArray(Charsets.UTF_8)

    fun decode(response: JsonObject, request: AssistantRequest, publishSummary: Boolean = true): ByteArray {
        validateHeader(response, request)
        require(response.string("status") == "completed")
        require(response["error"] == null || response["error"] == JsonNull)
        require(response["incomplete_details"] == null || response["incomplete_details"] == JsonNull)
        val output = response["output"] as JsonArray
        require(output.all { (it as JsonObject).string("type") in listOf("message", "reasoning") })
        val message = output.map { it as JsonObject }.single { it.string("type") == "message" }
        require(message.string("role") == "assistant" && message.string("status") == "completed")
        val parts = message["content"] as JsonArray
        require(parts.isNotEmpty())
        val text = StringBuilder()
        var size = 0
        for (part in parts) {
            part as JsonObject
            require(part.string("type") == "output_text")
            val piece = part.string("text")
            size += piece.toByteArray(Charsets.UTF_8).size
            if (size > AssistantResponseCodec.MAX_BYTES) throw DeepSeekException(DeepSeekFailure.RESPONSE_TOO_LARGE)
            text.append(piece)
        }
        val bytes = text.toString().toByteArray(Charsets.UTF_8)
        AssistantResponseCodec.decode(bytes, request.requestId, request.conversationId, request.baseSnapshotId)
        val summary = summary(response)
        if (publishSummary && wantsSummary(request) && summary.isNotBlank()) {
            AssistantThinkingBuffer(request).apply {
                append(summary)
                finish()
            }
        }
        return bytes
    }

    private fun wantsSummary(request: AssistantRequest) = request.thinkingEnabled && request.connection.models.first { it.id == request.modelId }.reasoningSummary

    private fun summary(response: JsonObject): String {
        val parts = mutableListOf<String>()
        var size = 0
        for (item in response["output"] as JsonArray) {
            item as JsonObject
            if (item.string("type") != "reasoning") continue
            for (part in item["summary"] as JsonArray) {
                part as JsonObject
                require(part.string("type") == "summary_text")
                val text = part.string("text")
                size += text.toByteArray(Charsets.UTF_8).size + if (parts.isEmpty()) 0 else 2
                if (size > AssistantClient.MAX_REASONING_BYTES) throw DeepSeekException(DeepSeekFailure.RESPONSE_TOO_LARGE)
                parts.add(text)
            }
        }
        return parts.joinToString("\n\n")
    }

    private class SummaryPart {
        val text = StringBuilder()
        var done = false
    }

    private fun validateHeader(response: JsonObject, request: AssistantRequest) {
        require(response.string("object") == "response")
        require(validId(response.string("id")) && AssistantClient.validWireModel(response.string("model"), request))
    }

    private fun validId(id: String) = id.isNotBlank() && id.length <= 256 && id.none(Char::isISOControl)

    fun read(input: InputStream, request: AssistantRequest, cancellation: DeepSeekCancellation): ByteArray {
        var responseId: String? = null
        var wireModel: String? = null
        var sequence = -1
        var messageId: String? = null
        var messageIndex = -1
        var textDone = false
        val text = StringBuilder()
        var textBytes = 0
        val reply = AssistantReplyBuffer(request)
        val thinking = AssistantThinkingBuffer(request)
        val reasoningItems = mutableMapOf<Int, String>()
        val summaries = linkedMapOf<Pair<Int, Int>, SummaryPart>()
        var summaryBytes = 0
        fun summaryKey(event: JsonObject): Pair<Int, Int> {
            val index = (event["output_index"] as JsonPrimitive).int
            val partIndex = (event["summary_index"] as JsonPrimitive).int
            require(index >= 0 && partIndex >= 0 && reasoningItems[index] == event.string("item_id"))
            return index to partIndex
        }
        fun checkPart(event: JsonObject) {
            require(messageId != null && event.string("item_id") == messageId)
            require(event["output_index"] == JsonPrimitive(messageIndex) && event["content_index"] == JsonPrimitive(0))
        }
        fun checkResponse(response: JsonObject) {
            validateHeader(response, request)
            require(responseId == null || response.string("id") == responseId)
            require(wireModel == null || response.string("model") == wireModel)
            responseId = response.string("id")
            wireModel = response.string("model")
        }
        try {
            return AssistantSse.read(input, cancellation, AssistantClient.MAX_RESPONSE_BYTES) { name, payload ->
                val event = AssistantClient.parseObject(payload.toByteArray(Charsets.UTF_8))
                val type = event.string("type")
                require(name == null || name == type)
                val next = (event["sequence_number"] as JsonPrimitive).also { require(!it.isString) }.int
                require(next > sequence)
                sequence = next
                when (type) {
                    "response.created" -> {
                        require(responseId == null)
                        checkResponse(event["response"] as JsonObject)
                    }

                    "response.in_progress" -> {
                        require(responseId != null)
                        checkResponse(event["response"] as JsonObject)
                    }

                    "response.output_item.added" -> {
                        require(responseId != null)
                        val item = event["item"] as JsonObject
                        require(validId(item.string("id")))
                        when (item.string("type")) {
                            "reasoning" -> {
                                val index = (event["output_index"] as JsonPrimitive).int
                                require(index >= 0 && index != messageIndex && reasoningItems.put(index, item.string("id")) == null)
                            }

                            "message" -> {
                                require(messageId == null && item.string("role") == "assistant")
                                messageId = item.string("id")
                                messageIndex = (event["output_index"] as JsonPrimitive).int.also { require(it >= 0) }
                                require(messageIndex !in reasoningItems)
                            }

                            else -> error("Unsupported output")
                        }
                    }

                    "response.content_part.added", "response.content_part.done" -> {
                        checkPart(event)
                        require((event["part"] as JsonObject).string("type") == "output_text")
                    }

                    "response.output_text.delta" -> {
                        checkPart(event)
                        require(!textDone)
                        val piece = event.string("delta")
                        textBytes += piece.toByteArray(Charsets.UTF_8).size
                        if (textBytes > AssistantResponseCodec.MAX_BYTES) throw DeepSeekException(DeepSeekFailure.RESPONSE_TOO_LARGE)
                        text.append(piece)
                        if (piece.isNotEmpty()) {
                            require(summaries.values.all { it.done })
                            thinking.finish()
                        }
                        reply.append(piece)
                    }

                    "response.output_text.done" -> {
                        checkPart(event)
                        require(!textDone && event.string("text") == text.toString())
                        textDone = true
                    }

                    "response.output_item.done" -> {
                        val item = event["item"] as JsonObject
                        require(item.string("type") in listOf("message", "reasoning"))
                        if (item.string("type") == "message") {
                            require(item.string("id") == messageId && textDone)
                        } else {
                            require(reasoningItems[(event["output_index"] as JsonPrimitive).int] == item.string("id"))
                        }
                    }

                    "response.reasoning_summary_part.added" -> {
                        val key = summaryKey(event)
                        val part = event["part"] as JsonObject
                        require(part.string("type") == "summary_text" && part.string("text").isEmpty() && key !in summaries)
                        require(text.isEmpty() && summaries.values.all { it.done })
                        if (summaries.isNotEmpty()) {
                            summaryBytes += 2
                            if (wantsSummary(request)) thinking.append("\n\n")
                        }
                        summaries[key] = SummaryPart()
                    }

                    "response.reasoning_summary_text.delta" -> {
                        val part = summaries.getValue(summaryKey(event))
                        require(!part.done && text.isEmpty())
                        val piece = event.string("delta")
                        summaryBytes += piece.toByteArray(Charsets.UTF_8).size
                        if (summaryBytes > AssistantClient.MAX_REASONING_BYTES) throw DeepSeekException(DeepSeekFailure.RESPONSE_TOO_LARGE)
                        part.text.append(piece)
                        if (wantsSummary(request)) thinking.append(piece)
                    }

                    "response.reasoning_summary_text.done" -> {
                        val part = summaries.getValue(summaryKey(event))
                        require(!part.done && event.string("text") == part.text.toString())
                        part.done = true
                    }

                    "response.reasoning_summary_part.done" -> {
                        val part = summaries.getValue(summaryKey(event))
                        val final = event["part"] as JsonObject
                        require(part.done && final.string("type") == "summary_text" && final.string("text") == part.text.toString())
                        require(event["status"] == null || event["status"] == JsonNull)
                    }

                    "response.completed" -> {
                        require(responseId != null && textDone)
                        val response = event["response"] as JsonObject
                        checkResponse(response)
                        val output = response["output"] as JsonArray
                        require((output[messageIndex] as JsonObject).string("id") == messageId)
                        reasoningItems.forEach { (index, id) -> require((output[index] as JsonObject).string("id") == id) }
                        val bytes = decode(response, request, publishSummary = false)
                        require(bytes.toString(Charsets.UTF_8) == text.toString())
                        require(summaries.values.all { it.done })
                        require(summary(response) == summaries.values.joinToString("\n\n") { it.text.toString() })
                        thinking.finish()
                        reply.publish(force = true)
                        return@read bytes
                    }

                    else -> throw DeepSeekException(DeepSeekFailure.INVALID_RESPONSE)
                }
                null
            }
        } finally {
            thinking.publish(force = true)
            reply.publish(force = true)
        }
    }
}

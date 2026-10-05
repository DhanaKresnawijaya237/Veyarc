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
    fun encode(messages: List<JsonObject>, streaming: Boolean, model: AssistantModelConfig): ByteArray = buildJsonObject {
        put("model", model.id)
        put("instructions", messages.first().string("content"))
        put("input", JsonArray(messages.drop(1)))
        put("store", false)
        put("stream", streaming)
        if (model.jsonOutput) put("text", buildJsonObject { put("format", buildJsonObject { put("type", "json_object") }) })
    }.toString().toByteArray(Charsets.UTF_8)

    fun decode(response: JsonObject, request: AssistantRequest): ByteArray {
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
        return bytes
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
                            "reasoning" -> Unit

                            "message" -> {
                                require(messageId == null && item.string("role") == "assistant")
                                messageId = item.string("id")
                                messageIndex = (event["output_index"] as JsonPrimitive).int.also { require(it >= 0) }
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
                        if (item.string("type") == "message") require(item.string("id") == messageId && textDone)
                    }

                    "response.reasoning_summary_part.added", "response.reasoning_summary_part.done",
                    "response.reasoning_summary_text.delta", "response.reasoning_summary_text.done",
                    -> require(responseId != null)

                    "response.completed" -> {
                        require(responseId != null && textDone)
                        val response = event["response"] as JsonObject
                        checkResponse(response)
                        val output = response["output"] as JsonArray
                        require((output[messageIndex] as JsonObject).string("id") == messageId)
                        val bytes = decode(response, request)
                        require(bytes.toString(Charsets.UTF_8) == text.toString())
                        reply.publish(force = true)
                        return@read bytes
                    }

                    else -> throw DeepSeekException(DeepSeekFailure.INVALID_RESPONSE)
                }
                null
            }
        } finally {
            reply.publish(force = true)
        }
    }
}

package com.veyarc.feature.assistant

import com.veyarc.feature.assistant.AssistantClient.Companion.string
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.InputStream

/** Stream display prose separately. Only a validated stop + DONE envelope leaves this reader. */
internal object DeepSeekStream {
    const val MAX_EVENT_BYTES = 512 * 1024

    fun read(input: InputStream, request: AssistantRequest, cancellation: DeepSeekCancellation): ByteArray {
        val content = StringBuilder()
        var contentBytes = 0
        var reasoningBytes = 0
        val thinking = AssistantThinkingBuffer(request)
        val reply = AssistantReplyBuffer(request)
        var completionId: String? = null
        var wireModel: String? = null
        var assistant = false
        var stopped = false
        fun dispatch(name: String?, payload: String): ByteArray? {
            require(name == null || name == "message" || name.isEmpty())
            if (payload == "[DONE]") {
                require(stopped && assistant)
                val bytes = content.toString().toByteArray(Charsets.UTF_8)
                AssistantResponseCodec.decode(bytes, request.requestId, request.conversationId, request.baseSnapshotId)
                return bytes
            }
            val chunk = DeepSeekClient.parseObject(payload.toByteArray(Charsets.UTF_8))
            require(chunk.string("object") == "chat.completion.chunk" && ChatCompletionClient.validWireModel(chunk.string("model"), request))
            require(wireModel == null || wireModel == chunk.string("model"))
            wireModel = chunk.string("model")
            val id = chunk.string("id")
            require(id.isNotBlank() && id.length <= 256 && (completionId == null || completionId == id))
            completionId = id
            request.captureUsage(chunk["usage"] as? JsonObject)
            val choices = chunk["choices"] as JsonArray
            // Compatible servers may send a usage-only chunk after the terminal choice.
            if (!request.strictDeepSeek && choices.isEmpty()) {
                require(stopped && chunk["usage"] is JsonObject && chunk["error"] == null)
                return null
            }
            require(!stopped)
            val choice = choices.single() as JsonObject
            require(choice["index"] == JsonPrimitive(0))
            val delta = choice["delta"] as JsonObject
            listOf(chunk, choice, delta).forEach { fields ->
                listOf("error", "tool_calls", "function_call", "refusal", "content_filter_results").forEach {
                    require(fields[it] == null || fields[it] == JsonNull)
                }
            }
            // Reasoning is separate provider output, never workflow JSON or conversation history.
            reasoningBytes += DeepSeekClient.reasoningBytes(delta, request)
            if (reasoningBytes > DeepSeekClient.MAX_REASONING_BYTES) throw DeepSeekException(DeepSeekFailure.RESPONSE_TOO_LARGE)
            if (delta["role"] != null && delta["role"] != JsonNull) {
                require(delta.string("role") == "assistant")
                assistant = true
            }
            if (request.thinkingEnabled) {
                val piece = DeepSeekClient.reasoningText(delta, request)
                if (piece.isNotEmpty()) {
                    require(assistant)
                    thinking.append(piece)
                }
            }
            if (delta["content"] != null && delta["content"] != JsonNull) {
                val piece = DeepSeekClient.contentText(delta, request)
                contentBytes += piece.toByteArray(Charsets.UTF_8).size
                if (contentBytes > AssistantResponseCodec.MAX_BYTES) throw DeepSeekException(DeepSeekFailure.RESPONSE_TOO_LARGE)
                if (piece.isNotEmpty()) thinking.finish()
                content.append(piece)
                if (piece.isNotEmpty()) {
                    require(assistant)
                    reply.append(piece)
                }
            }
            when (choice.getValue("finish_reason")) {
                JsonNull -> Unit
                JsonPrimitive("stop") -> stopped = true
                else -> error("Unsupported terminal reason")
            }
            return null
        }

        try {
            return AssistantSse.read(input, cancellation, MAX_EVENT_BYTES, ::dispatch)
        } finally {
            thinking.publish(force = true)
            reply.publish(force = true)
        }
    }
}

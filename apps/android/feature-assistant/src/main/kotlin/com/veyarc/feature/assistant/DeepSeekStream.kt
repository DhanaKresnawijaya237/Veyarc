package com.veyarc.feature.assistant

import com.veyarc.feature.assistant.DeepSeekClient.Companion.string
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

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
        var assistant = false
        var stopped = false
        var firstEvent = true
        val event = ByteArrayOutputStream()
        var lineLength = 0
        var skipLf = false

        fun dispatch(): ByteArray? {
            val decoded = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(event.toByteArray())).toString()
            val text = if (firstEvent) decoded.removePrefix("\uFEFF") else decoded
            firstEvent = false
            event.reset()
            val data = mutableListOf<String>()
            text.split(Regex("\r\n|\r|\n")).forEach { line ->
                if (line.isEmpty() || line.startsWith(':')) return@forEach
                val field = line.substringBefore(':')
                val value = line.substringAfter(':', "").removePrefix(" ")
                when (field) {
                    "data" -> data.add(value)

                    "event" -> require(value == "message" || value.isEmpty())

                    "id", "retry" -> Unit

                    // Never reconnect or resend, regardless of these hints.
                    else -> Unit // SSE defines unknown fields as ignorable, within the event cap.
                }
            }
            if (data.isEmpty()) return null
            val payload = data.joinToString("\n")
            if (payload == "[DONE]") {
                require(stopped && assistant)
                val bytes = content.toString().toByteArray(Charsets.UTF_8)
                AssistantResponseCodec.decode(bytes, request.requestId, request.conversationId, request.baseSnapshotId)
                return bytes
            }
            require(!stopped)
            val chunk = DeepSeekClient.parseObject(payload.toByteArray(Charsets.UTF_8))
            require(chunk.string("object") == "chat.completion.chunk" && chunk.string("model") == request.modelId)
            val id = chunk.string("id")
            require(id.isNotBlank() && id.length <= 256 && (completionId == null || completionId == id))
            completionId = id
            val choice = (chunk["choices"] as JsonArray).single() as JsonObject
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
            if (delta["reasoning_content"] != null && delta["reasoning_content"] != JsonNull) {
                val piece = delta.string("reasoning_content")
                if (piece.isNotEmpty()) {
                    require(assistant)
                    thinking.append(piece)
                }
            }
            if (delta["content"] != null && delta["content"] != JsonNull) {
                val piece = delta.string("content")
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

        // Read bytes so event limits apply before UTF-8 decoding, including comments and metadata.
        val buffered = input.buffered()
        try {
            while (true) {
                cancellation.checkActive()
                val value = buffered.read()
                if (value < 0) throw DeepSeekException(DeepSeekFailure.INVALID_RESPONSE)
                if (skipLf && value == 10) {
                    skipLf = false
                    if (event.size() > 0) {
                        if (event.size() == MAX_EVENT_BYTES) throw DeepSeekException(DeepSeekFailure.RESPONSE_TOO_LARGE)
                        event.write(value)
                    }
                    continue
                }
                skipLf = value == 13
                if (event.size() == MAX_EVENT_BYTES) throw DeepSeekException(DeepSeekFailure.RESPONSE_TOO_LARGE)
                event.write(value)
                if (value == 10 || value == 13) {
                    if (lineLength == 0) dispatch()?.let { return it }
                    lineLength = 0
                } else {
                    lineLength++
                }
            }
        } finally {
            thinking.publish(force = true)
            reply.publish(force = true)
        }
    }
}

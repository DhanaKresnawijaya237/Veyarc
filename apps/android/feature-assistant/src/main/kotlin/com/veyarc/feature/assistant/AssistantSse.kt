package com.veyarc.feature.assistant

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Bounded UTF-8 SSE framing. A provider parser decides which event completes an attempt. */
internal object AssistantSse {
    fun read(input: InputStream, cancellation: DeepSeekCancellation, maxEventBytes: Int, consume: (String?, String) -> ByteArray?): ByteArray {
        val event = ByteArrayOutputStream()
        var lineLength = 0
        var skipLf = false
        var firstEvent = true
        fun dispatch(): ByteArray? {
            val decoded = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(event.toByteArray())).toString()
            val text = if (firstEvent) decoded.removePrefix("\uFEFF") else decoded
            firstEvent = false
            event.reset()
            val data = mutableListOf<String>()
            var type: String? = null
            text.split(Regex("\r\n|\r|\n")).forEach { line ->
                val field = line.substringBefore(':')
                val value = line.substringAfter(':', "").removePrefix(" ")
                when (field) {
                    "data" -> data.add(value)

                    "event" -> type = value

                    // Comments, IDs, reconnect hints and unknown SSE fields never trigger a retry.
                    else -> Unit
                }
            }
            return if (data.isEmpty()) null else consume(type, data.joinToString("\n"))
        }
        val buffered = input.buffered()
        while (true) {
            cancellation.checkActive()
            val value = buffered.read()
            if (value < 0) throw DeepSeekException(DeepSeekFailure.INVALID_RESPONSE)
            if (skipLf && value == 10) {
                skipLf = false
                if (event.size() > 0) {
                    if (event.size() == maxEventBytes) throw DeepSeekException(DeepSeekFailure.RESPONSE_TOO_LARGE)
                    event.write(value)
                }
                continue
            }
            skipLf = value == 13
            if (event.size() == maxEventBytes) throw DeepSeekException(DeepSeekFailure.RESPONSE_TOO_LARGE)
            event.write(value)
            if (value == 10 || value == 13) {
                if (lineLength == 0) {
                    dispatch()?.let {
                        cancellation.checkActive()
                        return it
                    }
                }
                lineLength = 0
            } else {
                lineLength++
            }
        }
    }
}

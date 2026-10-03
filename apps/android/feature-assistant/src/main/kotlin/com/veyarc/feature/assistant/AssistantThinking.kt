package com.veyarc.feature.assistant

import kotlinx.serialization.Serializable

/** Provider thinking is display-only; it is never an answer, workflow candidate, or request history. */
@Serializable
data class AssistantThinking(val text: String, val truncated: Boolean = false, val finished: Boolean = false) {
    init {
        require(text.length <= MAX_LENGTH && (text.isNotEmpty() || truncated))
    }

    companion object {
        const val MAX_LENGTH = 16000
    }
}

/** Bound retained text and UI updates without changing the transport's full reasoning byte limit. */
internal class AssistantThinkingBuffer(private val request: DeepSeekRequest) {
    private val text = StringBuilder()
    private var truncated = false
    private var finished = false
    private var lastPublishedAt = 0L

    fun append(piece: String) {
        if (piece.isEmpty()) return
        require(!finished)
        val remaining = AssistantThinking.MAX_LENGTH - text.length
        text.append(piece.take(remaining))
        truncated = truncated || piece.length > remaining
        publish()
    }

    fun finish() {
        if (finished) return
        finished = true
        publish(force = true)
    }

    fun publish(force: Boolean = false) {
        if (text.isEmpty()) return
        val now = System.nanoTime()
        if (!force && lastPublishedAt != 0L && now - lastPublishedAt < 100_000_000L) return
        // A cap or token boundary must not display half an emoji.
        val value = text.toString().let { if (it.last().isHighSurrogate()) it.dropLast(1) else it }
        if (value.isEmpty()) return
        val thinking = AssistantThinking(value, truncated || (finished && value.length != text.length), finished)
        if (thinking != request.latestThinking) request.reportThinking(thinking)
        lastPublishedAt = now
    }
}

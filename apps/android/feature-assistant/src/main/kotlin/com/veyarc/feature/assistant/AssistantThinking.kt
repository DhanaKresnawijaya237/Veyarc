package com.veyarc.feature.assistant

import kotlinx.serialization.Serializable

/** Provider thinking is display-only; it is never an answer, workflow candidate, or request history. */
@Serializable
data class AssistantThinking(val text: String, val truncated: Boolean = false, val finished: Boolean = false, val summary: Boolean = false) {
    init {
        require(text.length <= MAX_LENGTH && (text.isNotEmpty() || truncated))
    }

    companion object {
        // Match the transport guard. Accepted thinking is retained in full.
        const val MAX_LENGTH = 2 * 1024 * 1024
    }
}

/** Bound retained text and UI updates without changing the transport's full reasoning byte limit. */
internal class AssistantThinkingBuffer(private val request: AssistantRequest) {
    private val text = StringBuilder()
    private var finished = false
    private var lastPublishedAt = 0L

    fun append(piece: String) {
        if (piece.isEmpty()) return
        require(!finished)
        require(text.length + piece.length <= AssistantThinking.MAX_LENGTH)
        text.append(piece)
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
        // Don't display half an emoji while its next delta is still pending.
        val value = text.toString().let { if (it.last().isHighSurrogate()) it.dropLast(1) else it }
        if (value.isEmpty()) return
        val thinking = AssistantThinking(value, finished = finished, summary = request.connection.protocol == com.veyarc.data.storage.AssistantProtocol.OPENAI_RESPONSES)
        if (thinking != request.latestThinking) request.reportThinking(thinking)
        lastPublishedAt = now
    }
}

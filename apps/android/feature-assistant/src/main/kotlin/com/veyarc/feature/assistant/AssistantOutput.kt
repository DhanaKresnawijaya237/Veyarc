package com.veyarc.feature.assistant

/** Display progress immediately; accept workflow JSON only at a successful protocol terminal. */
internal class AssistantOutput(private val request: AssistantRequest) {
    private val text = StringBuilder()
    private var textBytes = 0
    private var thinkingBytes = 0
    private val reply = AssistantReplyBuffer(request)
    private val thinking = AssistantThinkingBuffer(request)
    fun text(piece: String) {
        textBytes += piece.toByteArray(Charsets.UTF_8).size
        if (textBytes > AssistantResponseCodec.MAX_BYTES) throw DeepSeekException(DeepSeekFailure.RESPONSE_TOO_LARGE)
        text.append(piece)
        reply.append(piece)
    }
    fun thinking(piece: String) {
        thinkingBytes += piece.toByteArray(Charsets.UTF_8).size
        if (thinkingBytes > AssistantClient.MAX_REASONING_BYTES) throw DeepSeekException(DeepSeekFailure.RESPONSE_TOO_LARGE)
        if (request.thinkingEnabled) thinking.append(piece)
    }
    fun finish(): ByteArray {
        val bytes = text.toString().toByteArray(Charsets.UTF_8)
        AssistantResponseCodec.decode(bytes, request.requestId, request.conversationId, request.baseSnapshotId)
        thinking.finish()
        reply.publish(force = true)
        return bytes
    }
    fun publish() {
        thinking.publish(force = true)
        reply.publish(force = true)
    }
}

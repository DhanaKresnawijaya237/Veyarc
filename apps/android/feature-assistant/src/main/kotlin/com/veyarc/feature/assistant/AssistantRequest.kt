package com.veyarc.feature.assistant

import com.veyarc.data.storage.AssistantConnection
import com.veyarc.data.storage.AssistantProtocol

/** Completed, validated exchanges only. Failed, interrupted and sample turns stay local. */
class AssistantHistoryTurn(val prompt: String, val response: AssistantResponse, val attachments: List<AttachmentContent> = emptyList())

/** One immutable attempt. No generated toString can disclose its prompt or workflow. */
class AssistantRequest internal constructor(
    internal val body: ByteArray,
    internal val requestId: String,
    internal val conversationId: String,
    internal val baseSnapshotId: String,
    internal val streaming: Boolean,
    val modelId: String,
    internal val thinkingEnabled: Boolean,
    private val onThinking: ((AssistantThinking) -> Unit)? = null,
    private val onReply: ((String) -> Unit)? = null,
    val connection: AssistantConnection = AssistantConnection.deepSeek(),
) {
    internal val protocol: AssistantProtocol get() = connection.protocolFor(connection.models.first { it.id == modelId })
    internal val strictDeepSeek: Boolean get() = protocol == AssistantProtocol.DEEPSEEK

    @Volatile internal var latestReply: String = ""
        private set

    @Volatile internal var latestThinking: AssistantThinking? = null
        private set

    /** Progress is display-only. Proposals come exclusively from the validated final response. */
    fun reportReply(text: String) {
        require(text.length <= AssistantResponseCodec.MAX_TEXT_LENGTH)
        latestReply = text
        onReply?.invoke(text)
    }

    /** The UI owner checks the attempt identity before publishing worker progress. */
    fun reportThinking(thinking: AssistantThinking) {
        require(thinkingEnabled)
        latestThinking = thinking
        onThinking?.invoke(thinking)
    }
}

package com.veyarc.feature.assistant

import com.veyarc.model.WorkflowJson
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

@Serializable
enum class AssistantOutcome {
    @SerialName("explanation")
    EXPLANATION,

    @SerialName("clarification")
    CLARIFICATION,

    @SerialName("proposal")
    PROPOSAL,
}

/** A proposal carries a complete candidate body, which still requires local validation. */
@Serializable
data class AssistantResponse(
    val contractVersion: Int,
    val requestId: String,
    val conversationId: String,
    val baseSnapshotId: String,
    val outcome: AssistantOutcome,
    val text: String,
    val candidate: JsonObject? = null,
)

internal object AssistantResponseCodec {
    const val MAX_BYTES = 64 * 1024
    const val MAX_TEXT_LENGTH = 16000

    fun decode(bytes: ByteArray, requestId: String, conversationId: String, baseSnapshotId: String): AssistantResponse {
        require(bytes.size in 1..MAX_BYTES)
        val json = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        WorkflowJson.checkNesting(json, 64)
        rejectDuplicateKeys(json)
        val fields = WorkflowJson.format.parseToJsonElement(json) as? JsonObject ?: error("Expected response object")
        fields.forEach { (key, value) ->
            require(if (key == "candidate") value is JsonObject else value is JsonPrimitive && value.isString == (key != "contractVersion"))
        }
        val response = WorkflowJson.decodeStrict(AssistantResponse.serializer(), json)
        require(response.contractVersion == 1)
        require(response.requestId == requestId && response.conversationId == conversationId && response.baseSnapshotId == baseSnapshotId)
        require((response.outcome == AssistantOutcome.PROPOSAL) == (response.candidate != null))
        require(response.text.isNotBlank() && response.text.length <= MAX_TEXT_LENGTH)
        return response
    }

    fun encode(response: AssistantResponse): ByteArray = WorkflowJson.encodeCanonical(AssistantResponse.serializer(), response).toByteArray(Charsets.UTF_8)

    /** Scan only string tokens; the strict codec below checks JSON syntax and field types. */
    internal fun rejectDuplicateKeys(json: String) {
        val objects = ArrayDeque<MutableSet<String>>()
        var index = 0
        while (index < json.length) {
            when (json[index++]) {
                '{' -> {
                    objects.addLast(mutableSetOf())
                    continue
                }

                '}' -> {
                    if (objects.isNotEmpty()) objects.removeLast()
                    continue
                }

                '"' -> Unit

                else -> continue
            }
            val start = index - 1
            var escaped = false
            while (index < json.length) {
                val char = json[index++]
                if (escaped) {
                    escaped = false
                } else if (char == '\\') {
                    escaped = true
                } else if (char == '"') {
                    break
                }
            }
            val end = index
            while (index < json.length && json[index].isWhitespace()) index++
            if (index < json.length && json[index] == ':') {
                val key = WorkflowJson.format.decodeFromString<String>(json.substring(start, end))
                require(objects.isNotEmpty() && objects.last().add(key))
            }
        }
    }
}

internal fun PreviewTurn.responseBaseId(chat: PreviewConversation): String = baseSnapshot?.snapshotId ?: "draft:${chat.draftId}"

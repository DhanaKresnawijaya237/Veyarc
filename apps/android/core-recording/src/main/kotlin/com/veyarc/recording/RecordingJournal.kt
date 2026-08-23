@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.veyarc.recording

import com.veyarc.model.PackageName
import com.veyarc.model.ViewportSpec
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonClassDiscriminator
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.UUID

const val CURRENT_RECORDING_JOURNAL_VERSION: Int = 1
const val DEFAULT_MAX_RECORDING_JOURNAL_BYTES: Long = 16L * 1024L * 1024L

/** Metadata written as the first record in every recording journal. */
@Serializable
data class RecordingJournalHeader(
    val sessionId: String,
    val startedAtEpochMillis: Long,
    val viewport: ViewportSpec,
    val formatVersion: Int = CURRENT_RECORDING_JOURNAL_VERSION,
) {
    init {
        require(runCatching { UUID.fromString(sessionId) }.isSuccess) {
            "Recording session id must be a UUID"
        }
        require(startedAtEpochMillis >= 0L) {
            "Recording start time must be nonnegative"
        }
        require(formatVersion > 0) {
            "Recording journal format version must be positive"
        }
    }
}

@Serializable
enum class RecordingTouchAction {
    @SerialName("down")
    DOWN,

    @SerialName("move")
    MOVE,

    @SerialName("up")
    UP,
}

/** One strict JSON Lines record in the raw recording journal. */
@Serializable
@JsonClassDiscriminator("type")
sealed interface RecordingJournalLine {
    @Serializable
    @SerialName("header")
    data class Header(
        val header: RecordingJournalHeader,
    ) : RecordingJournalLine

    @Serializable
    @SerialName("touch")
    data class Touch(
        val elapsedMillis: Long,
        val action: RecordingTouchAction,
        val xPx: Double,
        val yPx: Double,
    ) : RecordingJournalLine {
        init {
            require(elapsedMillis >= 0L) { "Touch timestamp must be nonnegative" }
            require(xPx.isFinite() && yPx.isFinite()) { "Touch coordinates must be finite" }
            require(xPx >= 0.0 && yPx >= 0.0) { "Touch coordinates must be nonnegative" }
        }
    }

    @Serializable
    @SerialName("foregroundPackage")
    data class ForegroundPackage(
        val elapsedMillis: Long,
        val packageName: PackageName?,
    ) : RecordingJournalLine {
        init {
            require(elapsedMillis >= 0L) { "Foreground timestamp must be nonnegative" }
        }
    }
}

/** Cursor used to validate the event order without inspecting semantic UI content. */
data class RecordingJournalCursor(
    val lastElapsedMillis: Long = -1L,
    val touchInProgress: Boolean = false,
) {
    fun accept(line: RecordingJournalLine): RecordingJournalCursor {
        require(line !is RecordingJournalLine.Header) {
            "A journal header cannot be appended after the first record"
        }
        val elapsedMillis = when (line) {
            is RecordingJournalLine.Touch -> line.elapsedMillis
            is RecordingJournalLine.ForegroundPackage -> line.elapsedMillis
            is RecordingJournalLine.Header -> error("Header was checked above")
        }
        require(elapsedMillis >= lastElapsedMillis) {
            "Journal timestamps must be monotonic"
        }

        val nextTouchInProgress = when (line) {
            is RecordingJournalLine.Touch -> when (line.action) {
                RecordingTouchAction.DOWN -> {
                    require(!touchInProgress) { "A touch-down cannot start while another touch is active" }
                    true
                }

                RecordingTouchAction.MOVE -> {
                    require(touchInProgress) { "A touch-move requires an active touch" }
                    true
                }

                RecordingTouchAction.UP -> {
                    require(touchInProgress) { "A touch-up requires an active touch" }
                    false
                }
            }

            is RecordingJournalLine.ForegroundPackage -> touchInProgress

            is RecordingJournalLine.Header -> error("Header was checked above")
        }
        return RecordingJournalCursor(elapsedMillis, nextTouchInProgress)
    }
}

data class RecordingJournalSnapshot(
    val header: RecordingJournalHeader,
    val events: List<RecordingJournalLine>,
    val cursor: RecordingJournalCursor,
    /** Number of bytes ignored from an incomplete final JSON line, if any. */
    val trailingPartialBytes: Int,
    /** Byte offset through which the journal is valid and safe to append. */
    val validPrefixBytes: Int,
    val endsWithNewline: Boolean,
)

/** Strict JSONL codec and crash-tail recovery for the platform-neutral recording format. */
object RecordingJournalCodec {
    val json: Json = Json {
        classDiscriminator = "type"
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = false
        isLenient = false
        prettyPrint = false
        allowTrailingComma = false
        allowComments = false
        allowSpecialFloatingPointValues = false
    }

    fun encodeLine(line: RecordingJournalLine): ByteArray = encodeRecord(line) + "\n".toByteArray(StandardCharsets.UTF_8)

    fun encodeRecord(line: RecordingJournalLine): ByteArray = json.encodeToString(RecordingJournalLine.serializer(), line).toByteArray(StandardCharsets.UTF_8)

    fun decodeLine(bytes: ByteArray): RecordingJournalLine {
        require(bytes.isNotEmpty()) { "Journal records cannot be empty" }
        require(bytes.none { it == '\n'.code.toByte() || it == '\r'.code.toByte() }) {
            "Journal records cannot contain line breaks"
        }
        val text = try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (error: Exception) {
            throw SerializationException("Journal record is not valid UTF-8", error)
        }
        return try {
            json.decodeFromString(RecordingJournalLine.serializer(), text)
        } catch (error: Exception) {
            if (error is SerializationException || error is IllegalArgumentException) throw error
            throw SerializationException("Journal record is not valid", error)
        }
    }

    /**
     * Parses a JSONL payload and ignores only an incomplete final record. A complete but
     * semantically invalid record is treated as corruption and rejected.
     */
    fun parseJsonLines(
        bytes: ByteArray,
        maxBytes: Long = DEFAULT_MAX_RECORDING_JOURNAL_BYTES,
    ): RecordingJournalSnapshot {
        require(maxBytes > 0L) { "Journal size limit must be positive" }
        require(bytes.size.toLong() <= maxBytes) { "Recording journal exceeds its size limit" }
        require(bytes.isNotEmpty()) { "Recording journal is empty" }

        var header: RecordingJournalHeader? = null
        val events = mutableListOf<RecordingJournalLine>()
        var cursor = RecordingJournalCursor()
        var validPrefixBytes = 0
        var trailingPartialBytes = 0
        var lineStart = 0
        var offset = 0

        fun process(lineBytes: ByteArray) {
            val record = decodeLine(lineBytes)
            when (record) {
                is RecordingJournalLine.Header -> {
                    require(header == null && events.isEmpty()) {
                        "A recording journal must contain exactly one header first"
                    }
                    require(record.header.formatVersion == CURRENT_RECORDING_JOURNAL_VERSION) {
                        "Recording journal format version is unsupported"
                    }
                    header = record.header
                }

                is RecordingJournalLine.Touch,
                is RecordingJournalLine.ForegroundPackage,
                -> {
                    require(header != null) { "Recording journal events require a header" }
                    cursor = cursor.accept(record)
                    events += record
                }
            }
        }

        while (offset < bytes.size) {
            if (bytes[offset] != '\n'.code.toByte()) {
                offset += 1
                continue
            }
            val lineBytes = bytes.copyOfRange(lineStart, offset)
            require(lineBytes.isNotEmpty()) { "Recording journal cannot contain blank lines" }
            process(lineBytes)
            offset += 1
            lineStart = offset
            validPrefixBytes = offset
        }

        if (lineStart < bytes.size) {
            val finalLine = bytes.copyOfRange(lineStart, bytes.size)
            try {
                process(finalLine)
                validPrefixBytes = bytes.size
            } catch (error: SerializationException) {
                // The writer fsyncs each complete line. A crash may leave only this final
                // JSON fragment; keep every complete preceding line and repair this tail.
                trailingPartialBytes = finalLine.size
                validPrefixBytes = lineStart
            }
        }

        val parsedHeader = header ?: throw SerializationException("Recording journal has no header")
        return RecordingJournalSnapshot(
            header = parsedHeader,
            events = events.toList(),
            cursor = cursor,
            trailingPartialBytes = trailingPartialBytes,
            validPrefixBytes = validPrefixBytes,
            endsWithNewline = bytes.last() == '\n'.code.toByte(),
        )
    }
}

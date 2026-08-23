package com.veyarc.data.storage

import com.veyarc.model.PackageName
import com.veyarc.recording.DEFAULT_MAX_RECORDING_JOURNAL_BYTES
import com.veyarc.recording.RecordingJournalCodec
import com.veyarc.recording.RecordingJournalCursor
import com.veyarc.recording.RecordingJournalHeader
import com.veyarc.recording.RecordingJournalLine
import com.veyarc.recording.RecordingTouchAction
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer

data class EncryptedRecordingJournalSnapshot(
    val header: RecordingJournalHeader,
    val events: List<RecordingJournalLine>,
    val cursor: RecordingJournalCursor,
)

/**
 * Crash-safe append-only recording storage. Each JSONL record is authenticated and encrypted
 * independently, then written as a length-delimited frame and fsynced before append returns.
 */
class EncryptedRecordingJournalStore private constructor(
    private val file: File,
    private val codec: AesGcmFileCodec,
    private val maxEncryptedBytes: Long,
    private val access: RandomAccessFile,
    val header: RecordingJournalHeader,
    private val events: MutableList<RecordingJournalLine>,
    private var cursor: RecordingJournalCursor,
    private var nextSequence: Long,
) : Closeable {
    private var closed = false

    fun append(event: RecordingJournalLine) = synchronized(this) {
        checkOpen()
        require(event !is RecordingJournalLine.Header) {
            "A recording journal header is written when the journal is created"
        }
        val nextCursor = cursor.accept(event)
        val encrypted = codec.encrypt(
            plaintext = RecordingJournalCodec.encodeRecord(event),
            associatedData = eventAssociatedData(header.sessionId, nextSequence),
        )
        val frame = frame(encrypted)
        require(access.length() + frame.size <= maxEncryptedBytes) {
            "Encrypted recording journal exceeds its size limit"
        }
        access.seek(access.length())
        access.write(frame)
        access.fd.sync()
        events += event
        cursor = nextCursor
        nextSequence += 1L
    }

    fun appendTouch(
        elapsedMillis: Long,
        action: RecordingTouchAction,
        xPx: Double,
        yPx: Double,
    ) = append(
        RecordingJournalLine.Touch(
            elapsedMillis = elapsedMillis,
            action = action,
            xPx = xPx,
            yPx = yPx,
        ),
    )

    fun appendForegroundPackage(
        elapsedMillis: Long,
        packageName: PackageName?,
    ) = append(
        RecordingJournalLine.ForegroundPackage(
            elapsedMillis = elapsedMillis,
            packageName = packageName,
        ),
    )

    fun snapshot(): EncryptedRecordingJournalSnapshot = synchronized(this) {
        checkOpen()
        EncryptedRecordingJournalSnapshot(header, events.toList(), cursor)
    }

    override fun close() {
        synchronized(this) {
            if (closed) return
            access.fd.sync()
            access.close()
            closed = true
        }
    }

    private fun checkOpen() {
        check(!closed) { "Recording journal is closed" }
    }

    companion object {
        private val MAGIC = byteArrayOf('V'.code.toByte(), 'J'.code.toByte(), 'R'.code.toByte(), '1'.code.toByte())
        private val HEADER_ASSOCIATED_DATA = "veyarc:recording:header:v1".encodeToByteArray()
        private const val FRAME_LENGTH_BYTES = Int.SIZE_BYTES
        private const val MAX_FRAME_PAYLOAD_BYTES = 4 * 1024 * 1024

        fun open(
            file: File,
            header: RecordingJournalHeader,
            codec: AesGcmFileCodec,
            maxEncryptedBytes: Long = DEFAULT_MAX_RECORDING_JOURNAL_BYTES,
        ): EncryptedRecordingJournalStore {
            require(maxEncryptedBytes in (MAGIC.size + FRAME_LENGTH_BYTES + 1).toLong()..Int.MAX_VALUE) {
                "Encrypted recording journal size limit is invalid"
            }
            file.parentFile?.mkdirs()
            file.parentFile?.let { parent ->
                check(parent.isDirectory) { "Recording journal directory is unavailable" }
            }

            val access = RandomAccessFile(file, "rw")
            try {
                if (access.length() == 0L) {
                    writeHeader(access, codec, header, maxEncryptedBytes)
                    access.fd.sync()
                    return EncryptedRecordingJournalStore(
                        file = file,
                        codec = codec,
                        maxEncryptedBytes = maxEncryptedBytes,
                        access = access,
                        header = header,
                        events = mutableListOf(),
                        cursor = RecordingJournalCursor(),
                        nextSequence = 1L,
                    )
                }

                val parsed = readExisting(access, codec, header, maxEncryptedBytes)
                access.setLength(parsed.validPrefixBytes.toLong())
                access.seek(parsed.validPrefixBytes.toLong())
                if (parsed.header == null) {
                    // A crash during the very first header frame leaves no recoverable
                    // session identity. The caller supplied the identity, so safely restart
                    // that empty journal rather than accepting an ambiguous file.
                    access.setLength(MAGIC.size.toLong())
                    access.seek(MAGIC.size.toLong())
                    writeHeaderFrame(access, codec, header, maxEncryptedBytes)
                    access.fd.sync()
                    return EncryptedRecordingJournalStore(
                        file = file,
                        codec = codec,
                        maxEncryptedBytes = maxEncryptedBytes,
                        access = access,
                        header = header,
                        events = mutableListOf(),
                        cursor = RecordingJournalCursor(),
                        nextSequence = 1L,
                    )
                }
                require(parsed.header == header) { "Recording journal identity does not match its header" }
                return EncryptedRecordingJournalStore(
                    file = file,
                    codec = codec,
                    maxEncryptedBytes = maxEncryptedBytes,
                    access = access,
                    header = parsed.header,
                    events = parsed.events.toMutableList(),
                    cursor = parsed.cursor,
                    nextSequence = parsed.nextSequence,
                )
            } catch (error: Exception) {
                runCatching { access.close() }
                throw error
            }
        }

        private fun headerLine(header: RecordingJournalHeader): RecordingJournalLine = RecordingJournalLine.Header(header)

        private fun writeHeader(
            access: RandomAccessFile,
            codec: AesGcmFileCodec,
            header: RecordingJournalHeader,
            maxEncryptedBytes: Long,
        ) {
            access.write(MAGIC)
            writeHeaderFrame(access, codec, header, maxEncryptedBytes)
        }

        private fun writeHeaderFrame(
            access: RandomAccessFile,
            codec: AesGcmFileCodec,
            header: RecordingJournalHeader,
            maxEncryptedBytes: Long,
        ) {
            val headerFrame = frame(
                codec.encrypt(
                    RecordingJournalCodec.encodeRecord(headerLine(header)),
                    HEADER_ASSOCIATED_DATA,
                ),
            )
            require(access.length() + headerFrame.size <= maxEncryptedBytes) {
                "Encrypted recording journal exceeds its size limit"
            }
            access.write(headerFrame)
        }

        private fun frame(encryptedPayload: ByteArray): ByteArray {
            require(encryptedPayload.size <= MAX_FRAME_PAYLOAD_BYTES) {
                "Encrypted recording journal frame is too large"
            }
            return ByteBuffer.allocate(FRAME_LENGTH_BYTES + encryptedPayload.size)
                .putInt(encryptedPayload.size)
                .put(encryptedPayload)
                .array()
        }

        private fun appendFrame(access: RandomAccessFile, encryptedPayload: ByteArray) {
            access.write(frame(encryptedPayload))
        }

        private fun headerAssociatedData(): ByteArray = HEADER_ASSOCIATED_DATA

        private fun eventAssociatedData(sessionId: String, sequence: Long): ByteArray = "veyarc:recording:$sessionId:event:$sequence".encodeToByteArray()

        private fun readExisting(
            access: RandomAccessFile,
            codec: AesGcmFileCodec,
            expectedHeader: RecordingJournalHeader,
            maxEncryptedBytes: Long,
        ): ParsedJournal {
            require(access.length() <= maxEncryptedBytes) {
                "Encrypted recording journal exceeds its size limit"
            }
            require(access.length() <= Int.MAX_VALUE) { "Encrypted recording journal is too large to read" }
            val bytes = ByteArray(access.length().toInt())
            access.seek(0L)
            access.readFully(bytes)
            require(bytes.size >= MAGIC.size && bytes.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) {
                "Encrypted recording journal format is invalid"
            }

            var offset = MAGIC.size
            var sequence = 0L
            var header: RecordingJournalHeader? = null
            var cursor = RecordingJournalCursor()
            val events = mutableListOf<RecordingJournalLine>()
            var validPrefixBytes = offset

            while (offset < bytes.size) {
                val remainingBytes = bytes.size - offset
                if (remainingBytes < FRAME_LENGTH_BYTES) break
                val payloadLength = ByteBuffer.wrap(bytes, offset, FRAME_LENGTH_BYTES).int
                if (payloadLength !in 1..MAX_FRAME_PAYLOAD_BYTES) {
                    // A crash can leave only the four-byte length prefix. There is no
                    // complete frame to authenticate, so truncate that final prefix.
                    if (remainingBytes == FRAME_LENGTH_BYTES) break
                    error("Encrypted recording journal frame length is invalid")
                }
                val frameEnd = offset + FRAME_LENGTH_BYTES + payloadLength
                if (frameEnd > bytes.size) break
                val encryptedPayload = bytes.copyOfRange(offset + FRAME_LENGTH_BYTES, frameEnd)
                val associatedData = if (sequence == 0L) {
                    headerAssociatedData()
                } else {
                    val sessionId = requireNotNull(header).sessionId
                    eventAssociatedData(sessionId, sequence)
                }
                val record = RecordingJournalCodec.decodeLine(codec.decrypt(encryptedPayload, associatedData))
                if (sequence == 0L) {
                    val parsedHeader = record as? RecordingJournalLine.Header
                        ?: error("Encrypted recording journal does not start with a header")
                    require(parsedHeader.header == expectedHeader) {
                        "Recording journal identity does not match its header"
                    }
                    header = parsedHeader.header
                } else {
                    require(record !is RecordingJournalLine.Header) {
                        "Encrypted recording journal contains a second header"
                    }
                    cursor = cursor.accept(record)
                    events += record
                }
                offset = frameEnd
                validPrefixBytes = offset
                sequence += 1L
            }

            return ParsedJournal(
                header = header,
                events = events,
                cursor = cursor,
                nextSequence = sequence,
                validPrefixBytes = validPrefixBytes,
            )
        }

        private data class ParsedJournal(
            val header: RecordingJournalHeader?,
            val events: List<RecordingJournalLine>,
            val cursor: RecordingJournalCursor,
            val nextSequence: Long,
            val validPrefixBytes: Int,
        )
    }
}

package com.veyarc.data.storage

import com.veyarc.model.DisplayOrientation
import com.veyarc.model.PackageName
import com.veyarc.model.ViewportSpec
import com.veyarc.recording.RecordingJournalHeader
import com.veyarc.recording.RecordingJournalLine
import com.veyarc.recording.RecordingTouchAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import javax.crypto.spec.SecretKeySpec

class EncryptedRecordingJournalStoreTest {
    private val keySource = SecretKeySource {
        SecretKeySpec(ByteArray(32) { index -> (index + 11).toByte() }, "AES")
    }

    @Test
    fun persistsEncryptedRecordsAndReopensForAppend() {
        val directory = temporaryDirectory()
        val file = File(directory, "recording.rjenc")
        val header = header()
        val codec = AesGcmFileCodec(keySource)
        try {
            EncryptedRecordingJournalStore.open(file, header, codec).use { journal ->
                journal.appendForegroundPackage(0L, PackageName("com.example.target"))
                journal.appendTouch(10L, RecordingTouchAction.DOWN, 100.0, 200.0)
                journal.appendTouch(30L, RecordingTouchAction.UP, 100.0, 200.0)
            }

            val encryptedBytes = file.readBytes()
            assertTrue(!encryptedBytes.decodeToString().contains("com.example.target"))

            EncryptedRecordingJournalStore.open(file, header, codec).use { journal ->
                assertEquals(3, journal.snapshot().events.size)
                journal.appendForegroundPackage(50L, null)
                assertEquals(4, journal.snapshot().events.size)
            }

            EncryptedRecordingJournalStore.open(file, header, codec).use { journal ->
                assertEquals(
                    RecordingJournalLine.ForegroundPackage(50L, null),
                    journal.snapshot().events.last(),
                )
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun truncatesAnIncompleteFinalFrameBeforeAppending() {
        val directory = temporaryDirectory()
        val file = File(directory, "recording.rjenc")
        val header = header()
        val codec = AesGcmFileCodec(keySource)
        try {
            EncryptedRecordingJournalStore.open(file, header, codec).use { journal ->
                journal.appendForegroundPackage(0L, PackageName("com.example.target"))
            }
            val completeLength = file.length()
            FileOutputStream(file, true).use { output ->
                output.write(byteArrayOf(0, 0, 0, 32, 1, 2, 3))
                output.fd.sync()
            }
            assertTrue(file.length() > completeLength)

            EncryptedRecordingJournalStore.open(file, header, codec).use { journal ->
                assertEquals(1, journal.snapshot().events.size)
                journal.appendForegroundPackage(20L, null)
            }

            EncryptedRecordingJournalStore.open(file, header, codec).use { journal ->
                assertEquals(2, journal.snapshot().events.size)
                assertEquals(RecordingJournalLine.ForegroundPackage(20L, null), journal.snapshot().events.last())
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun header(): RecordingJournalHeader = RecordingJournalHeader(
        sessionId = "00000000-0000-4000-8000-000000000402",
        startedAtEpochMillis = 2_000L,
        viewport = ViewportSpec(
            recordedWidthPx = 1080,
            recordedHeightPx = 2392,
            recordedDensityDpi = 420,
            recordedOrientation = DisplayOrientation.PORTRAIT,
        ),
    )

    private fun temporaryDirectory(): File = File.createTempFile("veyarc-recording", "test").apply {
        delete()
        mkdirs()
    }
}

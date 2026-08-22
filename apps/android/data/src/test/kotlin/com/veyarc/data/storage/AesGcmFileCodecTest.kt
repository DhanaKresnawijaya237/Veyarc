package com.veyarc.data.storage

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import javax.crypto.spec.SecretKeySpec

class AesGcmFileCodecTest {
    private val keySource = SecretKeySource {
        SecretKeySpec(ByteArray(32) { index -> (index + 1).toByte() }, "AES")
    }

    @Test
    fun roundTripsAuthenticatedPayloads() {
        val codec = AesGcmFileCodec(keySource)
        val plaintext = "encrypted workflow".encodeToByteArray()
        val associatedData = "workflow:example:revision:1".encodeToByteArray()

        val encrypted = codec.encrypt(plaintext, associatedData)

        assertArrayEquals(plaintext, codec.decrypt(encrypted, associatedData))
    }

    @Test
    fun rejectsTamperedCiphertextAndWrongAssociatedData() {
        val codec = AesGcmFileCodec(keySource)
        val encrypted = codec.encrypt("workflow".encodeToByteArray(), "identity".encodeToByteArray())
        encrypted[encrypted.lastIndex] = (encrypted.last().toInt() xor 0x01).toByte()

        assertStorageCryptoFailure { codec.decrypt(encrypted, "identity".encodeToByteArray()) }
        assertStorageCryptoFailure {
            codec.decrypt(
                codec.encrypt("workflow".encodeToByteArray(), "identity".encodeToByteArray()),
                "other".encodeToByteArray(),
            )
        }
    }

    @Test
    fun writesAndReadsAtomicBoundedFiles() {
        val directory = File.createTempFile("veyarc-storage", "test").apply {
            delete()
            mkdirs()
        }
        try {
            val store = EncryptedFileStore(directory, AesGcmFileCodec(keySource))
            val fileName = "workflow-example-revision-1.wfenc"
            val plaintext = "workflow bundle".encodeToByteArray()

            val byteLength = store.writeNew(fileName, plaintext, "identity".encodeToByteArray())

            assertEquals(byteLength, File(directory, fileName).length())
            assertArrayEquals(plaintext, store.read(fileName, "identity".encodeToByteArray()))
            store.delete(fileName)
            assertEquals(false, File(directory, fileName).exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun assertStorageCryptoFailure(block: () -> Unit) {
        try {
            block()
            fail("Expected storage crypto failure")
        } catch (_: StorageCryptoException) {
            // The payload or identity was tampered with, which must be indistinguishable here.
        }
    }
}

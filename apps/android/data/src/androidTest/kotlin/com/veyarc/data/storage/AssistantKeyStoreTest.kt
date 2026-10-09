package com.veyarc.data.storage

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import javax.crypto.spec.SecretKeySpec

@RunWith(AndroidJUnit4::class)
class AssistantKeyStoreTest {
    @Test
    fun encryptionReplacementFailureRecoveryAndRemoval() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "assistant-key-test-${UUID.randomUUID()}")
        val file = File(directory, "key.enc")
        var unavailable = false
        val codec = AesGcmFileCodec(
            SecretKeySource {
                check(!unavailable) { "sensitive exception detail" }
                SecretKeySpec(ByteArray(32) { 42 }, "AES")
            },
        )
        val store = AssistantKeyStore(file, codec)
        val first = "fixture-key-not-a-real-provider-secret"
        try {
            assertFalse(store.hasKey())
            assertNull(store.readForRequest())
            store.save(first)
            assertEquals(first, store.readForRequest())
            assertTrue(AssistantKeyStore(file, codec).hasKey())
            assertFalse(file.readBytes().toString(Charsets.ISO_8859_1).contains(first))
            val committed = file.readBytes()
            unavailable = true
            val failure = runCatching { store.save("fixture-replacement") }.exceptionOrNull()!!
            assertNull(failure.cause)
            assertFalse(failure.toString().contains("sensitive"))
            assertArrayEquals(committed, file.readBytes())
            unavailable = false
            assertTrue(store.hasKey())
            store.save("fixture-replacement")
            assertEquals("fixture-replacement", store.readForRequest())
            assertEquals("fixture-replacement", codec.decrypt(file.readBytes(), "veyarc:assistant:deepseek:key:v1".toByteArray()).toString(Charsets.UTF_8))
            assertTrue(runCatching { codec.decrypt(file.readBytes(), "wrong-provider".toByteArray()) }.isFailure)
            File("${file.path}.bak").writeBytes(committed)
            file.writeBytes(byteArrayOf(1))
            assertTrue(store.hasKey()) // AtomicFile restores the last committed backup.
            assertArrayEquals(committed, file.readBytes())
            file.writeBytes(ByteArray(5000))
            assertTrue(runCatching { store.hasKey() }.isFailure)
            assertTrue(runCatching { store.readForRequest() }.isFailure)
            store.remove() // Corrupt/unreadable credentials can be removed without decryption.
            assertFalse(store.hasKey())
            assertTrue(directory.listFiles()!!.isEmpty())
            listOf("", "line\nbreak", "has space", "é", "x".repeat(4097)).forEach {
                assertFalse(AssistantKeyStore.isValidEntry(it))
                assertTrue(runCatching { store.save(it) }.isFailure)
            }
            assertTrue(AssistantKeyStore.isValidEntry("x".repeat(4096)))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun androidKeystoreSurvivesNewOwnerWithoutExposingPlaintext() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "assistant-keystore-test-${UUID.randomUUID()}")
        val alias = "veyarc.test.assistant.${UUID.randomUUID()}"
        val file = File(directory, "key.enc")
        try {
            AssistantKeyStore(file, AesGcmFileCodec(AndroidKeystoreKeySource(alias))).save("fixture-device-keystore-key")
            val restored = AssistantKeyStore(file, AesGcmFileCodec(AndroidKeystoreKeySource(alias)))
            assertTrue(restored.hasKey())
            restored.remove()
            assertFalse(restored.hasKey())
        } finally {
            directory.deleteRecursively()
            java.security.KeyStore.getInstance("AndroidKeyStore").apply {
                load(null)
                deleteEntry(alias)
            }
        }
    }
}

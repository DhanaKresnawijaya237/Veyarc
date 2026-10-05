package com.veyarc.data.storage

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID
import javax.crypto.spec.SecretKeySpec

class AssistantConnectionStoreTest {
    @Test fun migrationIsIdempotentAndFailedWritesKeepProfilesAndKeysIsolated() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "connections-${UUID.randomUUID()}")
        var unavailable = false
        val codec = AesGcmFileCodec(
            SecretKeySource {
                check(!unavailable)
                SecretKeySpec(ByteArray(32) { 42 }, "AES")
            },
        )
        val file = File(directory, "connections.enc")
        val store = AssistantConnectionStore(file, codec)
        try {
            val initial = store.loadOrMigrate("deepseek-v4-pro", "MAX")
            assertEquals("deepseek-v4-pro", initial.connections.single().defaultModelId)
            assertEquals(initial, store.loadOrMigrate())
            val other = AssistantConnection(UUID.randomUUID().toString(), "Private server", "https://example.org/v1", models = listOf(AssistantModelConfig("my-model")), defaultModelId = "my-model")
            val updated = initial.copy(connections = initial.connections + other, defaultConnectionId = other.id)
            store.save(updated)
            assertFalse(file.readBytes().toString(Charsets.ISO_8859_1).contains("Private server"))
            assertEquals(updated, AssistantConnectionStore(file, codec).loadOrMigrate())
            unavailable = true
            assertTrue(runCatching { store.save(initial) }.isFailure)
            unavailable = false
            assertEquals(updated, store.loadOrMigrate())
            val a = AssistantKeyStore(File(directory, "a.enc"), codec, "profile-a".toByteArray())
            val b = AssistantKeyStore(File(directory, "b.enc"), codec, "profile-b".toByteArray())
            a.save("fixture-a")
            b.save("fixture-b")
            assertEquals("fixture-a", a.readForRequest())
            assertEquals("fixture-b", b.readForRequest())
            a.remove()
            assertFalse(a.hasKey())
            assertTrue(b.hasKey())
            assertTrue(runCatching { AssistantKeyStore(File(directory, "b.enc"), codec, "profile-a".toByteArray()).readForRequest() }.isFailure)
            file.writeBytes(byteArrayOf(1))
            assertTrue(runCatching { store.loadOrMigrate() }.isFailure)
            assertTrue(b.hasKey())
        } finally {
            directory.deleteRecursively()
        }
    }
}

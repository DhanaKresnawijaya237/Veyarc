package com.veyarc.data.storage

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import javax.crypto.spec.SecretKeySpec

@RunWith(AndroidJUnit4::class)
class ChatRepositoryTest {
    @Test
    fun largeHistoryAndUnavailableDirectoryPreserveChatsAndWorkflow() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, VeyarcDatabase::class.java).allowMainThreadQueries().build()
        val root = File(context.cacheDir, "chat-capacity-${UUID.randomUUID()}").apply { mkdirs() }
        val directory = File(root, "history")
        val displaced = File(root, "displaced-history")
        val files = EncryptedFileStore(directory, AesGcmFileCodec(SecretKeySource { SecretKeySpec(ByteArray(32) { 9 }, "AES") }))
        val repository = ChatRepository(database, files)
        val workflow = WorkflowEntity("workflow", "Preserve this workflow", 1, 1, 1)
        val chat = StoredChat(UUID.randomUUID().toString(), "workflow", 1, 1, null, "EMPTY", "last saved chat".toByteArray())
        try {
            database.workflowDao().upsertWorkflow(workflow)
            repository.save(chat, create = true)
            repeat(25) { repository.save(chat.copy(id = UUID.randomUUID().toString(), workflowId = null, payload = ByteArray(300 * 1024)), create = true) }
            val before = database.chatDao().list()
            assertEquals(26, repository.load().size)
            assertTrue(before.sumOf { it.encryptedByteLength } > 4 * 1024 * 1024)
            assertEquals(before.map { it.encryptedFileName }.toSet(), directory.listFiles()!!.map { it.name }.toSet())
            assertArrayEquals(chat.payload, repository.load().single { it.id == chat.id }.payload)
            // Deterministic write-path failure, without exhausting the emulator's real disk.
            assertTrue(directory.renameTo(displaced))
            directory.writeText("not a directory")
            assertTrue(runCatching { repository.save(chat.copy(payload = "replacement".toByteArray()), create = false) }.isFailure)
            assertEquals(before, database.chatDao().list())
            assertTrue(directory.delete())
            assertTrue(displaced.renameTo(directory))
            assertArrayEquals(chat.payload, repository.load().single { it.id == chat.id }.payload)
            val deleteIds = before.filter { it.conversationId != chat.id }.take(3).map { it.conversationId }.toSet()
            repository.delete(deleteIds)
            assertEquals(23, repository.load().size)
            assertTrue(repository.load().none { it.id in deleteIds })
            assertArrayEquals(chat.payload, repository.load().single { it.id == chat.id }.payload)
            assertEquals(database.chatDao().list().map { it.encryptedFileName }.toSet(), directory.listFiles()!!.map { it.name }.toSet())
            repository.clear("workflow")
            assertEquals(22, repository.load().size)
            assertTrue(repository.load().none { it.workflowId == "workflow" })
            assertEquals(workflow, database.workflowDao().findWorkflow(workflow.workflowId))
            assertEquals(database.chatDao().list().map { it.encryptedFileName }.toSet(), directory.listFiles()!!.map { it.name }.toSet())
            repository.clear()
            assertTrue(repository.load().isEmpty())
            assertEquals(workflow, database.workflowDao().findWorkflow(workflow.workflowId))
        } finally {
            database.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun replacementFailureOrphansAndMissingPayloadPreserveTheLastCommittedChat() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, VeyarcDatabase::class.java).allowMainThreadQueries().build()
        val directory = File(context.cacheDir, "chat-test-${UUID.randomUUID()}")
        var failEncryption = false
        val files = EncryptedFileStore(
            directory,
            AesGcmFileCodec(
                SecretKeySource {
                    check(!failEncryption) { "Simulated key failure" }
                    SecretKeySpec(ByteArray(32) { 7 }, "AES")
                },
            ),
            ChatRepository.MAX_PAYLOAD_BYTES + 128L,
        )
        val repository = ChatRepository(database, files)
        val chat = StoredChat(UUID.randomUUID().toString(), null, 1, 2, null, "EMPTY", "private prompt and title".toByteArray())
        try {
            repository.save(chat, create = true)
            assertFalse(directory.listFiles()!!.single().readBytes().toString(Charsets.ISO_8859_1).contains("private prompt"))
            failEncryption = true
            assertTrue(runCatching { repository.save(chat.copy(payload = "replacement".toByteArray()), create = false) }.isFailure)
            failEncryption = false
            assertArrayEquals(chat.payload, repository.load().single().payload)
            files.writeNew("orphan.wfenc", byteArrayOf(1), byteArrayOf(2))
            repository.load()
            assertFalse(files.exists("orphan.wfenc"))
            val replacement = chat.copy(payload = "replacement".toByteArray(), updatedAtMs = 3)
            repository.save(replacement, create = false)
            assertEquals(1, directory.listFiles()!!.size)
            assertArrayEquals(replacement.payload, repository.load().single().payload)
            assertTrue(runCatching { repository.save(chat.copy(payload = ByteArray(ChatRepository.MAX_PAYLOAD_BYTES + 1)), create = false) }.isFailure)
            val row = database.chatDao().find(chat.id)!!
            files.delete(row.encryptedFileName)
            assertTrue(runCatching { repository.load() }.isFailure)
            assertEquals(1, database.chatDao().list().size)
            repository.clear()
            assertTrue(repository.load().isEmpty())
        } finally {
            database.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun workflowDeletionCascadesChatsAndLateUpdatesCannotRecreateThem() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, VeyarcDatabase::class.java).allowMainThreadQueries().build()
        val directory = File(context.cacheDir, "chat-delete-test-${UUID.randomUUID()}")
        val files = EncryptedFileStore(directory, AesGcmFileCodec(SecretKeySource { SecretKeySpec(ByteArray(32) { 3 }, "AES") }))
        val repository = ChatRepository(database, files)
        try {
            database.workflowDao().upsertWorkflow(WorkflowEntity("workflow", "Existing", 1, 1, 1))
            val chat = StoredChat(UUID.randomUUID().toString(), "workflow", 1, 1, null, "EMPTY", byteArrayOf(1))
            repository.save(chat, create = true)
            database.workflowDao().deleteWorkflow("workflow")
            assertTrue(repository.load().isEmpty())
            assertTrue(directory.listFiles().orEmpty().isEmpty())
            assertTrue(runCatching { repository.save(chat, create = false) }.isFailure)
            repeat(25) {
                repository.save(chat.copy(id = UUID.randomUUID().toString(), workflowId = null), create = true)
            }
            repository.save(chat.copy(workflowId = null), create = true)
            assertEquals(26, repository.load().size)
        } finally {
            database.close()
            directory.deleteRecursively()
        }
    }
}

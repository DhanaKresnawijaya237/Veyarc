package com.veyarc.data.storage

import android.content.Context
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import java.io.File
import java.util.UUID

@Entity(
    tableName = "chat_conversations",
    foreignKeys = [ForeignKey(entity = WorkflowEntity::class, parentColumns = ["workflowId"], childColumns = ["savedWorkflowId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("savedWorkflowId"), Index(value = ["encryptedFileName"], unique = true)],
)
internal data class ChatEntity(
    @PrimaryKey val conversationId: String,
    val scopeId: String?,
    val savedWorkflowId: String?,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val requestId: String?,
    val status: String,
    val encryptedFileName: String,
    val encryptedByteLength: Long,
)

@Dao
internal interface ChatDao {
    @Query("SELECT * FROM chat_conversations ORDER BY createdAtMs, conversationId")
    fun list(): List<ChatEntity>

    @Query("SELECT * FROM chat_conversations WHERE conversationId = :id")
    fun find(id: String): ChatEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun put(chat: ChatEntity)

    @Query("DELETE FROM chat_conversations WHERE conversationId = :id")
    fun delete(id: String)

    @Query("DELETE FROM chat_conversations WHERE scopeId = :workflowId")
    fun deleteForWorkflow(workflowId: String)

    @Query("DELETE FROM chat_conversations")
    fun clear()
}

data class StoredChat(
    val id: String,
    val workflowId: String?,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val requestId: String?,
    val status: String,
    val payload: ByteArray,
)

/** Opaque encrypted chat payloads. This repository never reads or modifies workflow bodies. */
class ChatRepository internal constructor(private val database: VeyarcDatabase, private val files: EncryptedFileStore) {
    constructor(context: Context) : this(VeyarcDatabase.create(context), createFiles(context))

    fun load(): List<StoredChat> = synchronized(storageLock) {
        collectGarbage()
        val rows = database.chatDao().list()
        require(rows.size <= MAX_CHATS && rows.sumOf { it.encryptedByteLength } <= MAX_TOTAL_BYTES)
        rows.map { row ->
            StoredChat(row.conversationId, row.scopeId, row.createdAtMs, row.updatedAtMs, row.requestId, row.status, files.read(row.encryptedFileName, associatedData(row.conversationId)))
        }
    }

    fun save(chat: StoredChat, create: Boolean) = synchronized(storageLock) {
        collectGarbage()
        require(UUID.fromString(chat.id).toString() == chat.id)
        require(chat.createdAtMs >= 0 && chat.updatedAtMs >= chat.createdAtMs)
        require(chat.payload.size in 1..MAX_PAYLOAD_BYTES)
        require(chat.workflowId == null || chat.workflowId.length in 1..128)
        require(chat.status in setOf("EMPTY", "RESPONDING", "COMPLETE", "STOPPED", "INTERRUPTED", "ERROR"))
        chat.requestId?.let { require(UUID.fromString(it).toString() == it) }
        val name = "chat-${UUID.randomUUID()}.wfenc"
        var committed = false
        var previous: ChatEntity? = null
        try {
            database.runInTransaction {
                val dao = database.chatDao()
                previous = dao.find(chat.id)
                check(if (create) previous == null else previous != null) { "Chat was removed or changed. Reload history." }
                val rows = dao.list()
                check(!create || rows.size < MAX_CHATS) { "Chat history limit reached" }
                previous?.let { check(it.scopeId == chat.workflowId && it.createdAtMs == chat.createdAtMs) }
                val length = files.writeNew(name, chat.payload, associatedData(chat.id))
                check(rows.sumOf { it.encryptedByteLength } - (previous?.encryptedByteLength ?: 0) + length <= MAX_TOTAL_BYTES) { "Chat storage limit reached" }
                val savedWorkflow = chat.workflowId?.takeIf { database.workflowDao().findWorkflow(it) != null }
                dao.put(ChatEntity(chat.id, chat.workflowId, savedWorkflow, chat.createdAtMs, chat.updatedAtMs, chat.requestId, chat.status, name, length))
            }
            committed = true
        } finally {
            if (!committed) runCatching { files.delete(name) }
        }
        // A crash here leaves an orphan, never a row pointing to a deleted previous payload.
        previous?.let { runCatching { files.delete(it.encryptedFileName) } }
    }

    fun delete(id: String) = synchronized(storageLock) {
        val row = database.chatDao().find(id)
        database.chatDao().delete(id)
        row?.let { runCatching { files.delete(it.encryptedFileName) } }
        runCatching { collectGarbage() }
    }

    fun clear() = synchronized(storageLock) {
        database.chatDao().clear()
        runCatching { collectGarbage() }
    }

    private fun collectGarbage() {
        val referenced = database.chatDao().list().map { it.encryptedFileName }.toSet()
        files.listCommittedFileNames(200).filterNot(referenced::contains).forEach(files::delete)
        files.deleteStaleTemporaryFiles(200)
    }

    fun close() = synchronized(storageLock) { database.close() }

    companion object {
        const val MAX_CHATS = 20
        const val MAX_PAYLOAD_BYTES = 256 * 1024
        const val MAX_TOTAL_BYTES = 4L * 1024 * 1024
        private val storageLock = Any()
        private fun associatedData(id: String) = "veyarc:chat:conversation:$id:v1".toByteArray(Charsets.UTF_8)

        internal fun createFiles(context: Context) = EncryptedFileStore(
            File(context.applicationContext.filesDir, "assistant-history"),
            AesGcmFileCodec(AndroidKeystoreKeySource("veyarc.assistant.history.v1")),
            MAX_PAYLOAD_BYTES + 128L,
        )
    }
}

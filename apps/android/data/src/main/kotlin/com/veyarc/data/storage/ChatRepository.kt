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
    private var garbageCollected = false

    fun load(): List<StoredChat> = loadChanges(emptyMap()).second

    /** Unique immutable file names identify committed replacements, including writes by another owner. */
    fun loadChanges(knownVersions: Map<String, String>): Pair<Map<String, String>, List<StoredChat>> = synchronized(storageLock) {
        val rows = database.chatDao().list()
        collectGarbage(rows)
        val versions = rows.associate { it.conversationId to it.encryptedFileName }
        versions to rows.filter { knownVersions[it.conversationId] != it.encryptedFileName || !files.exists(it.encryptedFileName) }.map { row ->
            StoredChat(row.conversationId, row.scopeId, row.createdAtMs, row.updatedAtMs, row.requestId, row.status, files.read(row.encryptedFileName, associatedData(row.conversationId)))
        }
    }

    fun save(chat: StoredChat, create: Boolean) = synchronized(storageLock) {
        if (!garbageCollected) collectGarbage()
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
                previous?.let { check(it.scopeId == chat.workflowId && it.createdAtMs == chat.createdAtMs) }
                val length = files.writeNew(name, chat.payload, associatedData(chat.id))
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

    fun delete(id: String) = delete(setOf(id))

    fun delete(ids: Set<String>) = synchronized(storageLock) {
        val rows = database.runInTransaction<List<ChatEntity>> {
            val dao = database.chatDao()
            val found = ids.mapNotNull(dao::find)
            ids.forEach(dao::delete)
            found
        }
        rows.forEach { runCatching { files.delete(it.encryptedFileName) } }
        runCatching { collectGarbage() }
    }

    fun clear() = synchronized(storageLock) {
        database.chatDao().clear()
        runCatching { collectGarbage() }
    }

    fun clear(workflowId: String) = synchronized(storageLock) {
        delete(database.chatDao().list().filter { it.scopeId == workflowId }.map { it.conversationId }.toSet())
    }

    private fun collectGarbage(rows: List<ChatEntity> = database.chatDao().list()) {
        val referenced = rows.map { it.encryptedFileName }.toSet()
        files.listCommittedFileNames(Int.MAX_VALUE).filterNot(referenced::contains).forEach(files::delete)
        files.deleteStaleTemporaryFiles(Int.MAX_VALUE)
        garbageCollected = true
    }

    fun close() = synchronized(storageLock) { database.close() }

    companion object {
        // A single corrupt payload must not allocate unbounded memory. No chat-count or total quota.
        const val MAX_PAYLOAD_BYTES = 32 * 1024 * 1024
        private val storageLock = Any()
        private fun associatedData(id: String) = "veyarc:chat:conversation:$id:v1".toByteArray(Charsets.UTF_8)

        internal fun createFiles(context: Context) = EncryptedFileStore(
            File(context.applicationContext.filesDir, "assistant-history"),
            AesGcmFileCodec(AndroidKeystoreKeySource("veyarc.assistant.history.v1")),
            MAX_PAYLOAD_BYTES + 128L,
        )
    }
}

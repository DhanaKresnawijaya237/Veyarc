package com.veyarc.data.storage

import android.content.Context
import java.io.File
import java.util.UUID

/** Immutable attachment blobs. Chat payloads carry small references, not image bytes. */
class ChatAttachmentStore(context: Context) {
    private val files = EncryptedFileStore(
        File(context.applicationContext.filesDir, "assistant-attachments"),
        AesGcmFileCodec(AndroidKeystoreKeySource("veyarc.assistant.attachments.v1")),
        MAX_BYTES + 128L,
    )

    @Synchronized
    fun save(bytes: ByteArray): String {
        require(bytes.size in 1..MAX_BYTES)
        val id = UUID.randomUUID().toString()
        files.writeNew(name(id), bytes, aad(id))
        return id
    }

    @Synchronized
    fun read(id: String): ByteArray = files.read(name(id), aad(id))

    @Synchronized
    fun prune(referenced: Set<String>) {
        val names = referenced.mapTo(mutableSetOf(), ::name)
        files.listCommittedFileNames(Int.MAX_VALUE).filterNot(names::contains).forEach(files::delete)
        files.deleteStaleTemporaryFiles(Int.MAX_VALUE)
    }

    private fun name(id: String): String {
        require(UUID.fromString(id).toString() == id)
        return "attachment-$id.wfenc"
    }
    private fun aad(id: String) = "veyarc:chat:attachment:$id:v1".toByteArray()

    companion object {
        const val MAX_BYTES = 32 * 1024 * 1024
    }
}

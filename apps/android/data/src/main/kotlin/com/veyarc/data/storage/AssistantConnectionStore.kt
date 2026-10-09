package com.veyarc.data.storage

import android.content.Context
import android.util.AtomicFile
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/** Device-local connection metadata. Credentials live in separate, profile-bound encrypted files. */
class AssistantConnectionStore internal constructor(private val file: File, private val codec: AesGcmFileCodec) {
    constructor(context: Context) : this(
        File(context.noBackupFilesDir, "assistant/connections.enc"),
        AesGcmFileCodec(AndroidKeystoreKeySource("veyarc.assistant.connections.v1")),
    )

    /** Legacy DeepSeek keeps its key filename/alias, so migration never copies or deletes a secret. */
    fun loadOrMigrate(modelId: String = "deepseek-flash", effort: String = "OFF"): AssistantConnections = synchronized(lock) {
        if (file.exists() || File("${file.path}.bak").exists()) return@synchronized read()
        val initial = AssistantConnections(listOf(AssistantConnection.deepSeek(modelId, effort)), AssistantConnection.DEEPSEEK_ID)
        save(initial)
        read()
    }

    fun save(value: AssistantConnections) = synchronized(lock) {
        value.validate()
        val plain = json.encodeToString(value).toByteArray(Charsets.UTF_8)
        require(plain.size <= MAX_BYTES)
        try {
            val encrypted = codec.encrypt(plain, aad)
            check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
            val atomic = AtomicFile(file)
            val output = atomic.startWrite()
            try {
                output.write(encrypted)
                atomic.finishWrite(output)
            } catch (error: Exception) {
                atomic.failWrite(output)
                throw error
            }
        } catch (_: Exception) {
            throw IllegalStateException("Could not save AI connections. Try again.")
        } finally {
            plain.fill(0)
        }
    }

    private fun read(): AssistantConnections {
        try {
            val encrypted = AtomicFile(file).openRead().use { input ->
                val buffer = ByteArray(MAX_BYTES + 129)
                var count = 0
                while (count < buffer.size) {
                    val next = input.read(buffer, count, buffer.size - count)
                    if (next < 0) break
                    count += next
                }
                buffer.copyOf(count)
            }
            require(encrypted.size <= MAX_BYTES + 128)
            val plain = codec.decrypt(encrypted, aad)
            return try {
                json.decodeFromString<AssistantConnections>(plain.toString(Charsets.UTF_8)).also { it.validate() }
            } finally {
                plain.fill(0)
            }
        } catch (_: Exception) {
            throw IllegalStateException("Saved AI connections could not be read. Your keys and chats have been kept.")
        }
    }

    companion object {
        private val lock = Any()
        private val json = Json { encodeDefaults = true }
        private val aad = "veyarc:assistant:connections:v1".toByteArray(Charsets.UTF_8)
        private const val MAX_BYTES = 512 * 1024
    }
}

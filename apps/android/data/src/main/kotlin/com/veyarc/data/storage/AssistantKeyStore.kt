package com.veyarc.data.storage

import android.content.Context
import android.util.AtomicFile
import java.io.File

/** One provider credential, separate from workflow/chat storage and exports. No network access. */
class AssistantKeyStore internal constructor(private val file: File, private val codec: AesGcmFileCodec) {
    constructor(context: Context) : this(
        File(context.noBackupFilesDir, "assistant/deepseek-key.enc"),
        AesGcmFileCodec(AndroidKeystoreKeySource("veyarc.assistant.deepseek.v1")),
    )

    fun hasKey(): Boolean = synchronized(lock) {
        val bytes = read() ?: return@synchronized false
        bytes.fill(0)
        true
    }

    fun save(key: String) = synchronized(lock) {
        require(isValidEntry(key)) { "Enter a key of 1 to 4096 visible ASCII characters without spaces." }
        val plain = key.toByteArray(Charsets.UTF_8)
        try {
            val encrypted = codec.encrypt(plain, associatedData)
            check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
            val atomic = AtomicFile(file)
            val output = atomic.startWrite()
            try {
                output.write(encrypted)
                atomic.finishWrite(output)
                check(file.length() == encrypted.size.toLong() && file.readBytes().contentEquals(encrypted))
            } catch (error: Exception) {
                atomic.failWrite(output)
                throw error
            }
        } catch (_: Exception) {
            throw IllegalStateException("Could not save the API key. Check saved-key status or replace it.")
        } finally {
            plain.fill(0)
        }
    }

    fun remove() = synchronized(lock) {
        AtomicFile(file).delete()
        check(listOf(file, File("${file.path}.bak"), File("${file.path}.new")).none { it.exists() }) {
            "Could not remove the API key. Try again."
        }
    }

    private fun read(): ByteArray? {
        if (!file.exists() && !File("${file.path}.bak").exists()) return null
        try {
            val payload = AtomicFile(file).openRead().use { input ->
                val buffer = ByteArray(MAX_KEY_LENGTH + 129)
                var count = 0
                while (count < buffer.size) {
                    val read = input.read(buffer, count, buffer.size - count)
                    if (read < 0) break
                    count += read
                }
                check(count < buffer.size)
                buffer.copyOf(count)
            }
            val plain = codec.decrypt(payload, associatedData)
            if (plain.size !in 1..MAX_KEY_LENGTH || plain.any { it.toInt() !in 33..126 }) {
                plain.fill(0)
                error("Invalid stored key")
            }
            return plain
        } catch (_: Exception) {
            throw IllegalStateException("The stored API key cannot be read. Replace or remove it.")
        }
    }

    companion object {
        const val MAX_KEY_LENGTH = 4096
        fun isValidEntry(value: String): Boolean = value.length in 1..MAX_KEY_LENGTH && value.all { it.code in 33..126 }
        private val lock = Any()
        private val associatedData = "veyarc:assistant:deepseek:key:v1".toByteArray(Charsets.UTF_8)
    }
}

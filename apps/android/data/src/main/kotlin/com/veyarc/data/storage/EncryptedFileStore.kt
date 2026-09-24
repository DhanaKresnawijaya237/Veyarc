package com.veyarc.data.storage

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

fun interface SecretKeySource {
    fun get(): SecretKey
}

/** Gets or creates the AES key inside Android Keystore. The key never leaves Keystore. */
class AndroidKeystoreKeySource(
    private val alias: String = DEFAULT_ALIAS,
) : SecretKeySource {
    @Synchronized
    override fun get(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = keyStore.getEntry(alias, null) as? KeyStore.SecretKeyEntry
        if (existing != null) return existing.secretKey

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setKeySize(KEY_SIZE_BITS)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val DEFAULT_ALIAS = "veyarc.workflow.storage.v1"
        const val KEY_SIZE_BITS = 256
    }
}

/** Gets or creates the HMAC key used only for private, device-local content identities. */
class AndroidKeystoreHmacKeySource(
    private val alias: String = DEFAULT_ALIAS,
) : SecretKeySource {
    @Synchronized
    override fun get(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = keyStore.getEntry(alias, null) as? KeyStore.SecretKeyEntry
        if (existing != null) return existing.secretKey

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
            )
                .setKeySize(KEY_SIZE_BITS)
                .setDigests(KeyProperties.DIGEST_SHA256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val DEFAULT_ALIAS = "veyarc.asset-identity.v1"
        const val KEY_SIZE_BITS = 256
    }
}

class StorageCryptoException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** AES-GCM payload codec with a small versioned header and optional associated data. */
class AesGcmFileCodec(
    private val keySource: SecretKeySource,
) {
    fun encrypt(plaintext: ByteArray, associatedData: ByteArray = ByteArray(0)): ByteArray {
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, keySource.get())
            cipher.updateAAD(associatedData)
            val ciphertext = cipher.doFinal(plaintext)
            val iv = cipher.iv
            require(iv.size in MIN_IV_BYTES..MAX_IV_BYTES) { "Unexpected AES-GCM IV length" }

            return ByteArray(MAGIC.size + 2 + iv.size + ciphertext.size).also { output ->
                MAGIC.copyInto(output, destinationOffset = 0)
                output[MAGIC.size] = FORMAT_VERSION
                output[MAGIC.size + 1] = iv.size.toByte()
                iv.copyInto(output, destinationOffset = MAGIC.size + 2)
                ciphertext.copyInto(output, destinationOffset = MAGIC.size + 2 + iv.size)
            }
        } catch (error: Exception) {
            if (error is StorageCryptoException) throw error
            throw StorageCryptoException("Could not encrypt workflow data", error)
        }
    }

    fun decrypt(payload: ByteArray, associatedData: ByteArray = ByteArray(0)): ByteArray {
        try {
            val minimumSize = MAGIC.size + 2 + MIN_IV_BYTES + GCM_TAG_BYTES
            require(payload.size >= minimumSize) { "Encrypted payload is truncated" }
            require(payload.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) {
                "Encrypted payload has an unknown format"
            }
            require(payload[MAGIC.size] == FORMAT_VERSION) { "Encrypted payload version is unsupported" }

            val ivLength = payload[MAGIC.size + 1].toInt() and 0xFF
            require(ivLength in MIN_IV_BYTES..MAX_IV_BYTES) { "Encrypted payload IV length is invalid" }
            val ivStart = MAGIC.size + 2
            val ciphertextStart = ivStart + ivLength
            require(payload.size > ciphertextStart + GCM_TAG_BYTES) { "Encrypted payload has no ciphertext" }

            val iv = payload.copyOfRange(ivStart, ciphertextStart)
            val ciphertext = payload.copyOfRange(ciphertextStart, payload.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, keySource.get(), GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.updateAAD(associatedData)
            return cipher.doFinal(ciphertext)
        } catch (error: Exception) {
            if (error is StorageCryptoException) throw error
            throw StorageCryptoException("Could not decrypt workflow data", error)
        }
    }

    private companion object {
        val MAGIC = byteArrayOf('V'.code.toByte(), 'Y'.code.toByte(), 'A'.code.toByte(), '1'.code.toByte())
        const val FORMAT_VERSION: Byte = 1
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
        const val GCM_TAG_BYTES = GCM_TAG_BITS / 8
        const val MIN_IV_BYTES = 12
        const val MAX_IV_BYTES = 16
    }
}

/** Writes encrypted payloads as bounded, same-directory atomic files. */
class EncryptedFileStore(
    private val directory: File,
    private val codec: AesGcmFileCodec,
    private val maxEncryptedBytes: Long = DEFAULT_MAX_ENCRYPTED_BYTES,
) {
    init {
        require(maxEncryptedBytes > 0) { "maxEncryptedBytes must be positive" }
    }

    @Synchronized
    fun writeNew(fileName: String, plaintext: ByteArray, associatedData: ByteArray): Long {
        val target = resolveFile(fileName)
        require(!target.exists()) { "Encrypted file already exists" }
        val encrypted = codec.encrypt(plaintext, associatedData)
        checkSize(encrypted.size.toLong())
        directory.mkdirs()
        check(directory.isDirectory) { "Encrypted file directory is unavailable" }

        val temporary = File.createTempFile(".$fileName.", ".tmp", directory)
        try {
            FileOutputStream(temporary).use { output ->
                output.write(encrypted)
                output.fd.sync()
            }
            check(temporary.renameTo(target)) { "Could not commit encrypted workflow file" }
            return encrypted.size.toLong()
        } finally {
            temporary.delete()
        }
    }

    @Synchronized
    fun read(fileName: String, associatedData: ByteArray): ByteArray {
        val file = resolveFile(fileName)
        check(file.isFile) { "Encrypted workflow file is missing" }
        checkSize(file.length())
        val payload = FileInputStream(file).use { input -> input.readBytes() }
        checkSize(payload.size.toLong())
        return codec.decrypt(payload, associatedData)
    }

    @Synchronized
    fun delete(fileName: String) {
        val file = resolveFile(fileName)
        if (file.exists()) check(file.delete()) { "Could not remove encrypted workflow file" }
    }

    @Synchronized
    fun exists(fileName: String): Boolean = resolveFile(fileName).isFile

    @Synchronized
    internal fun listCommittedFileNames(limit: Int): List<String> {
        require(limit > 0) { "File listing limit must be positive" }
        return directory.listFiles()
            .orEmpty()
            .asSequence()
            .filter(File::isFile)
            .map(File::getName)
            .filter(FILE_NAME_PATTERN::matches)
            .sorted()
            .take(limit)
            .toList()
    }

    @Synchronized
    internal fun deleteStaleTemporaryFiles(limit: Int): Int {
        require(limit > 0) { "Temporary-file cleanup limit must be positive" }
        return directory.listFiles()
            .orEmpty()
            .asSequence()
            .filter { it.isFile && TEMP_FILE_NAME_PATTERN.matches(it.name) }
            .sortedBy(File::getName)
            .take(limit)
            .count { it.delete() }
    }

    private fun resolveFile(fileName: String): File {
        require(FILE_NAME_PATTERN.matches(fileName)) { "Invalid encrypted workflow file name" }
        val directoryPath = directory.absoluteFile.normalize()
        val file = File(directoryPath, fileName).absoluteFile.normalize()
        require(file.parentFile == directoryPath) { "Encrypted workflow file escaped its directory" }
        return file
    }

    private fun checkSize(size: Long) {
        require(size <= maxEncryptedBytes) { "Encrypted workflow file exceeds its size limit" }
    }

    private companion object {
        val FILE_NAME_PATTERN = Regex("[A-Za-z0-9_-]+\\.wfenc")
        val TEMP_FILE_NAME_PATTERN = Regex("\\.[A-Za-z0-9_-]+\\.wfenc\\.[A-Za-z0-9_-]+\\.tmp")
        const val DEFAULT_MAX_ENCRYPTED_BYTES = 34L * 1024L * 1024L
    }
}

package com.veyarc.data.storage

import com.veyarc.model.AssetId
import java.nio.ByteBuffer
import java.nio.ByteOrder

const val TEMPLATE_ASSET_KEY_ALIAS = "veyarc.template-assets.v1"

/**
 * A tightly packed RGBA template held only while an editor or evaluator needs it.
 *
 * The constructor takes a defensive copy so a caller cannot mutate the bytes after validation.
 * The encrypted representation is produced by [EncryptedTemplateAssetCodec]; callers should
 * clear their own temporary byte arrays as soon as the codec has copied them.
 */
class DecodedTemplate(
    val widthPx: Int,
    val heightPx: Int,
    rgbaBytes: ByteArray,
) {
    private val rgbaBytes: ByteArray = rgbaBytes.copyOf()

    init {
        require(widthPx > 0) { "Template width must be positive" }
        require(heightPx > 0) { "Template height must be positive" }
        require(widthPx.toLong() * heightPx <= MAX_TEMPLATE_PIXELS) {
            "Template is too large to store safely"
        }
        val expectedBytes = widthPx.toLong() * heightPx * BYTES_PER_PIXEL
        require(expectedBytes <= Int.MAX_VALUE && rgbaBytes.size.toLong() == expectedBytes) {
            "Template RGBA bytes do not match its dimensions"
        }
    }

    fun copyRgbaBytes(): ByteArray = rgbaBytes.copyOf()

    /** Clears the in-memory template after encryption or evaluation is complete. */
    fun clear() {
        rgbaBytes.fill(0)
    }

    companion object {
        const val MAX_TEMPLATE_PIXELS: Long = 3_000_000L
        private const val BYTES_PER_PIXEL = 4L
    }
}

/**
 * Encrypts and authenticates image-template payloads before they enter a workflow bundle.
 *
 * The workflow bundle is encrypted again by [EncryptedFileStore], but the asset remains encrypted
 * on its own so it cannot be accidentally exposed if a bundle is decoded for inspection. The
 * asset id is authenticated as associated data, preventing a valid template from being relabeled
 * and attached to a different condition.
 */
class EncryptedTemplateAssetCodec(
    private val keySource: SecretKeySource,
    private val maxEncryptedBytes: Long = DEFAULT_MAX_ENCRYPTED_BYTES,
) {
    init {
        require(maxEncryptedBytes > HEADER_BYTES + ENCRYPTION_OVERHEAD_BYTES) {
            "Template encrypted size limit is too small"
        }
    }

    fun encrypt(assetId: AssetId, template: DecodedTemplate): ByteArray {
        val plaintext = encode(template)
        try {
            val encrypted = codec.encrypt(plaintext, associatedData(assetId))
            check(encrypted.size.toLong() <= maxEncryptedBytes) {
                "Encrypted template exceeds its safety limit"
            }
            return encrypted
        } finally {
            plaintext.fill(0)
        }
    }

    fun decrypt(assetId: AssetId, encrypted: ByteArray): DecodedTemplate {
        require(encrypted.size.toLong() <= maxEncryptedBytes) {
            "Encrypted template exceeds its safety limit"
        }
        val plaintext = codec.decrypt(encrypted, associatedData(assetId))
        return try {
            decode(plaintext)
        } finally {
            plaintext.fill(0)
        }
    }

    /** Authenticates and validates a stored asset without retaining its decoded pixels. */
    fun verify(assetId: AssetId, encrypted: ByteArray) {
        decrypt(assetId, encrypted).clear()
    }

    private val codec = AesGcmFileCodec(keySource)

    private fun encode(template: DecodedTemplate): ByteArray {
        val bytes = template.copyRgbaBytes()
        return try {
            val total = HEADER_BYTES.toLong() + bytes.size
            require(total <= maxPlaintextBytes()) { "Template plaintext exceeds its safety limit" }
            ByteBuffer.allocate(total.toInt()).order(ByteOrder.BIG_ENDIAN).apply {
                put(MAGIC)
                put(FORMAT_VERSION)
                putInt(template.widthPx)
                putInt(template.heightPx)
                put(bytes)
            }.array()
        } finally {
            bytes.fill(0)
        }
    }

    private fun decode(plaintext: ByteArray): DecodedTemplate {
        require(plaintext.size >= HEADER_BYTES) { "Template payload is truncated" }
        val buffer = ByteBuffer.wrap(plaintext).order(ByteOrder.BIG_ENDIAN)
        val magic = ByteArray(MAGIC.size)
        buffer.get(magic)
        require(magic.contentEquals(MAGIC)) { "Template payload has an unknown format" }
        require(buffer.get() == FORMAT_VERSION) { "Template payload version is unsupported" }
        val width = buffer.int
        val height = buffer.int
        val templateBytes = ByteArray(buffer.remaining()).also(buffer::get)
        return try {
            DecodedTemplate(width, height, templateBytes)
        } finally {
            templateBytes.fill(0)
        }
    }

    private fun maxPlaintextBytes(): Long = maxEncryptedBytes - ENCRYPTION_OVERHEAD_BYTES

    private fun associatedData(assetId: AssetId): ByteArray = "template:${assetId.value}".encodeToByteArray()

    private companion object {
        val MAGIC = byteArrayOf('V'.code.toByte(), 'Y'.code.toByte(), 'T'.code.toByte(), '1'.code.toByte())
        const val FORMAT_VERSION: Byte = 1
        const val HEADER_BYTES = 4 + 1 + 4 + 4
        const val ENCRYPTION_OVERHEAD_BYTES = 4 + 2 + 16 + 16
        const val DEFAULT_MAX_ENCRYPTED_BYTES = 12L * 1024L * 1024L
    }
}

package com.veyarc.data.storage

import com.veyarc.model.AssetId
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream

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
    private var rgbaBytes: ByteArray = rgbaBytes.copyOf()

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

    /** Transfers the pixel buffer to another short-lived owner without making another full-size copy. */
    fun takeRgbaBytes(): ByteArray {
        check(rgbaBytes.isNotEmpty()) { "Template pixels have already been transferred" }
        return rgbaBytes.also { rgbaBytes = ByteArray(0) }
    }

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

    /** Encodes the canonical compressed payload for the independently encrypted shared blob store. */
    internal fun encodePayload(template: DecodedTemplate): ByteArray = encode(template)

    /** Decodes a canonical payload after the shared blob store has authenticated and decrypted it. */
    internal fun decodePayload(payload: ByteArray): DecodedTemplate {
        require(payload.size.toLong() <= maxPlaintextBytes()) { "Template plaintext exceeds its safety limit" }
        return decode(payload)
    }

    /** Authenticates an asset, reports its dimensions, and upgrades legacy raw payloads. */
    internal fun verify(assetId: AssetId, encrypted: ByteArray): VerifiedTemplateAsset {
        require(encrypted.size.toLong() <= maxEncryptedBytes) {
            "Encrypted template exceeds its safety limit"
        }
        val plaintext = codec.decrypt(encrypted, associatedData(assetId))
        return try {
            val header = readHeader(plaintext)
            if (header.version == FORMAT_VERSION) {
                VerifiedTemplateAsset(header.widthPx, header.heightPx, null)
            } else {
                val decoded = decode(plaintext)
                try {
                    VerifiedTemplateAsset(
                        widthPx = decoded.widthPx,
                        heightPx = decoded.heightPx,
                        upgradedBytes = encrypt(assetId, decoded),
                    )
                } finally {
                    decoded.clear()
                }
            }
        } finally {
            plaintext.fill(0)
        }
    }

    private val codec = AesGcmFileCodec(keySource)

    private fun encode(template: DecodedTemplate): ByteArray {
        val bytes = template.copyRgbaBytes()
        return try {
            val compressed = ByteArrayOutputStream().use { output ->
                DeflaterOutputStream(output).use { it.write(bytes) }
                output.toByteArray()
            }
            val total = HEADER_BYTES.toLong() + compressed.size
            require(total <= maxPlaintextBytes()) { "Template plaintext exceeds its safety limit" }
            ByteBuffer.allocate(total.toInt()).order(ByteOrder.BIG_ENDIAN).apply {
                put(MAGIC)
                put(FORMAT_VERSION)
                putInt(template.widthPx)
                putInt(template.heightPx)
                put(compressed)
            }.array()
        } finally {
            bytes.fill(0)
        }
    }

    private fun decode(plaintext: ByteArray): DecodedTemplate {
        val header = readHeader(plaintext)
        val buffer = ByteBuffer.wrap(plaintext).order(ByteOrder.BIG_ENDIAN).apply { position(HEADER_BYTES) }
        val storedBytes = ByteArray(buffer.remaining()).also(buffer::get)
        val templateBytes = if (header.version == LEGACY_FORMAT_VERSION) {
            storedBytes
        } else {
            try {
                inflateExactly(
                    compressed = storedBytes,
                    expectedBytes = Math.multiplyExact(Math.multiplyExact(header.widthPx, header.heightPx), 4),
                )
            } finally {
                storedBytes.fill(0)
            }
        }
        return try {
            DecodedTemplate(header.widthPx, header.heightPx, templateBytes)
        } finally {
            templateBytes.fill(0)
        }
    }

    private fun readHeader(plaintext: ByteArray): TemplateHeader {
        require(plaintext.size > HEADER_BYTES) { "Template payload is truncated" }
        val buffer = ByteBuffer.wrap(plaintext).order(ByteOrder.BIG_ENDIAN)
        val magic = ByteArray(MAGIC.size)
        buffer.get(magic)
        require(magic.contentEquals(MAGIC)) { "Template payload has an unknown format" }
        val version = buffer.get()
        require(version == LEGACY_FORMAT_VERSION || version == FORMAT_VERSION) {
            "Template payload version is unsupported"
        }
        val width = buffer.int
        val height = buffer.int
        require(width > 0 && height > 0 && width.toLong() * height <= DecodedTemplate.MAX_TEMPLATE_PIXELS) {
            "Template dimensions are invalid"
        }
        return TemplateHeader(version, width, height)
    }

    private fun maxPlaintextBytes(): Long = maxEncryptedBytes - ENCRYPTION_OVERHEAD_BYTES

    private fun inflateExactly(compressed: ByteArray, expectedBytes: Int): ByteArray {
        val output = ByteArray(expectedBytes)
        InflaterInputStream(ByteArrayInputStream(compressed)).use { input ->
            var offset = 0
            while (offset < output.size) {
                val count = input.read(output, offset, output.size - offset)
                require(count > 0) { "Template payload is truncated" }
                offset += count
            }
            require(input.read() == -1) { "Template payload expands beyond its declared dimensions" }
        }
        return output
    }

    private fun associatedData(assetId: AssetId): ByteArray = "template:${assetId.value}".encodeToByteArray()

    private companion object {
        val MAGIC = byteArrayOf('V'.code.toByte(), 'Y'.code.toByte(), 'T'.code.toByte(), '1'.code.toByte())
        const val LEGACY_FORMAT_VERSION: Byte = 1
        const val FORMAT_VERSION: Byte = 2
        const val HEADER_BYTES = 4 + 1 + 4 + 4
        const val ENCRYPTION_OVERHEAD_BYTES = 4 + 2 + 16 + 16
        const val DEFAULT_MAX_ENCRYPTED_BYTES = 16L * 1024L * 1024L
    }
}

private data class TemplateHeader(val version: Byte, val widthPx: Int, val heightPx: Int)

internal data class VerifiedTemplateAsset(
    val widthPx: Int,
    val heightPx: Int,
    val upgradedBytes: ByteArray?,
)

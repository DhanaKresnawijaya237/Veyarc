package com.veyarc.data.storage

import com.veyarc.model.AssetId
import com.veyarc.model.AssetKind
import com.veyarc.model.WorkflowDocument
import com.veyarc.model.WorkflowJson
import com.veyarc.model.WorkflowValidator
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import javax.crypto.Mac
import javax.crypto.SecretKey

private const val STORED_REVISION_FORMAT_VERSION = 1
private const val BLOB_KEY_HEX_LENGTH = 64
private const val HMAC_ALGORITHM = "HmacSHA256"
private val BLOB_KEY_PATTERN = Regex("[0-9a-f]{$BLOB_KEY_HEX_LENGTH}")
private val BLOB_IDENTITY_HEADER = byteArrayOf('V'.code.toByte(), 'Y'.code.toByte(), 'B'.code.toByte(), 1)

@Serializable
@JvmInline
internal value class AssetBlobKey(val value: String) {
    init {
        require(BLOB_KEY_PATTERN.matches(value)) { "Asset blob key must be a lowercase SHA-256 HMAC" }
    }
}

@Serializable
internal enum class StoredTemplateRole {
    @SerialName("fullScreen")
    FULL_SCREEN,

    @SerialName("croppedTarget")
    CROPPED_TARGET,
}

@Serializable
internal data class StoredRevisionAssetDescriptor(
    val assetId: AssetId,
    val kind: AssetKind,
    val blobKey: AssetBlobKey,
    val role: StoredTemplateRole,
    val mimeType: String,
    val widthPx: Int,
    val heightPx: Int,
) {
    init {
        require(kind == AssetKind.IMAGE_TEMPLATE) { "Stored workflow assets must be image templates" }
        require(mimeType.startsWith("image/") && mimeType.length <= 128) {
            "Stored workflow assets must use a valid image MIME type"
        }
        require(widthPx > 0 && heightPx > 0) { "Stored workflow asset dimensions must be positive" }
    }
}

@Serializable
internal data class StoredRevisionManifest(
    val formatVersion: Int = STORED_REVISION_FORMAT_VERSION,
    val document: WorkflowDocument,
    val producerVersion: String,
    val assets: List<StoredRevisionAssetDescriptor>,
) {
    init {
        require(formatVersion == STORED_REVISION_FORMAT_VERSION) {
            "Unsupported stored revision format version $formatVersion"
        }
        require(producerVersion.isNotBlank() && producerVersion.length <= 64) {
            "Producer version must be 1 to 64 characters"
        }
        val validationErrors = WorkflowValidator.validate(document)
        require(validationErrors.isEmpty()) {
            "Stored revision contains an invalid workflow: ${validationErrors.joinToString { it.code.name }}"
        }
        require(assets.map { it.assetId }.distinct().size == assets.size) {
            "Stored revision contains duplicate asset descriptors"
        }
        val declarations = document.assets.associateBy { it.assetId }
        require(
            declarations.size == assets.size && assets.all { descriptor ->
                declarations[descriptor.assetId]?.kind == descriptor.kind
            },
        ) {
            "Stored revision asset descriptors must match the workflow declarations"
        }
    }
}

internal object StoredRevisionManifestCodec {
    private const val MAX_MANIFEST_BYTES = 512 * 1024

    fun encode(manifest: StoredRevisionManifest): ByteArray {
        val canonical = manifest.copy(assets = manifest.assets.sortedBy { it.assetId.value })
        val encoded = WorkflowJson.encodeCanonical(StoredRevisionManifest.serializer(), canonical).encodeToByteArray()
        require(encoded.size <= MAX_MANIFEST_BYTES) { "Stored revision manifest exceeds its size limit" }
        return encoded
    }

    fun decode(bytes: ByteArray): StoredRevisionManifest {
        require(bytes.isNotEmpty() && bytes.size <= MAX_MANIFEST_BYTES) {
            "Stored revision manifest has an invalid size"
        }
        return try {
            WorkflowJson.decodeStrict(StoredRevisionManifest.serializer(), decodeStrictUtf8(bytes))
        } catch (error: Exception) {
            throw WorkflowStorageException("Stored revision manifest is invalid", error)
        }
    }

    private fun decodeStrictUtf8(bytes: ByteArray): String = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString()
}

/** Produces a device-local content identity without exposing a public fingerprint of image pixels. */
internal class AssetBlobIdentity(private val keySource: () -> SecretKey) {
    fun identify(template: DecodedTemplate): AssetBlobKey {
        val rgba = template.copyRgbaBytes()
        return try {
            identify(template.widthPx, template.heightPx, rgba)
        } finally {
            rgba.fill(0)
        }
    }

    internal fun identify(widthPx: Int, heightPx: Int, rgba: ByteArray): AssetBlobKey {
        require(widthPx > 0 && heightPx > 0) { "Asset dimensions must be positive" }
        require(widthPx.toLong() * heightPx * 4L == rgba.size.toLong()) {
            "Asset pixel payload does not match its dimensions"
        }
        val dimensions = ByteBuffer.allocate(Int.SIZE_BYTES * 2)
            .putInt(widthPx)
            .putInt(heightPx)
            .array()
        val mac = Mac.getInstance(HMAC_ALGORITHM).apply { init(keySource()) }
        mac.update(BLOB_IDENTITY_HEADER)
        mac.update(dimensions)
        mac.update(rgba)
        return AssetBlobKey(mac.doFinal().toLowerHex())
    }
}

private fun ByteArray.toLowerHex(): String = buildString(size * 2) {
    this@toLowerHex.forEach { byte ->
        val value = byte.toInt() and 0xff
        append(HEX_DIGITS[value ushr 4])
        append(HEX_DIGITS[value and 0x0f])
    }
}

private const val HEX_DIGITS = "0123456789abcdef"

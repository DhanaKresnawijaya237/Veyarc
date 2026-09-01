package com.veyarc.model.bundle

import com.veyarc.model.AssetId
import com.veyarc.model.AssetKind
import com.veyarc.model.WorkflowDocument
import com.veyarc.model.WorkflowJson
import com.veyarc.model.WorkflowValidator
import kotlinx.serialization.Serializable
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

private const val WORKFLOW_ENTRY = "workflow.json"
private const val MANIFEST_ENTRY = "manifest.json"
private const val ASSET_PREFIX = "assets/"
private const val ASSET_SUFFIX = ".bin"

@Serializable
data class BundleManifest(
    val bundleVersion: Int,
    val workflowSchemaVersion: Int,
    val workflowId: String,
    val workflowRevision: Int,
    val producerVersion: String,
    val assets: List<BundleManifestAsset>,
)

@Serializable
data class BundleManifestAsset(
    val assetId: AssetId,
    val kind: AssetKind,
    val entryName: String,
    val sha256: String,
    val byteLength: Long,
    val mimeType: String,
    val encrypted: Boolean,
)

/** An already-encrypted payload. Encryption and key management are outside this portable module. */
data class BundleAsset(
    val assetId: AssetId,
    val kind: AssetKind,
    val mimeType: String,
    val bytes: ByteArray,
    val encrypted: Boolean,
) {
    init {
        require(mimeType.startsWith("image/")) { "Workflow condition assets must use an image MIME type" }
        require(mimeType.length <= 128) { "Asset MIME type must be at most 128 characters" }
        require(bytes.isNotEmpty()) { "Asset payload must not be empty" }
        require(encrypted) { "Workflow condition assets must be encrypted before bundling" }
    }
}

data class WorkflowBundle(
    val document: WorkflowDocument,
    val assets: List<BundleAsset>,
    val producerVersion: String,
) {
    init {
        require(producerVersion.isNotBlank() && producerVersion.length <= 64) {
            "Producer version must be 1 to 64 characters"
        }
    }
}

data class BundleLimits(
    val maxBundleBytes: Long = 32L * 1024L * 1024L,
    val maxEntries: Int = 64,
    val maxEntryNameLength: Int = 256,
    val maxWorkflowJsonBytes: Long = 2L * 1024L * 1024L,
    val maxManifestBytes: Long = 512L * 1024L,
    val maxAssetBytes: Long = 12L * 1024L * 1024L,
    val maxTotalAssetBytes: Long = 24L * 1024L * 1024L,
) {
    init {
        require(maxBundleBytes > 0) { "maxBundleBytes must be positive" }
        require(maxEntries >= 2) { "maxEntries must allow workflow and manifest entries" }
        require(maxEntryNameLength > 0) { "maxEntryNameLength must be positive" }
        require(maxWorkflowJsonBytes > 0) { "maxWorkflowJsonBytes must be positive" }
        require(maxManifestBytes > 0) { "maxManifestBytes must be positive" }
        require(maxAssetBytes > 0) { "maxAssetBytes must be positive" }
        require(maxTotalAssetBytes >= maxAssetBytes) { "maxTotalAssetBytes must include one asset" }
        require(maxAssetBytes <= Int.MAX_VALUE) { "maxAssetBytes must fit a JVM byte array" }
        require(maxWorkflowJsonBytes <= Int.MAX_VALUE) { "maxWorkflowJsonBytes must fit a JVM byte array" }
        require(maxManifestBytes <= Int.MAX_VALUE) { "maxManifestBytes must fit a JVM byte array" }
    }
}

class BundleFormatException(message: String, cause: Throwable? = null) : Exception(message, cause)

object BundleMigrations {
    const val CURRENT_BUNDLE_VERSION: Int = 1

    /** Entry point for future bundle migrations. Version 1 is already canonical. */
    fun migrateWorkflowJson(bundleVersion: Int, workflowJson: String): String = when {
        bundleVersion == CURRENT_BUNDLE_VERSION -> workflowJson

        bundleVersion > CURRENT_BUNDLE_VERSION -> throw BundleFormatException(
            "Bundle version $bundleVersion is newer than supported version $CURRENT_BUNDLE_VERSION",
        )

        else -> throw BundleFormatException("No migration path exists for bundle version $bundleVersion")
    }
}

object VeyarcBundle {
    fun export(bundle: WorkflowBundle, limits: BundleLimits = BundleLimits()): ByteArray {
        val validationErrors = WorkflowValidator.validate(bundle.document)
        if (validationErrors.isNotEmpty()) {
            throw BundleFormatException(
                "Cannot export an invalid workflow: ${validationErrors.joinToString { it.code.name }}",
            )
        }
        validateAssets(bundle.document, bundle.assets)

        val workflowBytes = WorkflowJson.encode(bundle.document).toByteArray(StandardCharsets.UTF_8)
        checkSize(WORKFLOW_ENTRY, workflowBytes.size.toLong(), limits.maxWorkflowJsonBytes)
        val manifest = createManifest(bundle)
        val manifestBytes = WorkflowJson.encodeCanonical(BundleManifest.serializer(), manifest)
            .toByteArray(StandardCharsets.UTF_8)
        checkSize(MANIFEST_ENTRY, manifestBytes.size.toLong(), limits.maxManifestBytes)

        val sortedAssets = bundle.assets.sortedBy { it.assetId.value }
        val totalAssetBytes = sortedAssets.sumOf { it.bytes.size.toLong() }
        checkSize("asset payloads", totalAssetBytes, limits.maxTotalAssetBytes)
        if (sortedAssets.size + 2 > limits.maxEntries) {
            throw BundleFormatException("Bundle contains too many entries")
        }

        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            writeEntry(zip, WORKFLOW_ENTRY, workflowBytes)
            writeEntry(zip, MANIFEST_ENTRY, manifestBytes)
            sortedAssets.forEach { asset ->
                checkSize(assetEntryName(asset.assetId), asset.bytes.size.toLong(), limits.maxAssetBytes)
                writeEntry(zip, assetEntryName(asset.assetId), asset.bytes)
            }
        }
        val result = output.toByteArray()
        checkSize("bundle", result.size.toLong(), limits.maxBundleBytes)
        return result
    }

    fun import(bytes: ByteArray, limits: BundleLimits = BundleLimits()): WorkflowBundle {
        checkSize("bundle", bytes.size.toLong(), limits.maxBundleBytes)
        val entries = readEntries(bytes, limits)
        val workflowBytes = entries[WORKFLOW_ENTRY] ?: throw BundleFormatException("Bundle is missing workflow.json")
        val manifestBytes = entries[MANIFEST_ENTRY] ?: throw BundleFormatException("Bundle is missing manifest.json")
        val manifest = decodeManifest(manifestBytes)
        val workflowJson = BundleMigrations.migrateWorkflowJson(
            manifest.bundleVersion,
            workflowBytes.toString(StandardCharsets.UTF_8),
        )
        val document = try {
            WorkflowJson.decode(workflowJson)
        } catch (error: Exception) {
            throw BundleFormatException("workflow.json is not a valid workflow document", error)
        }
        val validationErrors = WorkflowValidator.validate(document)
        if (validationErrors.isNotEmpty()) {
            throw BundleFormatException(
                "Imported workflow is invalid: ${validationErrors.joinToString { it.code.name }}",
            )
        }
        validateManifestIdentity(manifest, document)
        val assets = decodeAssets(entries, manifest, document, limits)
        val expectedEntries = buildSet {
            add(WORKFLOW_ENTRY)
            add(MANIFEST_ENTRY)
            manifest.assets.forEach { add(it.entryName) }
        }
        if (entries.keys != expectedEntries) {
            throw BundleFormatException("Bundle contains undeclared or missing entries")
        }
        return WorkflowBundle(document, assets, manifest.producerVersion)
    }

    private fun createManifest(bundle: WorkflowBundle): BundleManifest = BundleManifest(
        bundleVersion = BundleMigrations.CURRENT_BUNDLE_VERSION,
        workflowSchemaVersion = bundle.document.schemaVersion,
        workflowId = bundle.document.id,
        workflowRevision = bundle.document.revision,
        producerVersion = bundle.producerVersion,
        assets = bundle.assets
            .sortedBy { it.assetId.value }
            .map { asset ->
                BundleManifestAsset(
                    assetId = asset.assetId,
                    kind = asset.kind,
                    entryName = assetEntryName(asset.assetId),
                    sha256 = sha256(asset.bytes),
                    byteLength = asset.bytes.size.toLong(),
                    mimeType = asset.mimeType,
                    encrypted = asset.encrypted,
                )
            },
    )

    private fun decodeManifest(bytes: ByteArray): BundleManifest = try {
        WorkflowJson.decodeStrict(BundleManifest.serializer(), bytes.toString(StandardCharsets.UTF_8))
    } catch (error: Exception) {
        throw BundleFormatException("manifest.json is malformed", error)
    }

    private fun decodeAssets(
        entries: Map<String, ByteArray>,
        manifest: BundleManifest,
        document: WorkflowDocument,
        limits: BundleLimits,
    ): List<BundleAsset> {
        if (manifest.assets.size + 2 > limits.maxEntries) {
            throw BundleFormatException("Manifest declares too many assets")
        }
        val references = document.assets.associateBy { it.assetId.value }
        val seenIds = mutableSetOf<String>()
        var totalBytes = 0L
        val assets = manifest.assets.sortedBy { it.assetId.value }.map { metadata ->
            if (!seenIds.add(metadata.assetId.value)) {
                throw BundleFormatException("Manifest declares duplicate asset ${metadata.assetId.value}")
            }
            val reference = references[metadata.assetId.value]
                ?: throw BundleFormatException("Manifest asset is not referenced by workflow.json")
            if (reference.kind != metadata.kind) {
                throw BundleFormatException("Manifest asset kind does not match workflow.json")
            }
            if (metadata.kind != AssetKind.IMAGE_TEMPLATE || !metadata.encrypted) {
                throw BundleFormatException("Condition assets must be encrypted image templates")
            }
            val expectedEntry = assetEntryName(metadata.assetId)
            if (metadata.entryName != expectedEntry) {
                throw BundleFormatException("Manifest asset entry name is invalid")
            }
            val payload = entries[metadata.entryName]
                ?: throw BundleFormatException("Bundle is missing ${metadata.entryName}")
            checkSize(metadata.entryName, payload.size.toLong(), limits.maxAssetBytes)
            totalBytes += payload.size
            if (metadata.byteLength != payload.size.toLong()) {
                throw BundleFormatException("Asset byte length does not match manifest")
            }
            if (!metadata.sha256.equals(sha256(payload), ignoreCase = true)) {
                throw BundleFormatException("Asset hash does not match manifest")
            }
            BundleAsset(
                assetId = metadata.assetId,
                kind = metadata.kind,
                mimeType = metadata.mimeType,
                bytes = payload,
                encrypted = metadata.encrypted,
            )
        }
        checkSize("asset payloads", totalBytes, limits.maxTotalAssetBytes)
        if (seenIds != references.keys) {
            throw BundleFormatException("Manifest assets do not exactly match workflow.json references")
        }
        return assets
    }

    private fun validateManifestIdentity(manifest: BundleManifest, document: WorkflowDocument) {
        if (manifest.bundleVersion <= 0) throw BundleFormatException("Bundle version must be positive")
        if (manifest.workflowSchemaVersion != document.schemaVersion) {
            throw BundleFormatException("Manifest workflow schema version does not match workflow.json")
        }
        if (manifest.workflowId != document.id || manifest.workflowRevision != document.revision) {
            throw BundleFormatException("Manifest workflow identity does not match workflow.json")
        }
    }

    private fun validateAssets(document: WorkflowDocument, assets: List<BundleAsset>) {
        val references = document.assets.associateBy { it.assetId.value }
        if (references.size != document.assets.size) throw BundleFormatException("Workflow declares duplicate assets")
        val seen = mutableSetOf<String>()
        assets.forEach { asset ->
            if (!seen.add(asset.assetId.value)) throw BundleFormatException("Bundle contains duplicate assets")
            val reference = references[asset.assetId.value]
                ?: throw BundleFormatException("Bundle asset is not referenced by workflow.json")
            if (reference.kind != asset.kind) throw BundleFormatException("Bundle asset kind does not match workflow.json")
        }
        if (seen != references.keys) throw BundleFormatException("Bundle assets do not match workflow.json references")
    }

    private fun readEntries(bytes: ByteArray, limits: BundleLimits): Map<String, ByteArray> {
        val entries = linkedMapOf<String, ByteArray>()
        try {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                var count = 0
                var totalAssetBytes = 0L
                while (true) {
                    val entry = zip.nextEntry ?: break
                    count++
                    if (count > limits.maxEntries) throw BundleFormatException("Bundle contains too many entries")
                    validateEntryName(entry.name, limits.maxEntryNameLength)
                    if (entries.containsKey(entry.name)) throw BundleFormatException("Bundle contains duplicate entry ${entry.name}")
                    val maxBytes = when (entry.name) {
                        WORKFLOW_ENTRY -> limits.maxWorkflowJsonBytes
                        MANIFEST_ENTRY -> limits.maxManifestBytes
                        else -> limits.maxAssetBytes
                    }
                    val payload = readEntry(zip, entry.name, maxBytes)
                    if (entry.name.startsWith(ASSET_PREFIX)) {
                        totalAssetBytes += payload.size
                        checkSize("asset payloads", totalAssetBytes, limits.maxTotalAssetBytes)
                    }
                    entries[entry.name] = payload
                    zip.closeEntry()
                }
            }
        } catch (error: BundleFormatException) {
            throw error
        } catch (error: Exception) {
            throw BundleFormatException("Bundle ZIP is malformed", error)
        }
        return entries
    }

    private fun readEntry(zip: ZipInputStream, name: String, maxBytes: Long): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0L
        while (true) {
            val count = zip.read(buffer)
            if (count < 0) break
            total += count
            if (total > maxBytes) throw BundleFormatException("Entry $name exceeds its safety limit")
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun validateEntryName(name: String, maxLength: Int) {
        if (name.length > maxLength || name.startsWith("/") || name.contains('\\') || name.endsWith('/')) {
            throw BundleFormatException("Unsafe ZIP entry name")
        }
        if (name.split('/').any { it.isEmpty() || it == "." || it == ".." }) {
            throw BundleFormatException("Unsafe ZIP entry path")
        }
        val isKnownEntry = name == WORKFLOW_ENTRY || name == MANIFEST_ENTRY
        val isAssetEntry = name.startsWith(ASSET_PREFIX) && name.endsWith(ASSET_SUFFIX)
        if (!isKnownEntry && !isAssetEntry) {
            throw BundleFormatException("Unexpected ZIP entry $name")
        }
    }

    private fun writeEntry(zip: ZipOutputStream, name: String, bytes: ByteArray) {
        val crc = CRC32().apply { update(bytes) }
        val entry = ZipEntry(name).apply {
            method = ZipEntry.STORED
            size = bytes.size.toLong()
            compressedSize = bytes.size.toLong()
            this.crc = crc.value
            time = 0L
            extra = ByteArray(0)
        }
        zip.putNextEntry(entry)
        zip.write(bytes)
        zip.closeEntry()
    }

    private fun assetEntryName(assetId: AssetId): String = "$ASSET_PREFIX${assetId.value}$ASSET_SUFFIX"

    private fun checkSize(name: String, actual: Long, maximum: Long) {
        if (actual > maximum) throw BundleFormatException("$name exceeds its safety limit")
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString(separator = "") { "%02x".format(it.toInt() and 0xff) }
}

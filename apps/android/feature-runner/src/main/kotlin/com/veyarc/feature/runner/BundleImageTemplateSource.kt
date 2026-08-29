package com.veyarc.feature.runner

import com.veyarc.automation.android.AndroidAutomationException
import com.veyarc.automation.android.ImageTemplateSource
import com.veyarc.automation.android.RgbaTemplate
import com.veyarc.data.storage.AndroidKeystoreKeySource
import com.veyarc.data.storage.EncryptedTemplateAssetCodec
import com.veyarc.model.AssetId
import com.veyarc.model.AssetKind
import com.veyarc.model.bundle.BundleAsset
import java.io.Closeable

/**
 * Decrypts workflow image assets only while a visual-condition run is active.
 *
 * The encrypted payload is copied so the evaluator is not coupled to the mutable bundle object.
 * Decoded templates are cached for this run to avoid decrypting and allocating on every poll, and
 * both encrypted and decoded copies are cleared when the run releases the source.
 */
internal class BundleImageTemplateSource(
    assets: List<BundleAsset>,
    private val codec: EncryptedTemplateAssetCodec = EncryptedTemplateAssetCodec(
        AndroidKeystoreKeySource(TEMPLATE_KEY_ALIAS),
    ),
) : ImageTemplateSource,
    Closeable {
    init {
        require(assets.map { it.assetId }.distinct().size == assets.size) {
            "Visual condition assets must have unique IDs"
        }
    }

    private val encryptedAssets: Map<AssetId, EncryptedAsset> = assets.associate { asset ->
        require(asset.kind == AssetKind.IMAGE_TEMPLATE) {
            "Only image-template assets can be evaluated as visual conditions"
        }
        require(asset.encrypted) { "Visual condition assets must be encrypted" }
        asset.assetId to EncryptedAsset(asset.bytes.copyOf())
    }
    private val decodedTemplates = mutableMapOf<AssetId, RgbaTemplate>()
    private var closed = false

    @Synchronized
    override fun templateFor(assetId: AssetId): RgbaTemplate? {
        check(!closed) { "The workflow template source has already been released" }
        decodedTemplates[assetId]?.let { return it }
        val asset = encryptedAssets[assetId] ?: return null
        val decoded = try {
            codec.decrypt(assetId, asset.encryptedBytes)
        } catch (error: Exception) {
            throw AndroidAutomationException(
                "Could not decrypt image template ${assetId.value}",
                error,
            )
        }
        return try {
            val rgbaBytes = decoded.copyRgbaBytes()
            try {
                RgbaTemplate.fromRgba(decoded.widthPx, decoded.heightPx, rgbaBytes).also { template ->
                    decodedTemplates[assetId] = template
                }
            } finally {
                rgbaBytes.fill(0)
            }
        } catch (error: Exception) {
            throw AndroidAutomationException(
                "Could not decode image template ${assetId.value}",
                error,
            )
        } finally {
            decoded.clear()
        }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        decodedTemplates.values.forEach(RgbaTemplate::clear)
        decodedTemplates.clear()
        encryptedAssets.values.forEach { asset -> asset.encryptedBytes.fill(0) }
    }

    private data class EncryptedAsset(
        val encryptedBytes: ByteArray,
    )

    private companion object {
        const val TEMPLATE_KEY_ALIAS = "veyarc.template-assets.v1"
    }
}

package com.veyarc.feature.runner

import com.veyarc.automation.android.AndroidAutomationException
import com.veyarc.automation.android.ImageTemplateSource
import com.veyarc.automation.android.RgbaTemplate
import com.veyarc.data.storage.AndroidKeystoreKeySource
import com.veyarc.data.storage.DecodedTemplate
import com.veyarc.data.storage.EncryptedTemplateAssetCodec
import com.veyarc.data.storage.PreparedRunAssets
import com.veyarc.data.storage.TEMPLATE_ASSET_KEY_ALIAS
import com.veyarc.data.storage.WorkflowAssetRole
import com.veyarc.engine.ImageAssetUse
import com.veyarc.model.AssetId
import com.veyarc.model.AssetKind
import com.veyarc.model.bundle.BundleAsset
import java.io.Closeable
import java.util.Collections
import java.util.IdentityHashMap

/** Frozen, memory-only image templates owned by one workflow run. */
internal class BundleImageTemplateSource private constructor(
    templates: Map<AssetId, RgbaTemplate>,
) : ImageTemplateSource,
    Closeable {
    private val templates = Collections.unmodifiableMap(templates.toMap())
    private var closed = false

    @Synchronized
    override fun templateFor(assetId: AssetId): RgbaTemplate? {
        check(!closed) { "The workflow template source has already been released" }
        return templates[assetId]
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        templates.values.distinct().forEach(RgbaTemplate::clear)
    }

    companion object {
        fun prepareStored(
            prepared: PreparedRunAssets,
            requirements: Map<AssetId, Set<ImageAssetUse>>,
        ): BundleImageTemplateSource = try {
            requirements.forEach { (assetId, uses) ->
                val asset = prepared.assets[assetId]
                    ?: throw AndroidAutomationException("Image template ${assetId.value} is unavailable")
                validateRole(assetId, uses, asset.role)
            }
            fromDecoded(prepared.assets.mapValues { it.value.template })
        } finally {
            prepared.close()
        }

        fun prepareBundle(
            assets: List<BundleAsset>,
            requirements: Map<AssetId, Set<ImageAssetUse>>,
            viewportWidthPx: Int,
            viewportHeightPx: Int,
            maxDecodedBytes: Long,
            codec: EncryptedTemplateAssetCodec = EncryptedTemplateAssetCodec(
                AndroidKeystoreKeySource(TEMPLATE_ASSET_KEY_ALIAS),
            ),
        ): BundleImageTemplateSource {
            require(maxDecodedBytes > 0L) { "Run template memory budget must be positive" }
            val assetsById = assets.associateBy(BundleAsset::assetId)
            require(assetsById.size == assets.size) { "Visual condition assets must have unique IDs" }
            val decoded = linkedMapOf<AssetId, DecodedTemplate>()
            var decodedBytes = 0L
            try {
                requirements.forEach { (assetId, uses) ->
                    val asset = assetsById[assetId]
                        ?: throw AndroidAutomationException("Image template ${assetId.value} is unavailable")
                    require(asset.kind == AssetKind.IMAGE_TEMPLATE && asset.encrypted) {
                        "Visual condition assets must be encrypted image templates"
                    }
                    val template = codec.decrypt(assetId, asset.bytes)
                    decoded[assetId] = template
                    decodedBytes += template.widthPx.toLong() * template.heightPx * 4L + MATCHER_OVERHEAD_BYTES
                    if (decodedBytes > maxDecodedBytes) {
                        throw AndroidAutomationException("Workflow image templates exceed this device's preparation memory budget")
                    }
                    validateRole(
                        assetId,
                        uses,
                        if (template.widthPx == viewportWidthPx && template.heightPx == viewportHeightPx) {
                            WorkflowAssetRole.FULL_SCREEN
                        } else {
                            WorkflowAssetRole.CROPPED_TARGET
                        },
                    )
                }
                return fromDecoded(decoded)
            } catch (error: Exception) {
                if (error is AndroidAutomationException) throw error
                throw AndroidAutomationException("Could not prepare workflow image templates", error)
            } finally {
                decoded.values.forEach(DecodedTemplate::clear)
            }
        }

        private fun fromDecoded(decoded: Map<AssetId, DecodedTemplate>): BundleImageTemplateSource {
            val converted = IdentityHashMap<DecodedTemplate, RgbaTemplate>()
            val templates = linkedMapOf<AssetId, RgbaTemplate>()
            try {
                decoded.forEach { (assetId, source) ->
                    templates[assetId] = converted[source] ?: source.toRgbaTemplate().also {
                        converted[source] = it
                    }
                }
                return BundleImageTemplateSource(templates)
            } catch (error: Exception) {
                converted.values.forEach(RgbaTemplate::clear)
                throw AndroidAutomationException("Could not prepare workflow image templates", error)
            }
        }

        private fun validateRole(
            assetId: AssetId,
            uses: Set<ImageAssetUse>,
            storedRole: WorkflowAssetRole,
        ) {
            val requiredRole = when (uses.singleOrNull()) {
                ImageAssetUse.FULL_SCREEN -> WorkflowAssetRole.FULL_SCREEN

                ImageAssetUse.CROPPED_TARGET -> WorkflowAssetRole.CROPPED_TARGET

                null -> throw AndroidAutomationException(
                    "Image template ${assetId.value} cannot be both a full-screen state and a cropped target",
                )
            }
            if (requiredRole != storedRole) {
                throw AndroidAutomationException(
                    "Image template ${assetId.value} is not compatible with its visual condition",
                )
            }
        }

        private const val MATCHER_OVERHEAD_BYTES = 16L * 16L * 4L * Int.SIZE_BYTES
    }
}

private fun DecodedTemplate.toRgbaTemplate(): RgbaTemplate {
    val bytes = takeRgbaBytes()
    return try {
        RgbaTemplate.fromOwnedRgba(widthPx, heightPx, bytes)
    } catch (error: Exception) {
        bytes.fill(0)
        throw error
    }
}

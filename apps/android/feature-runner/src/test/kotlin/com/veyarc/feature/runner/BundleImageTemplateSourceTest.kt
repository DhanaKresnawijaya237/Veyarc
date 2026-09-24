package com.veyarc.feature.runner

import com.veyarc.automation.android.RgbaTemplate
import com.veyarc.data.storage.DecodedTemplate
import com.veyarc.data.storage.EncryptedTemplateAssetCodec
import com.veyarc.data.storage.SecretKeySource
import com.veyarc.engine.ImageAssetUse
import com.veyarc.model.AssetId
import com.veyarc.model.AssetKind
import com.veyarc.model.bundle.BundleAsset
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.spec.SecretKeySpec

class BundleImageTemplateSourceTest {
    private val keySource = SecretKeySource {
        SecretKeySpec(ByteArray(32) { index -> (index + 1).toByte() }, "AES")
    }

    @Test
    fun decryptsAndCachesTemplateForOneRun() {
        val encrypted = encryptTemplate()
        val source = BundleImageTemplateSource.prepareBundle(
            assets = listOf(asset(encrypted)),
            requirements = mapOf(ASSET_ID to setOf(ImageAssetUse.CROPPED_TARGET)),
            viewportWidthPx = 1080,
            viewportHeightPx = 2392,
            maxDecodedBytes = 1024 * 1024,
            codec = EncryptedTemplateAssetCodec(keySource),
        )

        val first = source.templateFor(ASSET_ID)
        val second = source.templateFor(ASSET_ID)

        assertNotNull(first)
        assertTrue(first === second)
        assertEquals(2, first?.widthPx)
        assertEquals(1, first?.heightPx)
        assertArrayEquals(RGBA, first?.copyRgbaBytes())
        source.close()
        assertArrayEquals(ByteArray(RGBA.size), first?.copyRgbaBytes())
    }

    @Test
    fun returnsNullForAnUnreferencedAsset() {
        val source = BundleImageTemplateSource.prepareBundle(
            assets = listOf(asset(encryptTemplate())),
            requirements = mapOf(ASSET_ID to setOf(ImageAssetUse.CROPPED_TARGET)),
            viewportWidthPx = 1080,
            viewportHeightPx = 2392,
            maxDecodedBytes = 1024 * 1024,
            codec = EncryptedTemplateAssetCodec(keySource),
        )

        assertEquals(null, source.templateFor(AssetId("missing-template")))
        source.close()
    }

    @Test
    fun lookupPerformsNoKeyAccessAfterPreparation() {
        var keyReads = 0
        val countedKeySource = SecretKeySource {
            keyReads++
            SecretKeySpec(ByteArray(32) { index -> (index + 1).toByte() }, "AES")
        }
        val codec = EncryptedTemplateAssetCodec(countedKeySource)
        val encrypted = codec.encrypt(
            ASSET_ID,
            DecodedTemplate(widthPx = 2, heightPx = 1, rgbaBytes = RGBA),
        )
        val source = BundleImageTemplateSource.prepareBundle(
            assets = listOf(asset(encrypted)),
            requirements = mapOf(ASSET_ID to setOf(ImageAssetUse.CROPPED_TARGET)),
            viewportWidthPx = 1080,
            viewportHeightPx = 2392,
            maxDecodedBytes = 1024 * 1024,
            codec = codec,
        )
        val readsAfterPreparation = keyReads

        repeat(3) { assertNotNull(source.templateFor(ASSET_ID)) }

        assertEquals(readsAfterPreparation, keyReads)
        source.close()
    }

    @Test
    fun reportsMalformedEncryptedPayloadAsAPlatformFailure() {
        var failed = false
        try {
            BundleImageTemplateSource.prepareBundle(
                assets = listOf(asset(byteArrayOf(1, 2, 3))),
                requirements = mapOf(ASSET_ID to setOf(ImageAssetUse.CROPPED_TARGET)),
                viewportWidthPx = 1080,
                viewportHeightPx = 2392,
                maxDecodedBytes = 1024 * 1024,
                codec = EncryptedTemplateAssetCodec(keySource),
            )
        } catch (error: IllegalStateException) {
            failed = error.message == "Could not prepare workflow image templates"
        }
        assertTrue(failed)
    }

    private fun encryptTemplate(): ByteArray = EncryptedTemplateAssetCodec(keySource).encrypt(
        ASSET_ID,
        DecodedTemplate(widthPx = 2, heightPx = 1, rgbaBytes = RGBA),
    )

    private fun asset(bytes: ByteArray) = BundleAsset(
        assetId = ASSET_ID,
        kind = AssetKind.IMAGE_TEMPLATE,
        mimeType = "image/x-veyarc-rgba",
        bytes = bytes,
        encrypted = true,
    )

    private companion object {
        val ASSET_ID = AssetId("template-main")
        val RGBA = byteArrayOf(
            0xFF.toByte(),
            0x00,
            0x00,
            0xFF.toByte(),
            0x00,
            0xFF.toByte(),
            0x00,
            0xFF.toByte(),
        )
    }
}

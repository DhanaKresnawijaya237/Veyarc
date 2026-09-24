package com.veyarc.data.storage

import com.veyarc.model.AssetId
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.crypto.spec.SecretKeySpec

class EncryptedTemplateAssetCodecTest {
    private val key = SecretKeySpec(ByteArray(32) { (it + 17).toByte() }, "AES")
    private val assetId = AssetId("template-test")

    @Test
    fun roundTripAuthenticatesDimensionsAndPixels() {
        val template = DecodedTemplate(
            widthPx = 2,
            heightPx = 1,
            rgbaBytes = byteArrayOf(255.toByte(), 0, 0, 255.toByte(), 0, 255.toByte(), 0, 255.toByte()),
        )
        val codec = EncryptedTemplateAssetCodec(::keySource)

        val encrypted = codec.encrypt(assetId, template)
        val decoded = codec.decrypt(assetId, encrypted)

        assertEquals(template.widthPx, decoded.widthPx)
        assertEquals(template.heightPx, decoded.heightPx)
        assertArrayEquals(template.copyRgbaBytes(), decoded.copyRgbaBytes())
        assertTrue(encrypted.isNotEmpty())
    }

    @Test
    fun tamperingOrRelabelingIsRejected() {
        val codec = EncryptedTemplateAssetCodec(::keySource)
        val encrypted = codec.encrypt(assetId, DecodedTemplate(1, 1, ByteArray(4) { 9 }))
        val tampered = encrypted.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }

        assertRejected { codec.decrypt(assetId, tampered) }
        assertRejected { codec.decrypt(AssetId("template-other"), encrypted) }
    }

    @Test
    fun rejectsOversizedTemplatesBeforeEncryption() {
        val codec = EncryptedTemplateAssetCodec(::keySource, maxEncryptedBytes = 64)
        val tooLarge = DecodedTemplate(4, 4, ByteArray(4 * 4 * 4) { (it * 73).toByte() })

        assertRejected { codec.encrypt(assetId, tooLarge) }
    }

    @Test
    fun verificationRejectsTamperedCiphertext() {
        val codec = EncryptedTemplateAssetCodec(::keySource)
        val encrypted = codec.encrypt(assetId, DecodedTemplate(1, 1, ByteArray(4) { 7 }))
        val tampered = encrypted.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }

        codec.verify(assetId, encrypted)
        assertRejected { codec.verify(assetId, tampered) }
    }

    @Test
    fun compressedPayloadIsSmallerAndVerificationReportsDimensions() {
        val codec = EncryptedTemplateAssetCodec(::keySource)
        val raw = ByteArray(400 * 400 * 4) { if (it % 4 == 3) 255.toByte() else 32 }

        val encrypted = codec.encrypt(assetId, DecodedTemplate(400, 400, raw))
        val verified = codec.verify(assetId, encrypted)

        assertTrue(encrypted.size < raw.size / 4)
        assertEquals(400, verified.widthPx)
        assertEquals(400, verified.heightPx)
        assertEquals(null, verified.upgradedBytes)
    }

    @Test
    fun verificationUpgradesLegacyRawPayload() {
        val pixels = byteArrayOf(1, 2, 3, 4)
        val legacyPlaintext = ByteBuffer.allocate(13 + pixels.size).order(ByteOrder.BIG_ENDIAN).apply {
            put(byteArrayOf('V'.code.toByte(), 'Y'.code.toByte(), 'T'.code.toByte(), '1'.code.toByte()))
            put(1.toByte())
            putInt(1)
            putInt(1)
            put(pixels)
        }.array()
        val legacyEncrypted = AesGcmFileCodec(::keySource).encrypt(
            legacyPlaintext,
            "template:${assetId.value}".encodeToByteArray(),
        )
        val codec = EncryptedTemplateAssetCodec(::keySource)

        val upgraded = codec.verify(assetId, legacyEncrypted).upgradedBytes

        assertTrue(upgraded != null)
        assertArrayEquals(pixels, codec.decrypt(assetId, upgraded!!).copyRgbaBytes())
    }

    @Test
    fun decodedPixelsCanBeTransferredWithoutLeavingASecondOwnedBuffer() {
        val template = DecodedTemplate(1, 1, byteArrayOf(1, 2, 3, 4))

        val transferred = template.takeRgbaBytes()

        assertArrayEquals(byteArrayOf(1, 2, 3, 4), transferred)
        assertArrayEquals(ByteArray(0), template.copyRgbaBytes())
        assertRejected { template.takeRgbaBytes() }
    }

    private fun keySource() = key

    private fun assertRejected(block: () -> Unit) {
        var rejected = false
        try {
            block()
        } catch (_: Exception) {
            rejected = true
        }
        assertTrue("Expected codec operation to be rejected", rejected)
    }
}

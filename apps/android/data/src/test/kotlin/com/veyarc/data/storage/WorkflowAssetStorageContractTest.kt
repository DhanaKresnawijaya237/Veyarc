package com.veyarc.data.storage

import com.veyarc.model.AssetId
import com.veyarc.model.AssetKind
import com.veyarc.model.AssetReference
import com.veyarc.model.DisplayOrientation
import com.veyarc.model.DurationMillis
import com.veyarc.model.ImagePresentCondition
import com.veyarc.model.NormalizedRect
import com.veyarc.model.PackageName
import com.veyarc.model.RunLimits
import com.veyarc.model.Stop
import com.veyarc.model.StopReason
import com.veyarc.model.ViewportSpec
import com.veyarc.model.WaitUntil
import com.veyarc.model.WorkflowDocument
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.spec.SecretKeySpec

class WorkflowAssetStorageContractTest {
    private val identity = AssetBlobIdentity {
        SecretKeySpec(ByteArray(32) { (it + 3).toByte() }, "HmacSHA256")
    }

    @Test
    fun blobIdentityDeduplicatesContentButIncludesDimensions() {
        val pixels = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)

        val first = identity.identify(2, 1, pixels)
        val same = identity.identify(2, 1, pixels.copyOf())
        val reshaped = identity.identify(1, 2, pixels)
        val changed = identity.identify(2, 1, pixels.copyOf().also { it[0]++ })

        assertEquals(first, same)
        assertNotEquals(first, reshaped)
        assertNotEquals(first, changed)
        assertTrue(first.value.matches(Regex("[0-9a-f]{64}")))
    }

    @Test
    fun manifestCodecIsDeterministicAndRoundTripsWithoutPayloads() {
        val descriptor = descriptor()
        val manifest = StoredRevisionManifest(
            document = document(),
            producerVersion = "test-producer",
            assets = listOf(descriptor),
        )

        val first = StoredRevisionManifestCodec.encode(manifest)
        val second = StoredRevisionManifestCodec.encode(manifest)
        val decoded = StoredRevisionManifestCodec.decode(first)

        assertArrayEquals(first, second)
        assertEquals(manifest, decoded)
        assertTrue(first.size < 8_192)
    }

    @Test
    fun manifestRejectsDescriptorsThatDoNotMatchWorkflowDeclarations() {
        val result = runCatching {
            StoredRevisionManifest(
                document = document(),
                producerVersion = "test-producer",
                assets = listOf(descriptor().copy(assetId = AssetId("template-other"))),
            )
        }

        assertTrue(result.isFailure)
    }

    @Test
    fun manifestRejectsMalformedUtf8() {
        val result = runCatching {
            StoredRevisionManifestCodec.decode(byteArrayOf(0xC3.toByte(), 0x28))
        }

        assertTrue(result.exceptionOrNull() is WorkflowStorageException)
    }

    private fun descriptor() = StoredRevisionAssetDescriptor(
        assetId = ASSET_ID,
        kind = AssetKind.IMAGE_TEMPLATE,
        blobKey = identity.identify(1, 1, byteArrayOf(1, 2, 3, 4)),
        role = StoredTemplateRole.CROPPED_TARGET,
        mimeType = "image/png",
        widthPx = 1,
        heightPx = 1,
    )

    private fun document() = WorkflowDocument(
        schemaVersion = WorkflowDocument.CURRENT_SCHEMA_VERSION,
        id = WORKFLOW_ID,
        revision = 1,
        name = "Manifest test",
        allowedPackages = listOf(PackageName("com.example.app")),
        viewport = ViewportSpec(
            recordedWidthPx = 1080,
            recordedHeightPx = 2392,
            recordedDensityDpi = 420,
            recordedOrientation = DisplayOrientation.PORTRAIT,
        ),
        runLimits = RunLimits(
            maxTotalSteps = 10,
            maxRuntimeMs = 10_000,
            maxLoopIterations = 2,
            maxNestingDepth = 2,
            maxPathPoints = 4,
            maxStepDurationMs = 2_000,
        ),
        variables = emptyMap(),
        assets = listOf(AssetReference(ASSET_ID, AssetKind.IMAGE_TEMPLATE, "Target")),
        steps = listOf(
            WaitUntil(
                id = WAIT_ID,
                condition = ImagePresentCondition(
                    assetId = ASSET_ID,
                    region = NormalizedRect(0.0, 0.0, 1.0, 1.0),
                    matchThreshold = 0.9,
                ),
                timeoutMs = DurationMillis(1_000),
                pollIntervalMs = DurationMillis(100),
            ),
            Stop(STOP_ID, StopReason.WORKFLOW),
        ),
    )

    private companion object {
        val ASSET_ID = AssetId("template-main")
        const val WORKFLOW_ID = "00000000-0000-4000-8000-000000000401"
        const val WAIT_ID = "00000000-0000-4000-8000-000000000402"
        const val STOP_ID = "00000000-0000-4000-8000-000000000403"
    }
}

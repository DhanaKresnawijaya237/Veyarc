package com.veyarc.model.bundle

import com.veyarc.model.AssetId
import com.veyarc.model.AssetKind
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
import com.veyarc.model.WorkflowJson
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class WorkflowBundleTest {
    @Test
    fun exportAndImportAreDeterministic() {
        val bundle = sampleBundle()

        val first = VeyarcBundle.export(bundle)
        val second = VeyarcBundle.export(bundle)
        val imported = VeyarcBundle.import(first)

        assertArrayEquals(first, second)
        assertEquals(bundle.document, imported.document)
        assertEquals(bundle.producerVersion, imported.producerVersion)
        assertEquals(bundle.assets.single().assetId, imported.assets.single().assetId)
        assertArrayEquals(bundle.assets.single().bytes, imported.assets.single().bytes)
        assertEquals(WorkflowJson.encode(bundle.document), WorkflowJson.encode(imported.document))
    }

    @Test
    fun importerRejectsPathTraversalEntries() {
        val bytes = zipOf("../workflow.json" to "{}".toByteArray())

        var rejected = false
        try {
            VeyarcBundle.import(bytes)
        } catch (_: BundleFormatException) {
            rejected = true
        }

        assertTrue(rejected)
    }

    @Test
    fun importerEnforcesEntryLimitsBeforeReadingPayload() {
        val bundle = sampleBundle()
        val bytes = VeyarcBundle.export(bundle)

        var rejected = false
        try {
            VeyarcBundle.import(bytes, BundleLimits(maxBundleBytes = bytes.size.toLong(), maxAssetBytes = 2))
        } catch (_: BundleFormatException) {
            rejected = true
        }

        assertTrue(rejected)
    }

    @Test
    fun importerRejectsAssetHashMismatch() {
        val original = VeyarcBundle.export(sampleBundle())
        val tampered = replaceEntry(original, "assets/template-main.bin", byteArrayOf(9, 9, 9, 9))

        var rejected = false
        try {
            VeyarcBundle.import(tampered)
        } catch (_: BundleFormatException) {
            rejected = true
        }

        assertTrue(rejected)
    }

    @Test
    fun migrationEntryPointRejectsUnknownVersions() {
        var rejected = false
        try {
            BundleMigrations.migrateWorkflowJson(2, "{}")
        } catch (_: BundleFormatException) {
            rejected = true
        }

        assertTrue(rejected)
    }

    private fun sampleBundle(): WorkflowBundle {
        val asset = BundleAsset(
            assetId = AssetId("template-main"),
            kind = AssetKind.IMAGE_TEMPLATE,
            mimeType = "image/png",
            bytes = byteArrayOf(1, 2, 3, 4),
            encrypted = true,
        )
        val document = WorkflowDocument(
            schemaVersion = WorkflowDocument.CURRENT_SCHEMA_VERSION,
            id = DOCUMENT_ID,
            revision = 1,
            name = "Bundled workflow",
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
            assets = listOf(com.veyarc.model.AssetReference(asset.assetId, asset.kind)),
            steps = listOf(
                WaitUntil(
                    id = STEP_1,
                    condition = ImagePresentCondition(
                        assetId = asset.assetId,
                        region = NormalizedRect(0.0, 0.0, 1.0, 1.0),
                        matchThreshold = 0.9,
                    ),
                    timeoutMs = DurationMillis(1000),
                    pollIntervalMs = DurationMillis(100),
                ),
                Stop(STEP_2, StopReason.WORKFLOW),
            ),
        )
        return WorkflowBundle(document, listOf(asset), "test-producer")
    }

    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    private fun replaceEntry(bundle: ByteArray, target: String, replacement: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        java.util.zip.ZipInputStream(bundle.inputStream()).use { input ->
            ZipOutputStream(output).use { zip ->
                while (true) {
                    val entry = input.nextEntry ?: break
                    val bytes = if (entry.name == target) replacement else input.readBytes()
                    zip.putNextEntry(ZipEntry(entry.name))
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
        }
        return output.toByteArray()
    }

    private companion object {
        const val DOCUMENT_ID = "00000000-0000-4000-8000-000000000101"
        const val STEP_1 = "00000000-0000-4000-8000-000000000111"
        const val STEP_2 = "00000000-0000-4000-8000-000000000112"
    }
}

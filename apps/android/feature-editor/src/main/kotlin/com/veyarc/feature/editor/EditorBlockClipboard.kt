package com.veyarc.feature.editor

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.veyarc.data.storage.AesGcmFileCodec
import com.veyarc.data.storage.AndroidKeystoreKeySource
import com.veyarc.data.storage.DecodedTemplate
import com.veyarc.data.storage.EncryptedFileStore
import com.veyarc.data.storage.EncryptedTemplateAssetCodec
import com.veyarc.data.storage.TEMPLATE_ASSET_KEY_ALIAS
import com.veyarc.data.storage.WorkflowAssetDescriptor
import com.veyarc.data.storage.WorkflowRepository
import com.veyarc.model.AndCondition
import com.veyarc.model.AssetId
import com.veyarc.model.AssetKind
import com.veyarc.model.Condition
import com.veyarc.model.DisplayOrientation
import com.veyarc.model.IfElse
import com.veyarc.model.ImageAbsentCondition
import com.veyarc.model.ImagePresentCondition
import com.veyarc.model.Jump
import com.veyarc.model.NotCondition
import com.veyarc.model.OrCondition
import com.veyarc.model.Repeat
import com.veyarc.model.StepResultCondition
import com.veyarc.model.WaitUntil
import com.veyarc.model.WhileStep
import com.veyarc.model.WorkflowIds
import com.veyarc.model.WorkflowJson
import com.veyarc.model.WorkflowStep
import com.veyarc.model.backgroundAssetId
import com.veyarc.model.bundle.BundleAsset
import com.veyarc.model.withBackgroundAsset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import java.security.MessageDigest

@Serializable
internal data class ClipboardImage(
    val id: AssetId,
    val name: String,
    val kind: AssetKind,
    val mimeType: String,
    val widthPx: Int?,
    val heightPx: Int?,
    val fileName: String,
)

@Serializable
internal data class BlockClipboardEntry(
    val version: Int = 1,
    val orientation: DisplayOrientation,
    val steps: List<WorkflowStep>,
    val images: List<ClipboardImage>,
)

/** One device-private clipboard entry; each referenced image is snapshotted, not tied to its source revision. */
internal class EditorBlockClipboard(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("editor-block-clipboard", Context.MODE_PRIVATE)
    private val files = EncryptedFileStore(
        File(appContext.filesDir, "editor-block-clipboard"),
        AesGcmFileCodec(AndroidKeystoreKeySource("veyarc.editor-block-clipboard.v1")),
    )
    private val templateCodec = EncryptedTemplateAssetCodec(AndroidKeystoreKeySource(TEMPLATE_ASSET_KEY_ALIAS))

    fun read(): BlockClipboardEntry? = synchronized(lock) {
        val fileName = prefs.getString("current", null) ?: return null
        val bytes = files.read(fileName, AAD)
        WorkflowJson.decodeStrict(BlockClipboardEntry.serializer(), bytes.decodeToString()).also {
            require(it.version == 1 && it.steps.isNotEmpty()) { "Unsupported block clipboard" }
        }
    }

    fun replace(orientation: DisplayOrientation, steps: List<WorkflowStep>, assets: List<Pair<BundleAsset, String>>) = synchronized(lock) {
        require(steps.isNotEmpty()) { "Select at least one block" }
        val previous = runCatching { read() }.getOrNull()
        val previousFile = prefs.getString("current", null)
        val written = mutableListOf<String>()
        try {
            val images = assets.map { (asset, name) ->
                val file = "image-${WorkflowIds.newUuid()}.wfenc"
                files.writeNew(file, asset.bytes, AAD)
                written += file
                ClipboardImage(asset.assetId, name, asset.kind, asset.mimeType, asset.widthPx, asset.heightPx, file)
            }
            val entry = BlockClipboardEntry(orientation = orientation, steps = steps, images = images)
            val file = "entry-${WorkflowIds.newUuid()}.wfenc"
            files.writeNew(file, WorkflowJson.encodeCanonical(BlockClipboardEntry.serializer(), entry).encodeToByteArray(), AAD)
            written += file
            check(prefs.edit().putString("current", file).commit()) { "Could not save block clipboard" }
        } catch (error: Exception) {
            written.forEach { runCatching { files.delete(it) } }
            throw error
        }
        // Cleanup must not roll back a clipboard entry that was already committed.
        previous?.images?.forEach { runCatching { files.delete(it.fileName) } }
        if (previousFile != null) runCatching { files.delete(previousFile) }
    }

    fun importImages(
        images: List<ClipboardImage>,
        existingAssets: List<WorkflowAssetDescriptor>,
        loadAsset: (AssetId) -> BundleAsset,
    ): ClipboardImageImport = synchronized(lock) {
        importClipboardImages(images, existingAssets, templateCodec, { files.read(it.fileName, AAD) }, loadAsset)
    }

    companion object {
        private val AAD = "editor-block-clipboard:v1".encodeToByteArray()
        private val lock = Any()
        private val operationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        var isCopying by mutableStateOf(false)
            private set
        private var isPasting by mutableStateOf(false)
        val isBusy: Boolean get() = isCopying || isPasting
        var copyMessage by mutableStateOf<String?>(null)
            private set

        fun copy(context: Context, source: WorkflowDraft, steps: List<WorkflowStep>) {
            if (isBusy || steps.isEmpty()) return
            val app = context.applicationContext
            isCopying = true
            copyMessage = "Copying blocks…"
            // Navigation and rotation must not cancel the copy or change its source revision.
            operationScope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        val repository = WorkflowRepository(app)
                        try {
                            val images = steps.clipboardImageIds().map { id ->
                                val assetId = AssetId(id)
                                val asset = source.assets.firstOrNull { it.assetId == assetId }
                                    ?: repository.loadRevisionAsset(source.workflowId, requireNotNull(source.lastSavedRevision), assetId)
                                asset to (source.assetNames[id] ?: "Image")
                            }
                            EditorBlockClipboard(app).replace(source.viewport.recordedOrientation, steps, images)
                        } finally {
                            repository.close()
                        }
                    }
                    copyMessage = "Copied ${steps.size} block${if (steps.size == 1) "" else "s"}."
                } catch (_: Exception) {
                    copyMessage = "Could not copy blocks. Try again."
                } finally {
                    isCopying = false
                }
            }
        }

        fun paste(context: Context, session: WorkflowEditorSession, expected: WorkflowDraft, path: StepListPath, index: Int, entry: BlockClipboardEntry, includeImages: Boolean) {
            if (isBusy || !session.beginPaste(expected)) return
            val app = context.applicationContext
            isPasting = true
            copyMessage = "Pasting blocks…"
            operationScope.launch {
                try {
                    val (updated, omitted) = withContext(Dispatchers.IO) {
                        val repository = WorkflowRepository(app)
                        try {
                            val required = entry.steps.clipboardImageIds()
                            val images = if (includeImages) entry.images.filter { it.id.value in required } else emptyList()
                            val loaded = EditorBlockClipboard(app).importImages(images, expected.assetDescriptors) { id ->
                                expected.assets.firstOrNull { it.assetId == id }
                                    ?: repository.loadRevisionAsset(expected.workflowId, requireNotNull(expected.lastSavedRevision), id)
                            }
                            var result = expected.insertSteps(path, index, remapClipboardSteps(entry.steps, loaded.assetIds))
                            loaded.assets.forEach { (image, asset) ->
                                result = result.addAsset(asset).renameAsset(asset.assetId, uniqueClipboardImageName(result, image.name))
                            }
                            result to (required - expected.assetDescriptors.map { it.assetId.value }.toSet() - loaded.assetIds.keys)
                        } finally {
                            repository.close()
                        }
                    }
                    check(session.completePaste(expected, updated)) { "The destination workflow was closed or replaced." }
                    copyMessage = if (omitted.isEmpty()) "Pasted ${entry.steps.size} blocks." else "Pasted blocks. Add the missing images before saving."
                } catch (_: Exception) {
                    copyMessage = "Could not paste blocks. The workflow was not changed."
                } finally {
                    session.finishPaste()
                    isPasting = false
                }
            }
        }
    }
}

private fun uniqueClipboardImageName(draft: WorkflowDraft, requested: String): String {
    val base = requested.trim().take(48).ifEmpty { "Image" }
    val used = draft.activeAssetNames().mapTo(mutableSetOf()) { it.lowercase() }
    if (base.lowercase() !in used) return base
    var suffix = 2
    while ("$base $suffix".lowercase() in used) suffix++
    return "$base $suffix"
}

internal data class ClipboardImageImport(
    val assetIds: Map<String, AssetId>,
    val assets: List<Pair<ClipboardImage, BundleAsset>>,
)

internal fun importClipboardImages(
    images: List<ClipboardImage>,
    existingAssets: List<WorkflowAssetDescriptor>,
    codec: EncryptedTemplateAssetCodec,
    readImage: (ClipboardImage) -> ByteArray,
    loadAsset: (AssetId) -> BundleAsset,
): ClipboardImageImport {
    val existingIds = existingAssets.mapTo(mutableSetOf()) { it.assetId }
    val missing = images.filter { it.id !in existingIds }
    val byContent = mutableMapOf<String, AssetId>()
    existingAssets.filter { asset ->
        missing.any { image ->
            image.kind == asset.kind &&
                (image.widthPx == null || asset.widthPx == null || image.widthPx == asset.widthPx) &&
                (image.heightPx == null || asset.heightPx == null || image.heightPx == asset.heightPx)
        }
    }.forEach { descriptor ->
        val asset = loadAsset(descriptor.assetId)
        val decoded = codec.decrypt(asset.assetId, asset.bytes)
        try {
            byContent.putIfAbsent(decoded.contentKey(), asset.assetId)
        } finally {
            decoded.clear()
        }
    }
    val ids = mutableMapOf<String, AssetId>()
    val imported = mutableListOf<Pair<ClipboardImage, BundleAsset>>()
    missing.forEach { image ->
        val encrypted = readImage(image)
        try {
            val decoded = codec.decrypt(image.id, encrypted)
            try {
                val key = decoded.contentKey()
                val id = byContent.getOrPut(key) {
                    val newId = AssetId(WorkflowIds.newUuid())
                    imported += image to BundleAsset(newId, image.kind, image.mimeType, codec.encrypt(newId, decoded), true, decoded.widthPx, decoded.heightPx)
                    newId
                }
                ids[image.id.value] = id
            } finally {
                decoded.clear()
            }
        } finally {
            encrypted.fill(0)
        }
    }
    return ClipboardImageImport(ids, imported)
}

private fun DecodedTemplate.contentKey(): String {
    val rgba = copyRgbaBytes()
    return try {
        // This fingerprint is only kept for the current paste; no image identity is persisted or shared.
        "$widthPx:$heightPx:" + MessageDigest.getInstance("SHA-256").digest(rgba).joinToString("") { "%02x".format(it) }
    } finally {
        rgba.fill(0)
    }
}

internal fun List<WorkflowStep>.clipboardImageIds(): Set<String> = buildSet {
    fun condition(value: Condition) {
        value.backgroundAssetId()?.let { add(it.value) }
        when (value) {
            is ImagePresentCondition -> value.assetId?.let { add(it.value) }
            is ImageAbsentCondition -> value.assetId?.let { add(it.value) }
            is AndCondition -> value.conditions.forEach(::condition)
            is OrCondition -> value.conditions.forEach(::condition)
            is NotCondition -> condition(value.condition)
            else -> Unit
        }
    }
    fun step(value: WorkflowStep) {
        value.backgroundAssetId()?.let { add(it.value) }
        when (value) {
            is com.veyarc.model.FindImage -> condition(value.condition)

            is WaitUntil -> condition(value.condition)

            is IfElse -> {
                condition(value.condition)
                value.thenSteps.forEach(::step)
                value.elseSteps.forEach(::step)
            }

            is Repeat -> value.bodySteps.forEach(::step)

            is WhileStep -> {
                condition(value.condition)
                value.bodySteps.forEach(::step)
            }

            else -> Unit
        }
    }
    forEach(::step)
}

internal fun remapClipboardSteps(steps: List<WorkflowStep>, assetIds: Map<String, AssetId>): List<WorkflowStep> {
    val stepIds = mutableMapOf<String, String>()
    fun assign(value: WorkflowStep) {
        stepIds[value.id] = WorkflowIds.newUuid()
        when (value) {
            is IfElse -> {
                value.thenSteps.forEach(::assign)
                value.elseSteps.forEach(::assign)
            }

            is Repeat -> value.bodySteps.forEach(::assign)

            is WhileStep -> value.bodySteps.forEach(::assign)

            else -> Unit
        }
    }
    steps.forEach(::assign)
    fun condition(value: Condition): Condition = when (value) {
        is com.veyarc.model.CapturedImageCondition -> value.copy(backgroundAssetId = value.backgroundAssetId?.let { assetIds[it.value] ?: it })
        is com.veyarc.model.PixelColorCondition -> value.copy(backgroundAssetId = value.backgroundAssetId?.let { assetIds[it.value] ?: it })
        is ImagePresentCondition -> value.copy(assetId = value.assetId?.let { assetIds[it.value] ?: it }, backgroundAssetId = value.backgroundAssetId?.let { assetIds[it.value] ?: it })
        is ImageAbsentCondition -> value.copy(assetId = value.assetId?.let { assetIds[it.value] ?: it }, backgroundAssetId = value.backgroundAssetId?.let { assetIds[it.value] ?: it })
        is StepResultCondition -> value.copy(stepId = stepIds[value.stepId] ?: value.stepId)
        is AndCondition -> value.copy(conditions = value.conditions.map(::condition))
        is OrCondition -> value.copy(conditions = value.conditions.map(::condition))
        is NotCondition -> value.copy(condition = condition(value.condition))
        else -> value
    }
    fun step(value: WorkflowStep): WorkflowStep {
        val id = requireNotNull(stepIds[value.id])
        return when (value) {
            is com.veyarc.model.FindImage -> value.copy(id = id, condition = condition(value.condition) as ImagePresentCondition)
            is com.veyarc.model.CaptureImage -> value.copy(id = id)
            is com.veyarc.model.TapPoint -> value.copy(id = id)
            is com.veyarc.model.TapRegion -> value.copy(id = id)
            is com.veyarc.model.LongPress -> value.copy(id = id)
            is com.veyarc.model.SwipePath -> value.copy(id = id)
            is com.veyarc.model.Wait -> value.copy(id = id)
            is com.veyarc.model.StageBoundary -> value.copy(id = id)
            is Jump -> value.copy(id = id, targetStepId = stepIds[value.targetStepId] ?: value.targetStepId)
            is WaitUntil -> value.copy(id = id, condition = condition(value.condition))
            is IfElse -> value.copy(id = id, condition = condition(value.condition), thenSteps = value.thenSteps.map(::step), elseSteps = value.elseSteps.map(::step))
            is Repeat -> value.copy(id = id, bodySteps = value.bodySteps.map(::step))
            is WhileStep -> value.copy(id = id, condition = condition(value.condition), bodySteps = value.bodySteps.map(::step))
            is com.veyarc.model.LaunchApp -> value.copy(id = id)
            is com.veyarc.model.Stop -> value.copy(id = id)
            is com.veyarc.model.SetVariable -> value.copy(id = id)
        }.withBackgroundAsset(value.backgroundAssetId()?.let { assetIds[it.value] ?: it })
    }
    return steps.map(::step)
}

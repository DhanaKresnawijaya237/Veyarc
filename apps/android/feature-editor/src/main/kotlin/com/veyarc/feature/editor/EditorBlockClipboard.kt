package com.veyarc.feature.editor

import android.content.Context
import com.veyarc.data.storage.AesGcmFileCodec
import com.veyarc.data.storage.AndroidKeystoreKeySource
import com.veyarc.data.storage.EncryptedFileStore
import com.veyarc.data.storage.EncryptedTemplateAssetCodec
import com.veyarc.data.storage.TEMPLATE_ASSET_KEY_ALIAS
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
import com.veyarc.model.bundle.BundleAsset
import kotlinx.serialization.Serializable
import java.io.File

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

    fun read(): BlockClipboardEntry? {
        val fileName = prefs.getString("current", null) ?: return null
        val bytes = files.read(fileName, AAD)
        return WorkflowJson.decodeStrict(BlockClipboardEntry.serializer(), bytes.decodeToString()).also {
            require(it.version == 1 && it.steps.isNotEmpty()) { "Unsupported block clipboard" }
        }
    }

    fun replace(orientation: DisplayOrientation, steps: List<WorkflowStep>, assets: List<Pair<BundleAsset, String>>) {
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

    fun importImage(image: ClipboardImage, newId: AssetId): BundleAsset {
        val encrypted = files.read(image.fileName, AAD)
        val decoded = templateCodec.decrypt(image.id, encrypted)
        return try {
            BundleAsset(newId, image.kind, image.mimeType, templateCodec.encrypt(newId, decoded), true, image.widthPx, image.heightPx)
        } finally {
            decoded.clear()
            encrypted.fill(0)
        }
    }

    private companion object {
        val AAD = "editor-block-clipboard:v1".encodeToByteArray()
    }
}

internal fun List<WorkflowStep>.clipboardImageIds(): Set<String> = buildSet {
    fun condition(value: Condition) {
        when (value) {
            is ImagePresentCondition -> add(value.assetId.value)
            is ImageAbsentCondition -> add(value.assetId.value)
            is AndCondition -> value.conditions.forEach(::condition)
            is OrCondition -> value.conditions.forEach(::condition)
            is NotCondition -> condition(value.condition)
            else -> Unit
        }
    }
    fun step(value: WorkflowStep) {
        when (value) {
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
        is ImagePresentCondition -> value.copy(assetId = assetIds[value.assetId.value] ?: value.assetId)
        is ImageAbsentCondition -> value.copy(assetId = assetIds[value.assetId.value] ?: value.assetId)
        is StepResultCondition -> value.copy(stepId = stepIds[value.stepId] ?: value.stepId)
        is AndCondition -> value.copy(conditions = value.conditions.map(::condition))
        is OrCondition -> value.copy(conditions = value.conditions.map(::condition))
        is NotCondition -> value.copy(condition = condition(value.condition))
        else -> value
    }
    fun step(value: WorkflowStep): WorkflowStep {
        val id = requireNotNull(stepIds[value.id])
        return when (value) {
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
        }
    }
    return steps.map(::step)
}

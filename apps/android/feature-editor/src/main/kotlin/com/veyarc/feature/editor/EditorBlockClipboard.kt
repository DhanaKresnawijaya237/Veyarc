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
import com.veyarc.model.ReusableSection
import com.veyarc.model.RunSection
import com.veyarc.model.StepResultCondition
import com.veyarc.model.WaitUntil
import com.veyarc.model.WhileStep
import com.veyarc.model.WorkflowIds
import com.veyarc.model.WorkflowJson
import com.veyarc.model.WorkflowStep
import com.veyarc.model.backgroundAssetId
import com.veyarc.model.bundle.BundleAsset
import com.veyarc.model.resolveBody
import com.veyarc.model.reusableSections
import com.veyarc.model.walkSteps
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
    val sourceWorkflowId: String? = null,
    val linkedSectionIds: Set<String> = emptySet(),
    val variables: Map<String, com.veyarc.model.VariableDeclaration> = emptyMap(),
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

    fun replace(orientation: DisplayOrientation, steps: List<WorkflowStep>, assets: List<Pair<BundleAsset, String>>, sourceWorkflowId: String? = null, linkedSectionIds: Set<String> = emptySet(), variables: Map<String, com.veyarc.model.VariableDeclaration> = emptyMap()) = synchronized(lock) {
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
            val entry = BlockClipboardEntry(orientation = orientation, steps = steps, images = images, sourceWorkflowId = sourceWorkflowId, linkedSectionIds = linkedSectionIds, variables = variables)
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
                            val copied = steps.withSectionDependencies(source.steps)
                            val images = copied.clipboardImageIds().map { id ->
                                val assetId = AssetId(id)
                                val asset = source.assets.firstOrNull { it.assetId == assetId }
                                    ?: repository.loadRevisionAsset(source.workflowId, requireNotNull(source.lastSavedRevision), assetId)
                                asset to (source.assetNames[id] ?: "Image")
                            }
                            EditorBlockClipboard(app).replace(
                                source.viewport.recordedOrientation,
                                copied,
                                images,
                                source.workflowId,
                                source.steps.reusableSections().values.filter { it.isLinked && copied.any { item -> item.id == it.id } }.mapTo(mutableSetOf()) { it.id },
                                source.variables.filterKeys { it in source.copy(steps = copied).usedVariableNames() },
                            )
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
                            require(entry.variables.none { (name, declaration) -> expected.variables[name]?.type?.let { it != declaration.type } == true }) { "A variable has a different type in this workflow" }
                            val reusedLinks = entry.linkedSectionIds.takeIf { entry.sourceWorkflowId == expected.workflowId }.orEmpty()
                            var result = expected.pasteSectionSteps(path, index, entry.steps, loaded.assetIds, reusedLinks).copy(variables = entry.variables + expected.variables)
                            loaded.assets.forEach { (image, asset) ->
                                result = result.addAsset(asset).renameAsset(asset.assetId, uniqueClipboardImageName(result, image.name))
                            }
                            result to (required - expected.assetDescriptors.map { it.assetId.value }.toSet() - loaded.assetIds.keys)
                        } finally {
                            repository.close()
                        }
                    }
                    check(session.completePaste(expected, updated)) { "The destination workflow was closed or replaced." }
                    copyMessage = if (omitted.isNotEmpty()) {
                        "Pasted blocks. Add the missing images before saving."
                    } else if (entry.linkedSectionIds.isNotEmpty() && entry.sourceWorkflowId != expected.workflowId) {
                        "Pasted blocks. Linked ranges are saved as copies in this workflow."
                    } else {
                        "Pasted ${entry.steps.size} blocks."
                    }
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

            is ReusableSection -> value.bodySteps.forEach(::step)

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

internal fun remapClipboardSteps(steps: List<WorkflowStep>, assetIds: Map<String, AssetId>, existingSectionIds: Map<String, String> = emptyMap()): List<WorkflowStep> {
    val stepIds = existingSectionIds.toMutableMap()
    fun assign(value: WorkflowStep) {
        stepIds[value.id] = WorkflowIds.newUuid()
        when (value) {
            is IfElse -> {
                value.thenSteps.forEach(::assign)
                value.elseSteps.forEach(::assign)
            }

            is ReusableSection -> value.bodySteps.forEach(::assign)

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
            is ReusableSection -> value.copy(id = id, bodySteps = value.bodySteps.map(::step))
            is RunSection -> value.copy(id = id, sectionId = stepIds[value.sectionId] ?: value.sectionId)
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
            is com.veyarc.model.BreakLoop -> value.copy(id = id)
            is com.veyarc.model.ContinueLoop -> value.copy(id = id)
            is com.veyarc.model.ReturnSection -> value.copy(id = id)
            is com.veyarc.model.Stop -> value.copy(id = id)
            is com.veyarc.model.SetVariable -> value.copy(id = id)
        }.withBackgroundAsset(value.backgroundAssetId()?.let { assetIds[it.value] ?: it })
    }
    return steps.map(::step)
}

internal fun List<WorkflowStep>.withSectionDependencies(source: List<WorkflowStep>): List<WorkflowStep> {
    val definitions = source.reusableSections()
    val included = filterIsInstance<ReusableSection>().mapTo(mutableSetOf()) { it.id }
    val snapshots = linkedMapOf<String, ReusableSection>()
    val visiting = mutableSetOf<String>()
    val pending = java.util.ArrayDeque<Pair<String, Boolean>>()
    walkSteps().filterIsInstance<RunSection>().forEach { pending.addLast(it.sectionId to false) }
    filterIsInstance<ReusableSection>().forEach { pending.addLast(it.id to false) }
    while (pending.isNotEmpty()) {
        val (id, finished) = pending.removeLast()
        if (id in snapshots) continue
        val definition = requireNotNull(definitions[id]) { "This saved sequence was removed" }
        val body = requireNotNull(definition.resolveBody(source)) { "Choose valid start and end blocks before copying" }
        if (finished) {
            val owned = if (definition.isLinked) remapClipboardSteps(body, emptyMap(), definitions.keys.associateWith { it }) else body
            snapshots[id] = definition.copy(bodySteps = owned, startStepId = null, endStepId = null)
            visiting.remove(id)
        } else {
            require(visiting.add(id)) { "Fix circular saved blocks before copying" }
            pending.addLast(id to true)
            body.walkSteps().filterIsInstance<RunSection>().forEach { pending.addLast(it.sectionId to false) }
        }
    }
    return map { if (it is ReusableSection) snapshots.getValue(it.id) else it } + snapshots.values.filterNot { it.id in included }
}

internal fun WorkflowDraft.pasteSectionSteps(path: StepListPath, index: Int, copied: List<WorkflowStep>, assetIds: Map<String, AssetId>, linkedSectionIds: Set<String> = emptySet()): WorkflowDraft {
    val existing = steps.reusableSections()
    val reused = copied.filterIsInstance<ReusableSection>().filter { existing[it.id] == it || (it.id in linkedSectionIds && existing[it.id]?.isLinked == true) }.associate { it.id to it.id }
    val incoming = remapClipboardSteps(copied.filterNot { it is ReusableSection && it.id in reused }, assetIds, reused)
    val usedNames = existing.values.mapTo(mutableSetOf()) { it.name.trim().lowercase() }
    val definitions = incoming.filterIsInstance<ReusableSection>().map { section ->
        val base = section.name.take(72)
        val name = generateSequence(1) { it + 1 }.map { if (it == 1) base else "$base $it" }.first { it.lowercase() !in usedNames }
        usedNames += name.lowercase()
        section.copy(name = name)
    }
    val blocks = incoming.filterNot { it is ReusableSection }
    val withDefinitions = insertSteps(StepListPath(), steps.size, definitions)
    return withDefinitions.insertSteps(path, index, blocks)
}

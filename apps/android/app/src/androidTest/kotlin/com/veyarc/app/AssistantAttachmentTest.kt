package com.veyarc.app

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.center
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.veyarc.app.ui.theme.VeyarcTheme
import com.veyarc.data.storage.AndroidKeystoreKeySource
import com.veyarc.data.storage.ChatRepository
import com.veyarc.data.storage.EncryptedTemplateAssetCodec
import com.veyarc.data.storage.TEMPLATE_ASSET_KEY_ALIAS
import com.veyarc.feature.assistant.AssistantAttachmentImporter
import com.veyarc.feature.assistant.AssistantImageAddition
import com.veyarc.feature.assistant.AssistantImagePurpose
import com.veyarc.feature.assistant.AssistantOutcome
import com.veyarc.feature.assistant.AssistantPreviewContext
import com.veyarc.feature.assistant.AssistantPreviewScreen
import com.veyarc.feature.assistant.AssistantPreviewState
import com.veyarc.feature.assistant.AssistantResponse
import com.veyarc.feature.assistant.PreviewReplyStatus
import com.veyarc.feature.assistant.ProposalApplyState
import com.veyarc.feature.editor.WorkflowDraft
import com.veyarc.feature.editor.WorkflowEditorSession
import com.veyarc.model.NormalizedRect
import com.veyarc.model.WorkflowIds
import com.veyarc.model.WorkflowJson
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class AssistantAttachmentTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun imageFile(): File {
        val file = File.createTempFile("attachment-test-", ".png", context.cacheDir)
        val bitmap = Bitmap.createBitmap(100, 200, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        return file
    }

    @Test
    fun composerImageCanExpandRotateAnnotateSendAndBranchWithoutChangingOriginal() {
        val file = imageFile()
        val importer = AssistantAttachmentImporter(context)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val state = AssistantPreviewState(scope = scope, attachments = importer)
        compose.runOnUiThread { state.open(AssistantPreviewContext()) }
        val restoration = StateRestorationTester(compose)
        restoration.setContent { VeyarcTheme(darkTheme = true) { AssistantPreviewScreen(state) } }
        compose.runOnUiThread { state.importAttachments(listOf(Uri.fromFile(file)), state.selectedId!!) }
        compose.waitUntil(10000) { !state.importingAttachments && state.conversation!!.composerAttachments.size == 1 }
        val original = state.conversation!!.composerAttachments.single()
        val bytes = importer.store.read(original.id)
        compose.onNodeWithTag("attachment-${original.id}").performClick()
        compose.onNodeWithTag("attachment-preview").assertExists()
        compose.onNodeWithText("Crop").performClick()
        compose.onNodeWithText("Rotate").performClick()
        compose.onNodeWithContentDescription("Annotate").performClick()
        compose.onNodeWithTag("attachment-image-editor").performTouchInput { swipe(center - Offset(25f, 0f), center + Offset(25f, 0f), 400) }
        compose.onNodeWithContentDescription("Undo annotation").assertIsEnabled()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("attachment-preview").assertExists()
        compose.onNodeWithContentDescription("Undo annotation").assertIsEnabled()
        compose.onNodeWithText("Use image").performClick()
        compose.waitUntil(10000) { state.conversation!!.composerAttachments.single().id != original.id }
        val edited = state.conversation!!.composerAttachments.single()
        assertEquals(200, edited.width)
        assertEquals(100, edited.height)
        assertArrayEquals(bytes, importer.store.read(original.id))
        assertFalse(bytes.contentEquals(importer.store.read(edited.id)))
        compose.runOnUiThread {
            state.compose("Explain this image")
            state.send()
        }
        compose.waitUntil(10000) { state.conversation!!.turns.lastOrNull()?.status == PreviewReplyStatus.COMPLETE }
        assertTrue(state.conversation!!.composerAttachments.isEmpty())
        val turn = state.conversation!!.turns.single()
        assertEquals(listOf(edited), turn.attachments)
        compose.runOnUiThread { state.branch(turn.id) }
        assertEquals(listOf(edited), state.conversation!!.turns.single().attachments)
        compose.runOnUiThread { state.dispose() }
        scope.cancel()
        file.delete()
    }

    @Test
    fun encryptedAttachmentsRestoreAndClearWithChatWhileDocxAndPdfAreReadable() = runBlocking {
        val file = imageFile()
        val importer = AssistantAttachmentImporter(context)
        val repository = ChatRepository(context)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val state = AssistantPreviewState(repository, scope, attachments = importer)
        val current = AssistantPreviewContext(workflowId = WorkflowIds.newUuid())
        compose.waitUntil(10000) { !state.isLoading && state.pendingWrites == 0 }
        compose.runOnUiThread { state.open(current) }
        compose.waitUntil(10000) { !state.isLoading && state.conversation != null && state.pendingWrites == 0 }
        compose.runOnUiThread { state.importAttachments(listOf(Uri.fromFile(file)), state.selectedId!!) }
        compose.waitUntil(10000) { !state.importingAttachments && state.pendingWrites == 0 && state.conversation!!.composerAttachments.isNotEmpty() }
        val selected = state.conversation!!.composerAttachments.single()
        compose.runOnUiThread { state.dispose() }
        scope.cancel()
        val restoredScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val restored = AssistantPreviewState(repository, restoredScope, attachments = importer)
        compose.waitUntil(10000) { !restored.isLoading && restored.pendingWrites == 0 }
        compose.runOnUiThread { restored.open(current) }
        compose.waitUntil(10000) { !restored.isLoading && restored.conversation != null && restored.pendingWrites == 0 }
        assertEquals(listOf(selected), restored.conversation!!.composerAttachments)
        assertTrue(importer.store.read(selected.id).isNotEmpty())

        val docx = File.createTempFile("attachment-test-", ".docx", context.cacheDir)
        ZipOutputStream(docx.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("word/document.xml"))
            zip.write("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body><w:p><w:r><w:t>Read this document</w:t></w:r></w:p></w:body></w:document>".toByteArray())
            zip.closeEntry()
        }
        val document = withContext(Dispatchers.IO) { importer.import(Uri.fromFile(docx)) }
        assertTrue(importer.content(document).text!!.contains("Read this document"))
        val pdf = File.createTempFile("attachment-test-", ".pdf", context.cacheDir)
        val documentPdf = PdfDocument()
        try {
            val page = documentPdf.startPage(PdfDocument.PageInfo.Builder(100, 200, 1).create())
            page.canvas.drawColor(Color.WHITE)
            documentPdf.finishPage(page)
            pdf.outputStream().use(documentPdf::writeTo)
        } finally {
            documentPdf.close()
        }
        val pdfAttachment = withContext(Dispatchers.IO) { importer.import(Uri.fromFile(pdf)) }
        assertEquals(1, pdfAttachment.pageCount)
        assertEquals(1, importer.content(pdfAttachment).images.size)
        compose.runOnUiThread { restored.clearHistory(workflowOnly = true) }
        compose.waitUntil(10000) { restored.pendingWrites == 0 }
        assertTrue(runCatching { importer.store.read(selected.id) }.isFailure)
        compose.runOnUiThread { restored.dispose() }
        restoredScope.cancel()
        repository.close()
        listOf(file, docx, pdf).forEach(File::delete)
    }

    @Test
    fun proposedCropCanBeInspectedAndCancelledBeforeAnyDraftChange() = runBlocking {
        val file = imageFile()
        val importer = AssistantAttachmentImporter(context)
        val editor = WorkflowEditorSession()
        compose.runOnUiThread { editor.open(WorkflowDraft.newDefault().buildBundle().bundle!!) }
        val bridge = AssistantDraftBridge(editor)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val prepare = CompletableDeferred<Unit>()
        lateinit var state: AssistantPreviewState
        compose.runOnUiThread {
            state = AssistantPreviewState(
                scope = scope,
                compileCandidate = ::assistantCompilationIssues,
                applyCandidate = bridge::apply,
                attachments = importer,
                contextSource = bridge::capture,
                applyImageCandidate = { binding, base, candidate, additions, sources ->
                    prepare.await()
                    bridge.applyImages(binding, base, candidate, additions, sources, importer.store)
                },
                providerCall = {
                    val chat = state.conversation!!
                    val turn = chat.turns.last()
                    val addition = AssistantImageAddition(turn.attachments.single().id, "review-target", "Button", crop = NormalizedRect(0.25, 0.25, 0.5, 0.5))
                    val candidate = editor.draft!!.buildBundle().document!!.let { it.copy(assets = it.assets + addition.reference()) }
                    WorkflowJson.encodeCanonical(AssistantResponse.serializer(), AssistantResponse(1, turn.requestId, chat.id, turn.baseSnapshot!!.snapshotId, AssistantOutcome.PROPOSAL, "Add this button image.", WorkflowJson.format.parseToJsonElement(WorkflowJson.encode(candidate)).jsonObject, listOf(addition))).toByteArray()
                },
            )
            state.open(AssistantPreviewContext(editor.draft!!.workflowId))
            state.enableProvider()
        }
        compose.setContent { VeyarcTheme(darkTheme = true) { AssistantPreviewScreen(state) } }
        compose.runOnUiThread { state.importAttachments(listOf(Uri.fromFile(file)), state.selectedId!!) }
        compose.waitUntil(10000) { !state.importingAttachments && state.conversation!!.composerAttachments.isNotEmpty() }
        compose.runOnUiThread {
            state.compose("Add the button crop to this workflow")
            state.send()
        }
        compose.waitUntil(10000) { state.conversation!!.turns.last().status == PreviewReplyStatus.COMPLETE }
        val turn = state.conversation!!.turns.last()
        assertTrue(state.canApply(turn))
        compose.onNodeWithTag("assistant-review-toggle").performScrollTo().performClick()
        compose.onNodeWithTag("proposal-image-review-target").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Close image review").performClick()
        compose.runOnUiThread { state.applyProposal(turn.id) }
        compose.waitUntil(10000) { state.isApplyingImages }
        compose.runOnUiThread { state.close() }
        prepare.complete(Unit)
        compose.waitUntil(10000) { !state.isApplyingImages }
        assertTrue(editor.draft!!.assets.isEmpty())
        assertEquals(ProposalApplyState.RESERVED, state.conversation!!.turns.last().applyState)
        compose.runOnUiThread {
            state.open(AssistantPreviewContext(editor.draft!!.workflowId))
            state.applyProposal(turn.id)
        }
        assertFalse(state.canApply(state.conversation!!.turns.last()))
        assertTrue(editor.draft!!.assets.isEmpty())
        compose.runOnUiThread { state.dispose() }
        scope.cancel()
        file.delete()
        Unit
    }

    @Test
    fun reviewedImageImportAddsEncryptedCropAndRejectsStaleDraft() = runBlocking {
        val file = imageFile()
        val importer = AssistantAttachmentImporter(context)
        val source = withContext(Dispatchers.IO) { importer.import(Uri.fromFile(file)) }
        val editor = WorkflowEditorSession()
        compose.runOnUiThread { editor.open(WorkflowDraft.newDefault().copy(allowedPackagesText = context.packageName).buildBundle().bundle!!) }
        val bridge = AssistantDraftBridge(editor)
        val captured = withContext(Dispatchers.Main) { bridge.capture() }
        val base = editor.draft!!.buildBundle().document!!
        val addition = AssistantImageAddition(source.id, "assistant-test-target", "Target", crop = NormalizedRect(0.25, 0.25, 0.5, 0.5))
        val background = AssistantImageAddition(source.id, "assistant-test-background", "Background", AssistantImagePurpose.BACKGROUND)
        val candidate = WorkflowJson.encode(base.copy(assets = base.assets + listOf(addition.reference(), background.reference())))
        val applied = withContext(Dispatchers.Main) { bridge.applyImages(captured.localBindingId!!, captured.snapshot!!, candidate, listOf(addition, background), listOf(source), importer.store) }
        assertTrue(applied)
        val asset = editor.draft!!.assets.single { it.assetId.value == addition.assetId }
        assertEquals(50, asset.widthPx)
        assertEquals(100, asset.heightPx)
        val decoded = EncryptedTemplateAssetCodec(AndroidKeystoreKeySource(TEMPLATE_ASSET_KEY_ALIAS)).decrypt(asset.assetId, asset.bytes)
        assertEquals(50, decoded.widthPx)
        decoded.clear()
        val reference = editor.draft!!.assets.single { it.assetId.value == background.assetId }
        assertEquals(100, reference.widthPx)
        assertEquals(200, reference.heightPx)
        compose.runOnUiThread { editor.undo() }
        assertTrue(editor.draft!!.assets.isEmpty())
        compose.runOnUiThread { editor.redo() }
        assertEquals(2, editor.draft!!.assets.size)
        val stale = withContext(Dispatchers.Main) { bridge.applyImages(captured.localBindingId!!, captured.snapshot!!, candidate, listOf(addition), listOf(source), importer.store) }
        assertFalse(stale)
        file.delete()
        Unit
    }
}

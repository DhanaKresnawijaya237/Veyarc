package com.veyarc.app

import android.graphics.Bitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.printToString
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.veyarc.app.ui.theme.VeyarcTheme
import com.veyarc.data.storage.AndroidKeystoreKeySource
import com.veyarc.data.storage.ChatRepository
import com.veyarc.data.storage.DecodedTemplate
import com.veyarc.data.storage.EncryptedTemplateAssetCodec
import com.veyarc.data.storage.TEMPLATE_ASSET_KEY_ALIAS
import com.veyarc.data.storage.WorkflowRepository
import com.veyarc.feature.assistant.AssistantOutcome
import com.veyarc.feature.assistant.AssistantPreviewHost
import com.veyarc.feature.assistant.AssistantPreviewState
import com.veyarc.feature.assistant.PreviewScenario
import com.veyarc.feature.editor.WorkflowEditorScreen
import com.veyarc.feature.editor.WorkflowEditorSession
import com.veyarc.model.AssetId
import com.veyarc.model.AssetKind
import com.veyarc.model.DurationMillis
import com.veyarc.model.Wait
import com.veyarc.model.bundle.BundleAsset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AssistantCreationSaveTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun creationClarifiesThenExplicitSavesPreserveChatAndUnloadedImagesAndRejectConflict() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = WorkflowRepository(context)
        val chats = ChatRepository(context)
        val owner = SupervisorJob()
        val editor = WorkflowEditorSession()
        val bridge = AssistantDraftBridge(editor)
        lateinit var state: AssistantPreviewState
        var workflowId: String? = null
        try {
            compose.runOnIdle { state = AssistantPreviewState(chats, CoroutineScope(owner + Dispatchers.Main), ::assistantCompilationIssues, bridge::apply, contextSource = bridge::capture) }
            compose.setContent {
                VeyarcTheme {
                    AssistantPreviewHost(true, state, editor.assistantContext()) { ask ->
                        WorkflowEditorScreen(repository, editor, onRunWorkflow = { error("Save must not run") }, onOpenWorkflowManager = {}, onAskAi = ask?.let { { state.open(editor.assistantContext()) } })
                    }
                }
            }
            compose.onNodeWithText("Create with AI").performClick()
            compose.waitUntil(10000) { state.canChange && state.conversation != null }
            workflowId = editor.draft!!.workflowId
            val chatId = state.selectedId
            val draftId = state.conversation!!.draftId
            compose.onNodeWithText("Setup needed:", substring = true).assertIsDisplayed()
            assertTrue(repository.listRevisions(workflowId).isEmpty())
            capture("creation-setup")
            completeSample(state)
            assertEquals(AssistantOutcome.CLARIFICATION, state.conversation!!.turns.last().outcome)
            assertTrue(editor.draft!!.steps.isEmpty())
            compose.onNodeWithContentDescription("Back to editor").performClick()
            val codec = EncryptedTemplateAssetCodec(AndroidKeystoreKeySource(TEMPLATE_ASSET_KEY_ALIAS))
            val pixels = ByteArray(16) { it.toByte() }
            val template = DecodedTemplate(2, 2, pixels)
            val assetId = AssetId("image")
            val asset = try {
                BundleAsset(assetId, AssetKind.IMAGE_TEMPLATE, "image/png", codec.encrypt(assetId, template), true, 2, 2)
            } finally {
                template.clear()
            }
            compose.runOnIdle { editor.update(editor.draft!!.copy(allowedPackagesText = "com.example.target", steps = listOf(Wait(UUID.randomUUID().toString(), DurationMillis(500)))).addAsset(asset)) }
            compose.onNodeWithContentDescription("Ask AI").performClick()
            compose.waitUntil(10000) { state.canChange }
            completeSample(state)
            compose.onNodeWithText("View proposal validation").performScrollTo().performClick()
            compose.onNodeWithText("Apply to draft").performScrollTo().performClick()
            compose.waitUntil(10000) { state.canChange }
            assertTrue(repository.listRevisions(workflowId).isEmpty())
            compose.onNodeWithText("Done").performClick()
            compose.onNodeWithContentDescription("Back to editor").performClick()
            compose.onNodeWithContentDescription("Save revision 1").performClick()
            compose.waitUntil(10000) { !editor.isSaving }
            assertEquals(compose.onRoot().printToString(), 1, editor.draft?.lastSavedRevision)
            assertEquals(workflowId, editor.draft!!.workflowId)
            assertTrue(editor.draft!!.assets.isEmpty())
            assertEquals(1, editor.draft!!.assetDescriptors.size)
            assertFalse(editor.isDirty)
            capture("creation-saved")
            compose.onNodeWithContentDescription("Ask AI").performClick()
            compose.waitUntil(10000) { state.canChange }
            assertEquals(chatId, state.selectedId)
            assertEquals(draftId, state.conversation!!.draftId)
            assertEquals(workflowId, chats.load().single { it.id == chatId }.workflowId)
            compose.onNodeWithContentDescription("Back to editor").performClick()
            compose.runOnIdle { editor.update(editor.draft!!.copy(name = "Second draft", steps = editor.draft!!.steps + Wait(UUID.randomUUID().toString(), DurationMillis(100)))) }
            compose.onNodeWithContentDescription("Ask AI").performClick()
            compose.waitUntil(10000) { state.canChange }
            completeSample(state)
            compose.runOnIdle { state.applyProposal(state.conversation!!.turns.last().id) }
            compose.waitUntil(10000) { state.canChange }
            compose.onNodeWithContentDescription("Back to editor").performClick()
            compose.onNodeWithContentDescription("Save revision 2").performClick()
            compose.waitUntil(10000) { !editor.isSaving }
            assertEquals(compose.onRoot().printToString(), 2, editor.draft?.lastSavedRevision)
            for (revision in 1..2) {
                val loaded = codec.decrypt(assetId, repository.loadRevisionAsset(workflowId, revision, assetId).bytes)
                try {
                    assertArrayEquals(pixels, loaded.copyRgbaBytes())
                } finally {
                    loaded.clear()
                }
            }
            val newer = repository.loadRevisionDocument(workflowId, 2).document.copy(revision = 3, name = "Concurrent revision")
            repository.saveRevisionFromExistingAssets(workflowId, 2, newer)
            compose.runOnIdle { editor.update(editor.draft!!.copy(name = "Keep this unsaved edit")) }
            compose.onNodeWithContentDescription("Save revision 3").performClick()
            compose.waitUntil(10000) { !editor.isSaving }
            compose.onNodeWithText("A newer saved revision exists.", substring = true).assertExists()
            assertEquals("Keep this unsaved edit", editor.draft!!.name)
            assertEquals(2, editor.draft!!.lastSavedRevision)
            assertEquals("Concurrent revision", repository.loadRevisionDocument(workflowId, 3).document.name)
            assertEquals(3, repository.listRevisions(workflowId).size)
        } finally {
            owner.cancel()
            workflowId?.let { id -> if (repository.listRevisions(id).isNotEmpty()) repository.deleteWorkflow(id) else chats.load().filter { it.workflowId == id }.forEach { chats.delete(it.id) } }
            com.veyarc.automation.android.ReplayWorkflowCatalog.replace(context, com.veyarc.automation.android.ReplayWorkflowCatalog.snapshot().filterNot { it.workflowId == workflowId })
            chats.close()
            repository.close()
        }
    }

    private fun capture(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory = java.io.File(instrumentation.targetContext.cacheDir, "assistant-preview-qa").apply { mkdirs() }
        java.io.File(directory, "$name.png").outputStream().use { instrumentation.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun completeSample(state: AssistantPreviewState) {
        compose.runOnIdle {
            state.scenario = PreviewScenario.VALIDATED_PROPOSAL
            state.compose("Propose a workflow")
            state.send()
        }
        compose.waitUntil(10000) { state.requestId != null || state.conversation!!.turns.last().outcome != null }
        compose.runOnIdle { state.requestId?.let(state::advance) }
        compose.waitUntil(10000) { state.canChange && state.requestId == null }
    }
}

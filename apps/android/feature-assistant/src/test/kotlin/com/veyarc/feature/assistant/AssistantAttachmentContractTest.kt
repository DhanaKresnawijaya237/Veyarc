package com.veyarc.feature.assistant

import com.veyarc.model.DisplayOrientation
import com.veyarc.model.DurationMillis
import com.veyarc.model.NormalizedRect
import com.veyarc.model.RunLimits
import com.veyarc.model.ViewportSpec
import com.veyarc.model.Wait
import com.veyarc.model.WorkflowJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class AssistantAttachmentContractTest {
    @Test
    fun markdownRemainsTextForTransportAndOldChatsUseMarkdownPreview() {
        val markdown = AssistantAttachment(UUID.randomUUID().toString(), "notes.md", AttachmentKind.MARKDOWN, "text/markdown", 5)
        markdown.validate()
        assertTrue(markdown.isMarkdown)
        assertFalse(markdown.needsVision)
        assertTrue(markdown.copy(kind = AttachmentKind.TEXT, mimeType = "text/plain").isMarkdown)
        val request = DeepSeekRequest.create("r", "c", "draft:d", "Read", null, model = DeepSeekModel.PRO, attachments = listOf(AttachmentContent(markdown, text = "# Hi")))
        assertTrue(request.body.toString(Charsets.UTF_8).contains("# Hi"))
    }
    private val image = AssistantAttachment(UUID.randomUUID().toString(), "Screenshot.png", AttachmentKind.IMAGE, "image/png", 3, 100, 200)
    private val base = AssistantContextSnapshot.capture(
        AssistantDraftContext(
            UUID.randomUUID().toString(), null, null, "Workflow", "com.example.app",
            ViewportSpec(recordedWidthPx = 1080, recordedHeightPx = 2400, recordedDensityDpi = 420, recordedOrientation = DisplayOrientation.PORTRAIT), RunLimits(100, 60_000, 100, 8, 128, 30_000), emptyMap(), emptyList(),
            listOf(Wait(UUID.randomUUID().toString(), DurationMillis(60_000))),
        ),
    )

    @Test
    fun multimodalRequestSendsImagesAndTextOnlyInUserMessagesAndKeepsHistory() {
        val document = AssistantAttachment(UUID.randomUUID().toString(), "notes.md", AttachmentKind.TEXT, "text/plain", 5)
        val imageContent = AttachmentContent(image, images = listOf(byteArrayOf(1, 2, 3)))
        val request = DeepSeekRequest.create(
            "request",
            "chat",
            base.snapshotId,
            "Use these",
            base,
            listOf(DeepSeekHistoryTurn("Earlier image", AssistantResponse(1, "old", "chat", base.snapshotId, AssistantOutcome.EXPLANATION, "Earlier reply"), listOf(imageContent))),
            attachments = listOf(imageContent, AttachmentContent(document, text = "hello")),
        )
        val messages = WorkflowJson.format.parseToJsonElement(request.body.toString(Charsets.UTF_8)).jsonObject.getValue("messages") as JsonArray
        assertEquals(4, messages.size)
        assertTrue(messages.first().jsonObject.getValue("content") is JsonPrimitive)
        assertTrue(messages[1].jsonObject.getValue("content") is JsonArray)
        assertTrue(messages[2].jsonObject.getValue("content") is JsonPrimitive)
        val blocks = messages.last().jsonObject.getValue("content") as JsonArray
        assertEquals("data:image/png;base64,AQID", blocks[2].jsonObject.getValue("image_url").jsonObject.getValue("url").jsonPrimitive.content)
        assertTrue(blocks.last().jsonObject.getValue("text").jsonPrimitive.content.endsWith("hello"))
        assertTrue(blocks[1].jsonObject.getValue("text").jsonPrimitive.content.contains("100 × 200"))
        assertTrue(runCatching { DeepSeekRequest.create("r", "c", "draft:d", "Look", null, model = DeepSeekModel.PRO, attachments = listOf(imageContent)) }.isFailure)
        assertTrue(runCatching { DeepSeekRequest.create("r", "c", "draft:d", "Read", null, model = DeepSeekModel.PRO, attachments = listOf(AttachmentContent(document, text = "hello"))) }.isSuccess)
    }

    @Test
    fun imageProposalRequiresSubmittedSourceAndPreservesExistingCatalog() {
        val original = AssistantCandidateValidator.baseDocument(base)
        val addition = AssistantImageAddition(image.id, "new-target", "Sign in", crop = NormalizedRect(0.1, 0.2, 0.3, 0.4))
        val candidate = WorkflowJson.encode(original.copy(assets = listOf(addition.reference())))
        val checked = AssistantCandidateValidator.validate(base, candidate, { emptyList() }, listOf(addition), listOf(image))
        assertTrue(checked.isValid)
        assertEquals(listOf(addition), checked.imageAdditions)
        assertTrue(AssistantProposalReview.changes(base, checked).any { it.label == "Cropped image" && it.after!!.contains("Sign in") })
        assertFalse(AssistantCandidateValidator.validate(base, candidate, { emptyList() }, listOf(addition), emptyList()).isValid)
        assertFalse(AssistantCandidateValidator.validate(base, candidate, { emptyList() }).isValid)
        assertFalse(AssistantCandidateValidator.validate(base, candidate, { emptyList() }, listOf(addition.copy(assetId = "other")), listOf(image)).isValid)
        val background = addition.copy(purpose = AssistantImagePurpose.BACKGROUND)
        assertTrue(runCatching { background.validate() }.isFailure)
        val response = AssistantResponse(1, "r", "c", base.snapshotId, AssistantOutcome.PROPOSAL, "Add this image", WorkflowJson.format.parseToJsonElement(candidate).jsonObject, listOf(addition))
        assertEquals(response, AssistantResponseCodec.decode(AssistantResponseCodec.encode(response), "r", "c", base.snapshotId))
    }

    @Test
    fun textDecodingRejectsBinaryAndMalformedTextAndDocxDeclarations() {
        assertEquals("Hi", AssistantAttachmentImporter.decodeText("Hi".toByteArray()))
        assertEquals("Hi", AssistantAttachmentImporter.decodeText(byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 72, 0, 105, 0)))
        assertTrue(runCatching { AssistantAttachmentImporter.decodeText(byteArrayOf(0xC3.toByte(), 0x28)) }.isFailure)
        assertTrue(runCatching { AssistantAttachmentImporter.decodeText(byteArrayOf(0)) }.isFailure)
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("word/document.xml"))
            zip.write("<!DOCTYPE foo [<!ENTITY data SYSTEM 'file:///etc/passwd'>]><foo>&data;</foo>".toByteArray())
            zip.closeEntry()
        }
        val error = runCatching { AssistantAttachmentImporter.documentText(output.toByteArray()) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException && error.message!!.contains("unsupported XML"))
    }
}

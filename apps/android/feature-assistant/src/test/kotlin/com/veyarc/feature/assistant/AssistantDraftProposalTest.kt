package com.veyarc.feature.assistant

import com.veyarc.model.DisplayOrientation
import com.veyarc.model.DurationMillis
import com.veyarc.model.PackageName
import com.veyarc.model.RunLimits
import com.veyarc.model.ViewportSpec
import com.veyarc.model.Wait
import com.veyarc.model.WorkflowDocument
import com.veyarc.model.WorkflowJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class AssistantDraftProposalTest {
    @Test
    fun unfinishedDraftCanReceiveAValidatedRepairWithoutEnablingDrafting() {
        val snapshot = AssistantContextSnapshot.capture(
            AssistantDraftContext(
                UUID.randomUUID().toString(), null, null, "", "com.example.app",
                ViewportSpec(recordedWidthPx = 1080, recordedHeightPx = 2400, recordedDensityDpi = 420, recordedOrientation = DisplayOrientation.PORTRAIT),
                RunLimits(100, 60_000, 100, 8, 128, 30_000), emptyMap(), emptyList(), emptyList(),
            ),
        )
        val request = DeepSeekRequest.create("r", "c", snapshot.snapshotId, "It should wait a minute here", snapshot)
        val messages = WorkflowJson.format.parseToJsonElement(request.body.toString(Charsets.UTF_8)).jsonObject.getValue("messages") as JsonArray
        val current = WorkflowJson.format.parseToJsonElement(messages.last().jsonObject.getValue("content").jsonPrimitive.content).jsonObject
        assertFalse(current.containsKey("proposalAllowed"))
        assertTrue(messages.first().jsonObject.getValue("content").jsonPrimitive.content.contains("no special phrase to enable it"))
        val base = AssistantCandidateValidator.baseDraft(snapshot)
        var compiled = false
        val candidate = WorkflowDocument(base.schemaVersion, base.workflowId, 1, "Wait a minute", listOf(PackageName("com.example.app")), base.viewport, base.runLimits, base.variables, base.assets, listOf(Wait(UUID.randomUUID().toString(), DurationMillis(60_000))))
        assertTrue(
            AssistantCandidateValidator.validate(snapshot, WorkflowJson.encode(candidate), {
                compiled = true
                emptyList()
            }).isValid,
        )
        assertTrue(compiled)
        val check = AssistantCandidateValidator.validate(snapshot, WorkflowJson.encode(candidate), { emptyList() })
        assertTrue(AssistantProposalReview.changes(snapshot, check).any { it.label.startsWith("Added block") })
        val json = WorkflowJson.format.parseToJsonElement(WorkflowJson.encode(candidate)).jsonObject
        assertFalse(AssistantCandidateValidator.validate(snapshot, JsonObject(json + ("steps" to JsonArray(emptyList()))).toString(), { emptyList() }).isValid)
        assertFalse(AssistantCandidateValidator.validate(snapshot, JsonObject(json + ("allowedPackages" to JsonArray(emptyList()))).toString(), { emptyList() }).isValid)
        assertFalse(AssistantCandidateValidator.validate(snapshot, WorkflowJson.encode(candidate), { listOf("Compiler rejected") }).isValid)
    }
}

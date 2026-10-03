package com.veyarc.feature.assistant

import com.veyarc.model.AssetId
import com.veyarc.model.AssetKind
import com.veyarc.model.AssetReference
import com.veyarc.model.DisplayOrientation
import com.veyarc.model.DurationMillis
import com.veyarc.model.RunLimits
import com.veyarc.model.ViewportSpec
import com.veyarc.model.Wait
import com.veyarc.model.WorkflowJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class AssistantDisplayTextTest {
    @Test
    fun labelsKnownIdsWithoutCorruptingWordsAndKeepsExactCandidateValues() {
        val step = Wait(UUID.randomUUID().toString(), DurationMillis(100))
        val snapshot = AssistantContextSnapshot.capture(
            AssistantDraftContext(
                UUID.randomUUID().toString(), null, null, "Morning", "com.example.target",
                ViewportSpec(recordedWidthPx = 1080, recordedHeightPx = 2400, recordedDensityDpi = 440, recordedOrientation = DisplayOrientation.PORTRAIT),
                RunLimits(1000, 60000, 100, 12, 4096, 60000), emptyMap(), listOf(AssetReference(AssetId("image"), AssetKind.IMAGE_TEMPLATE, "Logo")), listOf(step),
            ),
        )
        val turn = PreviewTurn(
            UUID.randomUUID().toString(),
            "Explain the workflow",
            PreviewScenario.EXPLANATION,
            reply = "${step.id} (${step.id.take(8)}) uses the image. Image `image` is unchanged.",
            baseSnapshot = snapshot,
        )
        val display = readableReply(turn)
        assertTrue(display.contains("Step 1: Wait"))
        assertTrue(display.contains("uses the image"))
        assertTrue(display.contains("Image Logo"))
        assertFalse(display.contains(step.id))
        assertEquals(turn.reply, readableReply(turn.copy(prompt = "Show the technical IDs")))
        val base = AssistantCandidateValidator.baseDocument(snapshot)
        val candidate = base.copy(steps = listOf(step.copy(durationMs = DurationMillis(250))))
        val proposal = AssistantProposalCheck(candidateJson = WorkflowJson.encode(candidate))
        val diff = readableChanges(snapshot, proposal).single()
        assertFalse(diff.label.contains(step.id))
        assertTrue(diff.label.endsWith("Duration"))
        assertEquals("100 ms", diff.before)
        assertEquals("250 ms", diff.after)
        assertEquals(candidate, WorkflowJson.decode(proposal.candidateJson!!))
    }
}

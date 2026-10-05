package com.veyarc.feature.assistant

import com.sun.net.httpserver.HttpServer
import com.veyarc.data.storage.AssistantConnection
import com.veyarc.feature.assistant.AssistantClient.Companion.string
import com.veyarc.model.DisplayOrientation
import com.veyarc.model.DurationMillis
import com.veyarc.model.RunLimits
import com.veyarc.model.ViewportSpec
import com.veyarc.model.Wait
import com.veyarc.model.WorkflowJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.net.InetSocketAddress
import java.net.URL
import java.util.UUID

class OpenAIResponsesTest {
    private val profile = AssistantConnection.openAI()
    private val content = AssistantResponseCodec.encode(AssistantResponse(1, "r", "c", "draft:d", AssistantOutcome.EXPLANATION, "Hello 🙂\nSecond line"))
    private fun request(stream: Boolean = true, reply: (String) -> Unit = {}) = DeepSeekRequest.create("r", "c", "draft:d", "Explain", null, streaming = stream, connection = profile, modelId = profile.defaultModelId, onReply = reply)

    @Test fun nativeRequestIsStatelessAndPreservesOnlyValidatedHistoryProse() {
        val old = AssistantResponse(1, "old", "c", "draft:d", AssistantOutcome.PROPOSAL, "Previous answer", buildJsonObject { put("old-secret-candidate", true) })
        val selected = DeepSeekRequest.create("r", "c", "draft:d", "Explain", null, listOf(AssistantHistoryTurn("Earlier prompt", old)), streaming = true, connection = profile, modelId = profile.defaultModelId)
        val body = Json.parseToJsonElement(selected.body.toString(Charsets.UTF_8)).jsonObject
        assertEquals(JsonPrimitive(false), body["store"])
        assertEquals(JsonPrimitive(true), body["stream"])
        assertEquals(JsonPrimitive("gpt-5.4"), body["model"])
        assertEquals(Json.parseToJsonElement("{\"format\":{\"type\":\"json_object\"}}"), body["text"])
        assertTrue(body.string("instructions").contains("explicit user review"))
        assertFalse(body.toString().contains("old-secret-candidate"))
        assertFalse(body.containsKey("messages") || body.containsKey("tools") || body.containsKey("previous_response_id") || body.containsKey("thinking"))
        val input = body["input"] as JsonArray
        assertEquals(3, input.size)
        assertEquals("Previous answer", (input[1] as JsonObject).string("content"))
        val binding = Json.parseToJsonElement((input.last() as JsonObject).string("content")).jsonObject
        assertEquals(JsonPrimitive("r"), binding["requestId"])
        val test = DeepSeekRequest.connectionTest(connection = profile)
        assertTrue(Json.parseToJsonElement(test.body.toString(Charsets.UTF_8)).jsonObject.containsKey("input"))
        assertFalse(test.body.toString(Charsets.UTF_8).contains("workflow.schema"))
    }

    @Test fun streamedProseNeedsAConsistentValidatedCompletedResponse() {
        val updates = mutableListOf<String>()
        val selected = request { updates += it }
        assertArrayEquals(content, read(wire(), selected))
        assertEquals("Hello 🙂\nSecond line", selected.latestReply)
        assertTrue(updates.isNotEmpty())
        val valid = wire()
        listOf(
            valid.substringBefore("event: response.completed"),
            valid.replace("response.completed", "response.incomplete"),
            valid.replace("\"status\":\"completed\"", "\"status\":\"incomplete\""),
            valid.replace("\"item_id\":\"msg\"", "\"item_id\":\"other\""),
            valid.replace("\"sequence_number\":2", "\"sequence_number\":0"),
            valid.replace("\"type\":\"output_text\"", "\"type\":\"refusal\""),
            valid.replace("event: response.output_text.delta", "event: response.refusal.delta"),
            valid.replace("\"id\":\"resp\"", "\"id\":\"resp\",\"id\":\"duplicate\""),
        ).forEach { bad -> assertTrue(runCatching { read(bad, request()) }.isFailure) }
        val mismatch = wire(envelope(content.toString(Charsets.UTF_8).replace("Second line", "Different line")))
        assertTrue(runCatching { read(mismatch, request()) }.isFailure)
        val cancellation = DeepSeekCancellation().apply { cancel() }
        assertEquals(DeepSeekFailure.CANCELLED, (runCatching { read(valid, request(), cancellation) }.exceptionOrNull() as DeepSeekException).reason)
    }

    @Test fun finalDecodeRejectsRefusalsToolsBadBindingsAndTruncation() {
        assertArrayEquals(content, OpenAIResponses.decode(envelope(), request(false)))
        val valid = envelope().toString()
        listOf(
            valid.replace("\"status\":\"completed\"", "\"status\":\"incomplete\""),
            valid.replace("\"error\":null", "\"error\":{}"),
            valid.replace("\"incomplete_details\":null", "\"incomplete_details\":{}"),
            valid.replace("\"type\":\"output_text\"", "\"type\":\"refusal\""),
            valid.replace("\"type\":\"message\"", "\"type\":\"function_call\""),
            valid.replace("draft:d", "wrong-binding"),
        ).forEach { bad -> assertTrue(runCatching { OpenAIResponses.decode(Json.parseToJsonElement(bad).jsonObject, request(false)) }.isFailure) }
    }

    @Test fun workflowProposalUsesTheSharedReviewValidator() {
        val snapshot = AssistantContextSnapshot.capture(AssistantDraftContext(UUID.randomUUID().toString(), null, null, "Workflow", "com.example.app", ViewportSpec(recordedWidthPx = 1080, recordedHeightPx = 2400, recordedDensityDpi = 420, recordedOrientation = DisplayOrientation.PORTRAIT), RunLimits(100, 60000, 100, 8, 128, 30000), emptyMap(), emptyList(), listOf(Wait(UUID.randomUUID().toString(), DurationMillis(100)))))
        val selected = DeepSeekRequest.create("r", "c", snapshot.snapshotId, "Change the wait", snapshot, connection = profile, modelId = profile.defaultModelId)
        val base = AssistantCandidateValidator.baseDocument(snapshot)
        val changed = base.copy(steps = listOf((base.steps.single() as Wait).copy(durationMs = DurationMillis(200))))
        val response = AssistantResponse(1, "r", "c", snapshot.snapshotId, AssistantOutcome.PROPOSAL, "Changed the wait", WorkflowJson.format.parseToJsonElement(WorkflowJson.encode(changed)).jsonObject)
        val accepted = AssistantResponseCodec.decode(OpenAIResponses.decode(envelope(AssistantResponseCodec.encode(response).toString(Charsets.UTF_8)), selected), "r", "c", snapshot.snapshotId)
        assertTrue(AssistantCandidateValidator.validate(snapshot, requireNotNull(accepted.candidate).toString(), { emptyList() }).isValid)
        val locked = changed.copy(name = "Renamed", allowedPackages = listOf(com.veyarc.model.PackageName("com.other.app")))
        assertFalse(AssistantCandidateValidator.validate(snapshot, WorkflowJson.encode(locked), { emptyList() }).isValid)
    }

    @Test fun nativeTransportPostsOnceToResponsesAndMapsErrorsWithoutLeaking() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var status = 200
        var calls = 0
        server.createContext("/responses") { exchange ->
            calls++
            assertEquals("Bearer fixture-openai-key", exchange.requestHeaders.getFirst("Authorization"))
            assertEquals("POST", exchange.requestMethod)
            val sent = Json.parseToJsonElement(exchange.requestBody.bufferedReader().use { it.readText() }).jsonObject
            assertEquals(JsonPrimitive(false), sent["store"])
            val body = if (status == 200) envelope().toString() else "fixture-openai-key private server error"
            exchange.responseHeaders.set("Content-Type", "application/json")
            exchange.responseHeaders.set("Location", "/redirect")
            exchange.sendResponseHeaders(status, 0)
            exchange.responseBody.use { it.write(body.toByteArray()) }
        }
        server.start()
        try {
            val client = AssistantClient(URL("http://127.0.0.1:${server.address.port}/responses"))
            assertArrayEquals(content, client.complete(request(false), "fixture-openai-key"))
            listOf(401 to DeepSeekFailure.KEY_REQUIRED, 429 to DeepSeekFailure.RATE_LIMITED, 500 to DeepSeekFailure.UNAVAILABLE, 307 to DeepSeekFailure.INVALID_RESPONSE).forEach { (code, expected) ->
                status = code
                val error = runCatching { client.complete(request(false), "fixture-openai-key") }.exceptionOrNull() as DeepSeekException
                assertEquals(expected, error.reason)
                assertFalse(error.toString().contains("fixture-openai-key"))
            }
            assertEquals(5, calls)
        } finally {
            server.stop(0)
        }
    }

    private fun envelope(text: String = content.toString(Charsets.UTF_8)) = buildJsonObject {
        put("id", "resp")
        put("object", "response")
        put("model", "gpt-5.4-2026-03-05")
        put("status", "completed")
        put("error", JsonNull)
        put("incomplete_details", JsonNull)
        put(
            "output",
            JsonArray(
                listOf(
                    buildJsonObject {
                        put("id", "msg")
                        put("type", "message")
                        put("role", "assistant")
                        put("status", "completed")
                        put(
                            "content",
                            JsonArray(
                                listOf(
                                    buildJsonObject {
                                        put("type", "output_text")
                                        put("text", text)
                                    },
                                ),
                            ),
                        )
                    },
                ),
            ),
        )
    }
    private fun wire(final: JsonObject = envelope()): String {
        var sequence = 0
        fun event(type: String, fields: JsonObject): String = "event: $type\r\ndata: " + JsonObject(fields + ("type" to JsonPrimitive(type)) + ("sequence_number" to JsonPrimitive(sequence++))).toString() + "\r\n\r\n"
        val start = JsonObject(envelope() + ("status" to JsonPrimitive("in_progress")) + ("output" to JsonArray(emptyList())))
        val text = content.toString(Charsets.UTF_8)
        val part = buildJsonObject {
            put("item_id", "msg")
            put("output_index", 0)
            put("content_index", 0)
        }
        return "\uFEFF: keepalive\r\n\r\n" +
            event("response.created", buildJsonObject { put("response", start) }) +
            event(
                "response.output_item.added",
                buildJsonObject {
                    put("output_index", 0)
                    put(
                        "item",
                        buildJsonObject {
                            put("id", "msg")
                            put("type", "message")
                            put("role", "assistant")
                        },
                    )
                },
            ) +
            event("response.output_text.delta", JsonObject(part + ("delta" to JsonPrimitive(text.take(110))))) +
            event("response.output_text.delta", JsonObject(part + ("delta" to JsonPrimitive(text.drop(110))))) +
            event("response.output_text.done", JsonObject(part + ("text" to JsonPrimitive(text)))) +
            event("response.completed", buildJsonObject { put("response", final) })
    }
    private fun read(text: String, selected: AssistantRequest, cancellation: DeepSeekCancellation = DeepSeekCancellation()) = OpenAIResponses.read(ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)), selected, cancellation)
}

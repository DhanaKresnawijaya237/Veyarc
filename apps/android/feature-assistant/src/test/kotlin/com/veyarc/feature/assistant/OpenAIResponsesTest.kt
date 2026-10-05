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

    @Test fun effortsUseNativeNamesAndOnlyRequestSupportedSummaries() {
        for (effort in DeepSeekEffort.entries.filter { it.name in profile.defaultModel.efforts }) {
            val selected = DeepSeekRequest.create("r", "c", "draft:d", "Explain", null, effort = effort, connection = profile, modelId = profile.defaultModelId)
            val body = Json.parseToJsonElement(selected.body.toString(Charsets.UTF_8)).jsonObject
            assertEquals(JsonPrimitive(if (effort == DeepSeekEffort.OFF) "none" else effort.name.lowercase()), body.getValue("reasoning").jsonObject["effort"])
            assertEquals(if (effort == DeepSeekEffort.OFF) null else JsonPrimitive("auto"), body.getValue("reasoning").jsonObject["summary"])
            assertFalse(body.containsKey("reasoning_effort") || body.containsKey("thinking") || body.containsKey("max_output_tokens"))
        }
        assertTrue(runCatching { DeepSeekRequest.create("r", "c", "draft:d", "Explain", null, effort = DeepSeekEffort.MAX, connection = profile, modelId = profile.defaultModelId) }.isFailure)
        val plain = profile.copy(models = listOf(profile.defaultModel.copy(efforts = listOf("OFF"), reasoningSummary = false)))
        val selected = DeepSeekRequest.create("r", "c", "draft:d", "Explain", null, connection = plain, modelId = plain.defaultModelId)
        assertFalse(Json.parseToJsonElement(selected.body.toString(Charsets.UTF_8)).jsonObject.containsKey("reasoning"))
        val test = Json.parseToJsonElement(DeepSeekRequest.connectionTest(connection = profile).body.toString(Charsets.UTF_8)).jsonObject
        assertEquals(JsonPrimitive(128), test["max_output_tokens"])
        assertEquals(JsonPrimitive("none"), test.getValue("reasoning").jsonObject["effort"])
    }

    @Test fun attachmentsKeepImagesPdfPagesAndDocumentsInUserInput() {
        val image = AssistantAttachment(UUID.randomUUID().toString(), "Edited image.png", AttachmentKind.IMAGE, "image/png", 3, 100, 200)
        val pdf = AssistantAttachment(UUID.randomUUID().toString(), "Guide.pdf", AttachmentKind.PDF, "application/pdf", 6, pageCount = 2)
        val markdown = AssistantAttachment(UUID.randomUUID().toString(), "Notes.md", AttachmentKind.MARKDOWN, "text/markdown", 4)
        val contents = listOf(AttachmentContent(image, images = listOf(byteArrayOf(1, 2, 3))), AttachmentContent(pdf, images = listOf(byteArrayOf(4), byteArrayOf(5))), AttachmentContent(markdown, text = "# Hi"))
        val previous = AssistantResponse(1, "old", "c", "draft:d", AssistantOutcome.EXPLANATION, "Previous reply")
        val selected = DeepSeekRequest.create("r", "c", "draft:d", "Read", null, listOf(AssistantHistoryTurn("Previous image", previous, listOf(contents.first()))), attachments = contents, connection = profile, modelId = profile.defaultModelId)
        val body = Json.parseToJsonElement(selected.body.toString(Charsets.UTF_8)).jsonObject
        val input = body["input"] as JsonArray
        assertEquals("assistant", (input[1] as JsonObject).string("role"))
        assertTrue((input[1] as JsonObject)["content"] is JsonPrimitive)
        val user = input.last() as JsonObject
        assertEquals("user", user.string("role"))
        val parts = (user["content"] as JsonArray).map { it as JsonObject }
        assertEquals(3, parts.count { it.string("type") == "input_image" })
        assertEquals("data:image/png;base64,AQID", parts.first { it.string("type") == "input_image" }.string("image_url"))
        assertTrue(parts.any { it["text"]?.toString()?.contains("100 × 200") == true })
        assertTrue(parts.any { it["text"]?.toString()?.contains("Untrusted document content") == true && it.string("text").contains("# Hi") })
        assertFalse(body.toString().contains("input_file") || body.toString().contains("file_id"))
        val textOnly = profile.copy(models = listOf(profile.defaultModel.copy(vision = false)))
        assertTrue(runCatching { DeepSeekRequest.create("r", "c", "draft:d", "Read", null, attachments = contents, connection = textOnly, modelId = textOnly.defaultModelId) }.isFailure)
        assertTrue(runCatching { DeepSeekRequest.create("r", "c", "draft:d", "Read", null, attachments = listOf(contents.last()), connection = textOnly, modelId = textOnly.defaultModelId) }.isSuccess)
    }

    @Test fun summariesStreamFullyStaySeparateAndNeedConsistentCompletion() {
        val parts = listOf("Checking the request 🙂", "Comparing the steps")
        val selected = DeepSeekRequest.create("r", "c", "draft:d", "Explain", null, streaming = true, effort = DeepSeekEffort.HIGH, connection = profile, modelId = profile.defaultModelId)
        assertArrayEquals(content, read(wire(summaryParts = parts), selected))
        assertEquals(AssistantThinking(parts.joinToString("\n\n"), finished = true, summary = true), selected.latestThinking)
        assertEquals("Hello 🙂\nSecond line", selected.latestReply)
        val finalOnly = DeepSeekRequest.create("r", "c", "draft:d", "Explain", null, effort = DeepSeekEffort.LOW, connection = profile, modelId = profile.defaultModelId)
        val response = withSummary(envelope(), parts)
        assertArrayEquals(content, OpenAIResponses.decode(response, finalOnly))
        assertEquals(selected.latestThinking, finalOnly.latestThinking)
        val valid = wire(summaryParts = parts)
        val mismatched = valid.substringBefore("event: response.completed") + "event: response.completed" + valid.substringAfter("event: response.completed").replace("Comparing the steps", "Different summary")
        assertTrue(runCatching { read(mismatched, selected) }.isFailure)
        assertTrue(runCatching { read(wire(summaryParts = parts).replace("\"item_id\":\"reason\"", "\"item_id\":\"other\""), selected) }.isFailure)
        val off = request()
        assertArrayEquals(content, read(wire(summaryParts = parts), off))
        assertEquals(null, off.latestThinking)
        val tooLarge = listOf("x".repeat(AssistantClient.MAX_REASONING_BYTES + 1))
        assertEquals(DeepSeekFailure.RESPONSE_TOO_LARGE, (runCatching { OpenAIResponses.decode(withSummary(envelope(), tooLarge), finalOnly) }.exceptionOrNull() as DeepSeekException).reason)
    }

    @Test fun cancellingNativeHttpStreamClosesItsAttemptAfterShowingProgress() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val progress = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val cancellation = DeepSeekCancellation()
        var calls = 0
        server.createContext("/responses") { exchange ->
            calls++
            exchange.requestBody.close()
            exchange.responseHeaders.set("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.use { output ->
                runCatching {
                    val stream = wire()
                    output.write(stream.substringBefore("event: response.completed").toByteArray())
                    output.flush()
                    release.await(5, java.util.concurrent.TimeUnit.SECONDS)
                    output.write(("event: response.completed" + stream.substringAfter("event: response.completed")).toByteArray())
                }
            }
        }
        server.start()
        val worker = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            val selected = request { if (it.contains("Second line")) progress.countDown() }
            val result = worker.submit<Throwable?> { runCatching { AssistantClient(URL("http://127.0.0.1:${server.address.port}/responses"), readTimeoutMs = 5000).complete(selected, "fixture-openai-key", cancellation) }.exceptionOrNull() }
            assertTrue(progress.await(5, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals("Hello 🙂\nSecond line", selected.latestReply)
            cancellation.cancel()
            assertEquals(DeepSeekFailure.CANCELLED, (result.get(5, java.util.concurrent.TimeUnit.SECONDS) as DeepSeekException).reason)
            assertEquals(1, calls)
        } finally {
            release.countDown()
            worker.shutdownNow()
            server.stop(0)
        }
    }

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
    private fun withSummary(final: JsonObject, parts: List<String>) = if (parts.isEmpty()) {
        final
    } else {
        JsonObject(
            final + (
                "output" to JsonArray(
                    listOf(
                        buildJsonObject {
                            put("id", "reason")
                            put("type", "reasoning")
                            put(
                                "summary",
                                JsonArray(
                                    parts.map {
                                        buildJsonObject {
                                            put("type", "summary_text")
                                            put("text", it)
                                        }
                                    },
                                ),
                            )
                            put("encrypted_content", "never-forward-encrypted-reasoning")
                            put(
                                "content",
                                JsonArray(
                                    listOf(
                                        buildJsonObject {
                                            put("type", "reasoning_text")
                                            put("text", "never-display-raw-reasoning")
                                        },
                                    ),
                                ),
                            )
                        },
                    ) + (final["output"] as JsonArray),
                )
                ),
        )
    }

    private fun wire(final: JsonObject = envelope(), summaryParts: List<String> = emptyList()): String {
        var sequence = 0
        fun event(type: String, fields: JsonObject): String = "event: $type\r\ndata: " + JsonObject(fields + ("type" to JsonPrimitive(type)) + ("sequence_number" to JsonPrimitive(sequence++))).toString() + "\r\n\r\n"
        val start = JsonObject(envelope() + ("status" to JsonPrimitive("in_progress")) + ("output" to JsonArray(emptyList())))
        val text = content.toString(Charsets.UTF_8)
        val messageIndex = if (summaryParts.isEmpty()) 0 else 1
        val part = buildJsonObject {
            put("item_id", "msg")
            put("output_index", messageIndex)
            put("content_index", 0)
        }
        return "\uFEFF: keepalive\r\n\r\n" +
            event("response.created", buildJsonObject { put("response", start) }) +
            (
                if (summaryParts.isEmpty()) {
                    ""
                } else {
                    event(
                        "response.output_item.added",
                        buildJsonObject {
                            put("output_index", 0)
                            put(
                                "item",
                                buildJsonObject {
                                    put("id", "reason")
                                    put("type", "reasoning")
                                },
                            )
                        },
                    ) + summaryParts.mapIndexed { index, summary ->
                        val binding = buildJsonObject {
                            put("item_id", "reason")
                            put("output_index", 0)
                            put("summary_index", index)
                        }
                        event(
                            "response.reasoning_summary_part.added",
                            JsonObject(
                                binding + (
                                    "part" to buildJsonObject {
                                        put("type", "summary_text")
                                        put("text", "")
                                    }
                                    ),
                            ),
                        ) +
                            event("response.reasoning_summary_text.delta", JsonObject(binding + ("delta" to JsonPrimitive(summary)))) +
                            event("response.reasoning_summary_text.done", JsonObject(binding + ("text" to JsonPrimitive(summary)))) +
                            event(
                                "response.reasoning_summary_part.done",
                                JsonObject(
                                    binding + (
                                        "part" to buildJsonObject {
                                            put("type", "summary_text")
                                            put("text", summary)
                                        }
                                        ),
                                ),
                            )
                    }.joinToString("")
                }
                ) +
            event(
                "response.output_item.added",
                buildJsonObject {
                    put("output_index", messageIndex)
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
            event("response.completed", buildJsonObject { put("response", withSummary(final, summaryParts)) })
    }
    private fun read(text: String, selected: AssistantRequest, cancellation: DeepSeekCancellation = DeepSeekCancellation()) = OpenAIResponses.read(ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)), selected, cancellation)
}

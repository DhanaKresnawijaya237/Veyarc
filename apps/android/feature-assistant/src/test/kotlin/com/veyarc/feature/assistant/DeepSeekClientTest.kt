package com.veyarc.feature.assistant

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import com.veyarc.model.AssetId
import com.veyarc.model.AssetKind
import com.veyarc.model.AssetReference
import com.veyarc.model.DisplayOrientation
import com.veyarc.model.DurationMillis
import com.veyarc.model.RunLimits
import com.veyarc.model.ViewportSpec
import com.veyarc.model.Wait
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPOutputStream

class DeepSeekClientTest {
    private val request = DeepSeekRequest.create("request", "chat", "draft:draft", "Explain", null)
    private val response = AssistantResponse(1, "request", "chat", "draft:draft", AssistantOutcome.EXPLANATION, "Explanation")
    private val content = AssistantResponseCodec.encode(response)
    private val key = "fixture-secret-never-real"

    @Test
    fun sendsExactTextContractAndReceivesOriginalContent() = server { server, url ->
        var body = ""
        var authorization: String? = null
        var method = ""
        var accept: String? = null
        var contentType: String? = null
        val calls = AtomicInteger()
        server.createContext("/chat/completions") { exchange ->
            calls.incrementAndGet()
            method = exchange.requestMethod
            authorization = exchange.requestHeaders.getFirst("Authorization")
            accept = exchange.requestHeaders.getFirst("Accept")
            contentType = exchange.requestHeaders.getFirst("Content-Type")
            body = exchange.requestBody.bufferedReader().use { it.readText() }
            exchange.respond(200, envelope())
        }
        assertArrayEquals(content, DeepSeekClient(url).complete(request, key))
        assertEquals(1, calls.get())
        assertEquals("POST", method)
        assertEquals("Bearer $key", authorization)
        assertEquals("application/json", accept)
        assertEquals("application/json", contentType)
        assertFalse(body.contains(key))
        val json = Json.parseToJsonElement(body).jsonObject
        assertEquals(setOf("model", "messages", "max_tokens", "thinking", "response_format", "stream"), json.keys)
        assertEquals(JsonPrimitive("deepseek-flash"), json["model"])
        assertEquals(JsonPrimitive(16384), json["max_tokens"])
        assertEquals(JsonPrimitive(false), json["stream"])
        assertEquals(Json.parseToJsonElement("{\"type\":\"disabled\"}"), json["thinking"])
        assertEquals(Json.parseToJsonElement("{\"type\":\"json_object\"}"), json["response_format"])
        val messages = json["messages"] as JsonArray
        assertEquals(2, messages.size)
        messages.forEach { assertEquals(setOf("role", "content"), it.jsonObject.keys) }
        val instructions = messages[0].jsonObject.getValue("content").jsonPrimitive.content
        assertTrue(instructions.contains("Veyarc workflow document"))
        assertTrue(instructions.contains("Copy snapshot.assets exactly"))
        val schema = Json.parseToJsonElement(instructions.substringAfter("Workflow schema (spec/workflows/v1/workflow.schema.json):")).jsonObject
        val imageSchema = schema.getValue("$" + "defs").jsonObject.getValue("assetReference").jsonObject
        val nameSchema = imageSchema.getValue("properties").jsonObject.getValue("displayName").jsonObject
        assertEquals(Json.parseToJsonElement("[\"string\",\"null\"]"), nameSchema.getValue("type"))
        assertEquals(JsonPrimitive(64), nameSchema.getValue("maxLength"))
        val current = Json.parseToJsonElement(messages.last().jsonObject.getValue("content").jsonPrimitive.content).jsonObject
        assertEquals(JsonNull, current["snapshot"])
        assertEquals(JsonPrimitive(false), current["proposalAllowed"])
    }

    @Test
    fun bindsCapturedBaseAndSendsOnlyHistoryProseWithoutDroppingOversizedHistory() {
        val snapshot = AssistantContextSnapshot.capture(
            AssistantDraftContext(
                "00000000-0000-0000-0000-000000000001", 2, 5, "Workflow", "com.example.app",
                ViewportSpec(recordedWidthPx = 1080, recordedHeightPx = 2400, recordedDensityDpi = 420, recordedOrientation = DisplayOrientation.PORTRAIT),
                RunLimits(1000, 60000, 100, 12, 4096, 10000), emptyMap(), listOf(AssetReference(AssetId("existing_image"), AssetKind.IMAGE_TEMPLATE, "Sign in button")),
                listOf(Wait("00000000-0000-0000-0000-000000000002", DurationMillis(100))),
            ),
        )
        val old = response.copy(requestId = "previous", outcome = AssistantOutcome.PROPOSAL, candidate = buildJsonObject { put("secret-old-candidate", true) })
        val next = DeepSeekRequest.create("request", "chat", snapshot.snapshotId, "Explain", snapshot, listOf(DeepSeekHistoryTurn("Old prompt", old)))
        val body = next.body.toString(Charsets.UTF_8)
        assertFalse(body.contains("secret-old-candidate"))
        val messages = Json.parseToJsonElement(body).jsonObject["messages"] as JsonArray
        assertEquals(4, messages.size)
        assertEquals("Explanation", messages[2].jsonObject.getValue("content").jsonPrimitive.content)
        val current = Json.parseToJsonElement(messages.last().jsonObject.getValue("content").jsonPrimitive.content).jsonObject
        assertEquals(JsonPrimitive(6), current["expectedRevision"])
        assertEquals(JsonPrimitive(true), current["proposalAllowed"])
        assertEquals(Json.parseToJsonElement(snapshot.json), current["snapshot"])
        val image = (current.getValue("snapshot").jsonObject.getValue("assets") as JsonArray).single().jsonObject
        assertEquals(JsonPrimitive("Sign in button"), image.getValue("displayName"))
        failure(DeepSeekFailure.INVALID_REQUEST) { DeepSeekRequest.create("r", "chat", "wrong", "Explain", snapshot) }
        failure(DeepSeekFailure.INVALID_REQUEST) { DeepSeekRequest.create("r", "chat", "draft:d", "", null) }
        failure(DeepSeekFailure.INVALID_REQUEST) { DeepSeekRequest.create("r", "other-chat", "draft:d", "Explain", null, listOf(DeepSeekHistoryTurn("Old", old))) }
        val large = List(10) { DeepSeekHistoryTurn("p".repeat(4000), response.copy(requestId = "old-$it", text = "界".repeat(16000))) }
        failure(DeepSeekFailure.REQUEST_TOO_LARGE) { DeepSeekRequest.create("r", "chat", "draft:d", "Explain", null, large) }
        failure(DeepSeekFailure.INVALID_REQUEST) { DeepSeekRequest.create("r", "chat", "draft:d", "Explain", null, List(40) { DeepSeekHistoryTurn("Old", old) }) }
    }

    @Test
    fun rejectsMalformedTruncatedUnboundAndUnexpectedResponses() {
        val valid = envelope().toString(Charsets.UTF_8)
        listOf(
            valid.dropLast(1), valid + "garbage", "[]", "",
            valid.replace("chat.completion", "chat.completion.chunk"),
            valid.replace("deepseek-flash", "unrecognized-model"),
            valid.replace("\"index\":0", "\"index\":\"0\""),
            valid.replace("\"index\":0", "\"index\":1"),
            valid.replace("\"index\":0", "\"index\":0,\"index\":0"),
            valid.replace("\"role\":\"assistant\"", "\"role\":\"tool\""),
            valid.replace("\"role\":\"assistant\"", "\"role\":\"assistant\",\"tool_calls\":[]"),
            valid.replace("\"role\":\"assistant\"", "\"role\":\"assistant\",\"reasoning_content\":\"hidden\""),
            valid.replace("\"role\":\"assistant\"", "\"role\":\"assistant\",\"refusal\":\"refused\""),
            valid.replace("\"object\":", "\"error\":{},\"object\":"),
            "{\"x\":" + "[".repeat(100) + "0" + "]".repeat(100) + "}",
        ).forEach { text -> failure(DeepSeekFailure.INVALID_RESPONSE) { DeepSeekClient.decode(text.toByteArray(), request) } }
        listOf("length", "tool_calls", "content_filter", "aborted", "insufficient_system_resource", "unknown").forEach {
            failure(DeepSeekFailure.INVALID_RESPONSE) { DeepSeekClient.decode(valid.replace("\"stop\"", "\"$it\"").toByteArray(), request) }
        }
        listOf("", "{}", "```json\n${content.toString(Charsets.UTF_8)}\n```", content.toString(Charsets.UTF_8).replace("\"request\"", "\"wrong\""), content.toString(Charsets.UTF_8).replace("\"contractVersion\":1", "\"contractVersion\":1,\"contractVersion\":1")).forEach {
            failure(DeepSeekFailure.INVALID_RESPONSE) { DeepSeekClient.decode(envelope(it), request) }
        }
        failure(DeepSeekFailure.INVALID_RESPONSE) { DeepSeekClient.decode(byteArrayOf(0xc3.toByte(), 0x28), request) }
        failure(DeepSeekFailure.RESPONSE_TOO_LARGE) { DeepSeekClient.decode(envelope("x".repeat(65537)), request) }
        failure(DeepSeekFailure.RESPONSE_TOO_LARGE) { DeepSeekClient.decode(ByteArray(DeepSeekClient.MAX_RESPONSE_BYTES + 1), request) }
    }

    @Test
    fun mapsHttpErrorsWithoutLeakingBodiesOrRetryingOrFollowingRedirects() = server { server, url ->
        val calls = AtomicInteger()
        var status = 401
        server.createContext("/chat/completions") {
            calls.incrementAndGet()
            it.requestBody.close()
            it.responseHeaders.set("Location", "$url/redirect-target")
            it.responseHeaders.set("Retry-After", "0")
            it.respond(status, "sensitive provider details $key".toByteArray())
        }
        val client = DeepSeekClient(url)
        mapOf(
            401 to DeepSeekFailure.KEY_REQUIRED, 402 to DeepSeekFailure.INSUFFICIENT_BALANCE, 403 to DeepSeekFailure.ACCESS_DENIED,
            400 to DeepSeekFailure.INVALID_REQUEST, 413 to DeepSeekFailure.INVALID_REQUEST, 422 to DeepSeekFailure.INVALID_REQUEST,
            429 to DeepSeekFailure.RATE_LIMITED, 500 to DeepSeekFailure.UNAVAILABLE, 503 to DeepSeekFailure.UNAVAILABLE,
            302 to DeepSeekFailure.INVALID_RESPONSE, 307 to DeepSeekFailure.INVALID_RESPONSE,
        ).forEach { (code, reason) ->
            status = code
            val before = calls.get()
            failure(reason) { client.complete(request, key) }
            assertEquals(before + 1, calls.get())
        }
        val before = calls.get()
        failure(DeepSeekFailure.KEY_REQUIRED) { client.complete(request, "bad\nheader") }
        assertEquals(before, calls.get())
    }

    @Test
    fun boundsDecompressedBodiesAndRejectsWrongContentType() = server { server, url ->
        var payload = envelope()
        var type = "application/json; charset=utf-8"
        server.createContext("/chat/completions") { exchange ->
            exchange.requestBody.close()
            exchange.responseHeaders.set("Content-Encoding", "gzip")
            val compressed = ByteArrayOutputStream().also { output -> GZIPOutputStream(output).use { it.write(payload) } }.toByteArray()
            exchange.respond(200, compressed, type)
        }
        val client = DeepSeekClient(url)
        assertArrayEquals(content, client.complete(request, key))
        payload = ByteArray(DeepSeekClient.MAX_RESPONSE_BYTES + 1) { 32 }
        failure(DeepSeekFailure.RESPONSE_TOO_LARGE) { client.complete(request, key) }
        payload = envelope()
        type = "text/event-stream"
        failure(DeepSeekFailure.INVALID_RESPONSE) { client.complete(request, key) }
    }

    @Test
    fun disconnectedRequestsAreNotRetriedAndIncompleteBodiesAreRejected() = server { server, url ->
        val calls = AtomicInteger()
        var sendPartial = false
        server.createContext("/chat/completions") { exchange ->
            calls.incrementAndGet()
            exchange.requestBody.close()
            if (sendPartial) {
                exchange.responseHeaders.set("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, 0)
                exchange.responseBody.write(envelope().dropLast(5).toByteArray())
            }
            exchange.close()
        }
        val client = DeepSeekClient(url)
        failure(DeepSeekFailure.NETWORK) { client.complete(request, key) }
        assertEquals(1, calls.get())
        sendPartial = true
        failure(DeepSeekFailure.INVALID_RESPONSE) { client.complete(request, key) }
        assertEquals(2, calls.get())
    }

    @Test
    fun idleAndOverallTimeoutsReleaseTheRequestAndRejectConcurrentCalls() = server { server, url ->
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        server.createContext("/chat/completions") { exchange ->
            exchange.requestBody.close()
            entered.countDown()
            release.await(3, TimeUnit.SECONDS)
            runCatching { exchange.respond(200, envelope()) }
        }
        val worker = Executors.newSingleThreadExecutor()
        try {
            val client = DeepSeekClient(url, readTimeoutMs = 200)
            val first = worker.submit<DeepSeekFailure> {
                try {
                    client.complete(request, key)
                    error("Expected timeout")
                } catch (error: DeepSeekException) {
                    error.reason
                }
            }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            failure(DeepSeekFailure.BUSY) { client.complete(request, key) }
            assertEquals(DeepSeekFailure.TIMEOUT, first.get(3, TimeUnit.SECONDS))
            // A separate overall deadline must win even when the idle timeout is longer.
            failure(DeepSeekFailure.TIMEOUT) { DeepSeekClient(url, readTimeoutMs = 2000, deadlineMs = 100).complete(request, key) }
            release.countDown()
            assertArrayEquals(content, client.complete(request, key))
        } finally {
            release.countDown()
            worker.shutdownNow()
        }
    }

    private fun envelope(text: String = content.toString(Charsets.UTF_8)): ByteArray = buildJsonObject {
        put("object", "chat.completion")
        put("model", "deepseek-flash")
        put(
            "choices",
            JsonArray(
                listOf(
                    buildJsonObject {
                        put("index", 0)
                        put("finish_reason", "stop")
                        put(
                            "message",
                            buildJsonObject {
                                put("role", "assistant")
                                put("content", text)
                            },
                        )
                    },
                ),
            ),
        )
    }.toString().toByteArray()

    private fun failure(reason: DeepSeekFailure, action: () -> Unit) {
        val error = runCatching(action).exceptionOrNull()
        assertTrue("Expected $reason, received ${error?.javaClass?.simpleName}", error is DeepSeekException)
        error as DeepSeekException
        assertEquals(reason, error.reason)
        assertNull(error.cause)
        assertFalse(error.toString().contains(key))
        assertFalse(error.toString().contains("sensitive"))
    }

    private fun HttpExchange.respond(status: Int, payload: ByteArray, type: String = "application/json") {
        responseHeaders.set("Content-Type", type)
        sendResponseHeaders(status, payload.size.toLong())
        responseBody.use { it.write(payload) }
        close()
    }

    private fun server(test: (HttpServer, URL) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val executor = Executors.newCachedThreadPool()
        server.executor = executor
        server.start()
        try {
            test(server, URL("http://127.0.0.1:${server.address.port}/chat/completions"))
        } finally {
            server.stop(0)
            executor.shutdownNow()
        }
    }
}

package com.veyarc.feature.assistant

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.net.InetSocketAddress
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class DeepSeekStreamTest {
    private val request = DeepSeekRequest.create("r", "c", "draft:d", "Explain", null, streaming = true)
    private val key = "fixture-only-key"
    private val content = AssistantResponseCodec.encode(AssistantResponse(1, "r", "c", "draft:d", AssistantOutcome.EXPLANATION, "Hello 界 🌍"))
    private fun chunk(text: String? = null, stop: String? = null, role: String? = null, reasoning: String? = null) = buildJsonObject {
        put("id", "completion-1")
        put("object", "chat.completion.chunk")
        put("model", "deepseek-flash")
        put(
            "choices",
            JsonArray(
                listOf(
                    buildJsonObject {
                        put("index", 0)
                        put("finish_reason", stop?.let(::JsonPrimitive) ?: JsonNull)
                        put(
                            "delta",
                            buildJsonObject {
                                role?.let { put("role", it) }
                                text?.let { put("content", it) }
                                reasoning?.let { put("reasoning_content", it) }
                            },
                        )
                    },
                ),
            ),
        )
    }.toString()
    private fun event(value: String) = "data: $value\n\n"
    private fun valid() = event(chunk(content.toString(Charsets.UTF_8), role = "assistant")) + event(chunk(stop = "stop")) + event("[DONE]")

    @Test
    fun thinkingChunksStaySeparateAndBoundedAndCannotCompleteWithoutFinalJson() {
        val thinkingRequest = DeepSeekRequest.create("r", "c", "draft:d", "Explain", null, streaming = true, effort = DeepSeekEffort.HIGH)
        val thought = event(chunk(role = "assistant", reasoning = "Untrusted thinking 界 🌍 {not workflow JSON}"))
        val final = event(chunk(content.toString(Charsets.UTF_8))) + event(chunk(stop = "stop")) + event("[DONE]")
        assertArrayEquals(content, DeepSeekStream.read((thought + final).byteInputStream(), thinkingRequest, DeepSeekCancellation()))
        listOf(thought, thought + event(chunk(stop = "stop")) + event("[DONE]"), thought + final.replace("\"stop\"", "\"length\""), thought.replace("\"Untrusted thinking 界 🌍 {not workflow JSON}\"", "false") + final).forEach {
            assertTrue(runCatching { DeepSeekStream.read(it.byteInputStream(), thinkingRequest, DeepSeekCancellation()) }.isFailure)
        }
        val oversized = event(chunk(role = "assistant", reasoning = "x".repeat(400000))).repeat(6) + final
        failure(DeepSeekFailure.RESPONSE_TOO_LARGE) { DeepSeekStream.read(oversized.byteInputStream(), thinkingRequest, DeepSeekCancellation()) }
    }

    @Test
    fun parsesFragmentedUtf8CrLfMultilineDataCommentsAndTerminalUsage() {
        listOf("\n", "\r\n", "\r").forEach { newline ->
            val first = chunk(content.toString(Charsets.UTF_8), role = "assistant").replace(",\"choices\"", ",\ndata: \"choices\"")
            val final = chunk(stop = "stop").replace("\"delta\":{}", "\"delta\":{\"role\":null,\"content\":\"\"}").dropLast(1) + ",\"usage\":{\"total_tokens\":100}}"
            val bytes = ("\uFEFF: keepalive\n\nid: ignored\nretry: 1\nevent: message\n" + event(first) + event(final) + event("[DONE]")).replace("\n", newline).toByteArray()
            val fragmented = object : ByteArrayInputStream(bytes) {
                override fun read(buffer: ByteArray, offset: Int, length: Int) = super.read(buffer, offset, minOf(length, 1))
            }
            assertArrayEquals(content, DeepSeekStream.read(fragmented, request, DeepSeekCancellation()))
        }
        val text = content.toString(Charsets.UTF_8)
        val deltas = event(chunk(text.take(50), role = "assistant")) + event(chunk(text.drop(50))) + event(chunk(stop = "stop")) + event("[DONE]")
        assertArrayEquals(content, DeepSeekStream.read(deltas.byteInputStream(), request, DeepSeekCancellation()))
    }

    @Test
    fun requiresOneAssistantChoiceStableMetadataStopAndDoneWithoutExposingPartialJson() {
        val start = event(chunk(content.toString(Charsets.UTF_8), role = "assistant"))
        val stop = event(chunk(stop = "stop"))
        val done = event("[DONE]")
        val invalid = listOf(
            "", start, start + stop, start + done, done, start + stop + "data: [DONE]", // No event terminator.
            start + stop + event(chunk("extra")) + done,
            start + event("{\"error\":{\"message\":\"sensitive\"}}") + done,
            start + "event: error\ndata: {}\n\n",
            start + event(chunk(stop = "stop").replace("completion-1", "different")) + done,
            valid().replace("deepseek-flash", "other-model"),
            valid().replace("chat.completion.chunk", "chat.completion"),
            valid().replace("\"index\":0", "\"index\":1"),
            valid().replace("\"index\":0", "\"index\":0,\"index\":0"),
            valid().replace("\"assistant\"", "\"tool\""),
            valid().replace("\"role\":\"assistant\",", ""),
            valid().replace("\"delta\":{", "\"delta\":{\"tool_calls\":[],"),
            valid().replace("\"delta\":{", "\"delta\":{\"reasoning_content\":\"secret\","),
            valid().replace("\"delta\":{", "\"delta\":{\"refusal\":\"no\","),
            event(chunk("{}", role = "assistant")) + stop + done,
            event(chunk(content.toString(Charsets.UTF_8).replace("\"r\"", "\"wrong\""), role = "assistant")) + stop + done,
            event(chunk(content.toString(Charsets.UTF_8).replace("\"contractVersion\":1", "\"contractVersion\":1,\"contractVersion\":1"), role = "assistant")) + stop + done,
        ) + listOf("length", "tool_calls", "content_filter", "aborted", "insufficient_system_resource", "unknown").map { start + event(chunk(stop = it)) + done }
        invalid.forEachIndexed { index, value ->
            assertTrue("Fixture $index must fail", runCatching { DeepSeekStream.read(value.byteInputStream(), request, DeepSeekCancellation()) }.isFailure)
        }
        assertTrue(runCatching { DeepSeekStream.read(ByteArrayInputStream(byteArrayOf(0xc3.toByte(), 10, 10)), request, DeepSeekCancellation()) }.isFailure)
    }

    @Test
    fun boundsEventsAndAccumulatedContent() {
        listOf(
            ":" + "x".repeat(DeepSeekStream.MAX_EVENT_BYTES),
            event(chunk("x".repeat(65537), role = "assistant")),
            event(chunk("x".repeat(40000), role = "assistant")) + event(chunk("x".repeat(30000))),
        ).forEach {
            failure(DeepSeekFailure.RESPONSE_TOO_LARGE) { DeepSeekStream.read(it.byteInputStream(), request, DeepSeekCancellation()) }
        }
        val token = DeepSeekCancellation().apply { cancel() }
        failure(DeepSeekFailure.CANCELLED) { DeepSeekStream.read(valid().byteInputStream(), request, token) }
    }

    @Test
    fun usesStreamingRequestAndClosesAtDoneWithoutWaitingForEof() = server { server, url ->
        val release = CountDownLatch(1)
        var sent = ""
        var accept: String? = null
        server.createContext("/chat/completions") {
            sent = it.requestBody.bufferedReader().readText()
            accept = it.requestHeaders.getFirst("Accept")
            it.responseHeaders.set("Content-Type", "text/event-stream; charset=utf-8")
            it.sendResponseHeaders(200, 0)
            it.responseBody.write(valid().toByteArray())
            it.responseBody.flush()
            release.await(3, TimeUnit.SECONDS)
            it.close()
        }
        try {
            assertArrayEquals(content, DeepSeekClient(url, readTimeoutMs = 500).complete(request, key))
            assertEquals("text/event-stream", accept)
            val body = Json.parseToJsonElement(sent).jsonObject
            assertEquals(JsonPrimitive(true), body["stream"])
            assertEquals(setOf("model", "messages", "max_tokens", "thinking", "response_format", "stream"), body.keys)
        } finally {
            release.countDown()
        }
    }

    @Test
    fun cancellationDuringHeadersBodyOrPendingDoneClosesTransportAndAllowsOnlyExplicitRetry() = server { server, url ->
        val calls = AtomicInteger()
        var prefix = ""
        var headers = false
        var entered = CountDownLatch(1)
        var release = CountDownLatch(1)
        var closed = CountDownLatch(1)
        server.createContext("/chat/completions") {
            calls.incrementAndGet()
            it.requestBody.close()
            if (headers) {
                it.responseHeaders.set("Content-Type", "text/event-stream")
                it.sendResponseHeaders(200, 0)
                it.responseBody.write(prefix.toByteArray())
                it.responseBody.flush()
            }
            entered.countDown()
            release.await(5, TimeUnit.SECONDS)
            try {
                if (!headers) it.sendResponseHeaders(200, 0)
                repeat(64) { _ ->
                    it.responseBody.write(ByteArray(65536))
                    it.responseBody.flush()
                }
            } catch (_: java.io.IOException) {
                closed.countDown()
            } finally {
                it.close()
            }
        }
        val worker = Executors.newSingleThreadExecutor()
        val client = DeepSeekClient(url, readTimeoutMs = 4000)
        try {
            val preCancelled = DeepSeekCancellation().apply { cancel() }
            failure(DeepSeekFailure.CANCELLED) { client.complete(request, key, preCancelled) }
            assertEquals(0, calls.get())
            listOf("", event(chunk("{", role = "assistant")), event(chunk(content.toString(Charsets.UTF_8), role = "assistant")) + event(chunk(stop = "stop")), event(chunk(role = "assistant", reasoning = "Still thinking"))).forEachIndexed { index, partial ->
                headers = index != 0
                prefix = partial
                entered = CountDownLatch(1)
                release = CountDownLatch(1)
                closed = CountDownLatch(1)
                val token = DeepSeekCancellation()
                val pending = worker.submit<DeepSeekFailure> {
                    try {
                        val selected = if (index == 3) DeepSeekRequest.create("r", "c", "draft:d", "Explain", null, streaming = true, effort = DeepSeekEffort.MAX) else request
                        client.complete(selected, key, token)
                        error("Accepted cancelled result")
                    } catch (error: DeepSeekException) {
                        error.reason
                    }
                }
                assertTrue(entered.await(2, TimeUnit.SECONDS))
                Thread.sleep(100) // Let the worker enter a blocking read, rather than cancel before I/O.
                val started = System.nanoTime()
                token.cancel()
                assertEquals(DeepSeekFailure.CANCELLED, pending.get(1, TimeUnit.SECONDS))
                assertTrue("Cancellation must not wait for the read timeout", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 1000)
                token.cancel()
                release.countDown()
                assertTrue("Peer must observe transport closure", closed.await(2, TimeUnit.SECONDS))
                assertEquals(index + 1, calls.get())
            }
        } finally {
            release.countDown()
            worker.shutdownNow()
        }
    }

    @Test
    fun cancelledCoroutineOwnerCannotPublishAndLateCancellationCannotAffectTheNextAttempt() = server { server, url ->
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        server.createContext("/chat/completions") {
            val first = calls.incrementAndGet() == 1
            it.requestBody.close()
            it.responseHeaders.set("Content-Type", "text/event-stream")
            it.sendResponseHeaders(200, 0)
            if (first) {
                it.responseBody.write(event(chunk("{", role = "assistant")).toByteArray())
                it.responseBody.flush()
                entered.countDown()
                release.await(3, TimeUnit.SECONDS)
            }
            runCatching { it.responseBody.write(valid().toByteArray()) }
            it.close()
        }
        val client = DeepSeekClient(url, readTimeoutMs = 2000)
        val firstToken = DeepSeekCancellation()
        try {
            runBlocking {
                var published = false
                val owner = async { client.await(request, key, firstToken).also { published = true } }
                withContext(Dispatchers.IO) { assertTrue(entered.await(2, TimeUnit.SECONDS)) }
                delay(100)
                owner.cancelAndJoin()
                assertFalse(published)
                release.countDown()
                // Cancellation completes the owner immediately; allow its worker's finally to run.
                var next: ByteArray? = null
                repeat(100) {
                    if (next == null) {
                        try {
                            next = client.await(request, key)
                        } catch (error: DeepSeekException) {
                            assertEquals(DeepSeekFailure.BUSY, error.reason)
                            delay(10)
                        }
                    }
                }
                firstToken.cancel()
                assertArrayEquals(content, next)
                assertEquals(2, calls.get())
            }
        } finally {
            release.countDown()
        }
    }

    @Test
    fun keepalivesCannotExtendDeadlineAndErrorsRemainSanitized() = server { server, url ->
        var payload = event("{\"error\":{\"message\":\"sensitive $key\"}}")
        server.createContext("/chat/completions") {
            it.requestBody.close()
            it.responseHeaders.set("Content-Type", "text/event-stream")
            it.sendResponseHeaders(200, 0)
            runCatching {
                if (payload.isNotEmpty()) {
                    it.responseBody.write(payload.toByteArray())
                } else {
                    repeat(100) { _ ->
                        it.responseBody.write(": ping\n\n".toByteArray())
                        it.responseBody.flush()
                        Thread.sleep(20)
                    }
                }
            }
            it.close()
        }
        failure(DeepSeekFailure.INVALID_RESPONSE) { DeepSeekClient(url).complete(request, key) }
        payload = ""
        failure(DeepSeekFailure.TIMEOUT) { DeepSeekClient(url, readTimeoutMs = 1000, deadlineMs = 150).complete(request, key) }
        payload = (":" + "x".repeat(65534) + "\n\n").repeat(33)
        failure(DeepSeekFailure.RESPONSE_TOO_LARGE) { DeepSeekClient(url).complete(request, key) }
    }

    private fun failure(reason: DeepSeekFailure, action: () -> Unit) {
        val error = runCatching(action).exceptionOrNull()
        assertTrue("Expected $reason, got ${error?.javaClass?.simpleName}", error is DeepSeekException)
        error as DeepSeekException
        assertEquals(reason, error.reason)
        assertNull(error.cause)
        assertFalse(error.toString().contains(key))
        assertFalse(error.toString().contains("sensitive"))
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

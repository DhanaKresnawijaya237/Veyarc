package com.veyarc.feature.assistant

import com.sun.net.httpserver.HttpServer
import com.veyarc.data.storage.AssistantCompatibility
import com.veyarc.data.storage.AssistantConnection
import com.veyarc.data.storage.AssistantModelConfig
import com.veyarc.data.storage.AssistantProtocol
import com.veyarc.data.storage.AssistantProviderCatalog
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.net.InetSocketAddress
import java.net.URL
import java.util.UUID

class ProviderProtocolsTest {
    private val response = AssistantResponseCodec.encode(AssistantResponse(1, "r", "c", "draft:d", AssistantOutcome.EXPLANATION, "Hello 🙂"))
    private val escaped = JsonPrimitive(response.toString(Charsets.UTF_8)).toString()
    private fun profile(protocol: AssistantProtocol, adaptive: Boolean = false) = AssistantConnection(
        UUID.randomUUID().toString(),
        "Provider",
        "https://example.org/v1",
        protocol,
        listOf(AssistantModelConfig("model", vision = true, efforts = listOf("OFF", "LOW", "HIGH"), maxOutputTokens = 32768, compatibility = AssistantCompatibility(adaptiveThinking = adaptive))),
        "model",
    )
    private fun request(profile: AssistantConnection, stream: Boolean = true, effort: DeepSeekEffort = DeepSeekEffort.HIGH) = DeepSeekRequest.create("r", "c", "draft:d", "Explain", null, connection = profile, modelId = "model", effort = effort, streaming = stream)
    private fun event(type: String, body: String) = "event: $type\ndata: {\"type\":\"$type\"$body}\n\n"
    private fun anthropicWire(): String = event("message_start", ",\"message\":{\"id\":\"msg-a\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"model\",\"content\":[],\"stop_reason\":null}") +
        event("content_block_start", ",\"index\":0,\"content_block\":{\"type\":\"thinking\",\"thinking\":\"\"}") +
        event("content_block_delta", ",\"index\":0,\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\"Checking\"}") +
        event("content_block_delta", ",\"index\":0,\"delta\":{\"type\":\"signature_delta\",\"signature\":\"hidden-signature\"}") +
        event("content_block_stop", ",\"index\":0") +
        event("content_block_start", ",\"index\":1,\"content_block\":{\"type\":\"text\",\"text\":\"\"}") +
        event("content_block_delta", ",\"index\":1,\"delta\":{\"type\":\"text_delta\",\"text\":$escaped}") +
        event("content_block_stop", ",\"index\":1") + event("message_delta", ",\"delta\":{\"stop_reason\":\"end_turn\"}") + event("message_stop", "")

    @Test fun nativeAnthropicStreamsBoundedThinkingAndValidatedRepliesAndRejectsPartialTerminals() {
        val profile = profile(AssistantProtocol.ANTHROPIC_MESSAGES, true)
        val selected = request(profile)
        val body = Json.parseToJsonElement(selected.body.toString(Charsets.UTF_8)).jsonObject
        assertEquals(JsonPrimitive("adaptive"), body.getValue("thinking").jsonObject["type"])
        assertEquals(JsonPrimitive("high"), body.getValue("output_config").jsonObject["effort"])
        assertFalse(body.containsKey("reasoning_effort"))
        val wire = anthropicWire()
        assertArrayEquals(response, AnthropicMessages.read(ByteArrayInputStream(wire.toByteArray()), selected, DeepSeekCancellation()))
        assertEquals("Hello 🙂", selected.latestReply)
        assertEquals("Checking", selected.latestThinking!!.text)
        assertTrue(selected.latestThinking!!.finished)
        for (invalid in listOf(wire.substringBefore("event: message_stop"), wire.replace("end_turn", "max_tokens"), wire.replace("\"index\":1", "\"index\":3"), wire.replace("text_delta", "input_json_delta"))) {
            assertTrue(runCatching { AnthropicMessages.read(ByteArrayInputStream(invalid.toByteArray()), request(profile), DeepSeekCancellation()) }.isFailure)
        }
        val budget = Json.parseToJsonElement(request(profile(AssistantProtocol.ANTHROPIC_MESSAGES), effort = DeepSeekEffort.LOW).body.toString(Charsets.UTF_8)).jsonObject
        assertEquals(JsonPrimitive(2048), budget.getValue("thinking").jsonObject["budget_tokens"])
    }

    @Test fun nativeGoogleStreamsThoughtPartsAndRequiresStopAndOneResponseIdentity() {
        val profile = profile(AssistantProtocol.GOOGLE_GENERATIVE_AI)
        val selected = request(profile)
        fun part(text: String, thought: Boolean, terminal: String = "") = "data: {\"responseId\":\"g-a\",\"modelVersion\":\"model\",\"candidates\":[{\"index\":0,\"content\":{\"role\":\"model\",\"parts\":[{\"text\":$text,\"thought\":$thought}]}$terminal}]}\n\n"
        val wire = part("\"Checking\"", true) + part(escaped, false, ",\"finishReason\":\"STOP\"")
        assertArrayEquals(response, GoogleContent.read(ByteArrayInputStream(wire.toByteArray()), selected, DeepSeekCancellation()))
        assertEquals("Hello 🙂", selected.latestReply)
        assertEquals("Checking", selected.latestThinking!!.text)
        for (invalid in listOf(wire.replace("STOP", "MAX_TOKENS"), part(escaped, false), part("\"Checking\"", true) + part(escaped, false, ",\"finishReason\":\"STOP\"").replace("g-a", "g-b"))) {
            assertTrue(runCatching { GoogleContent.read(ByteArrayInputStream(invalid.toByteArray()), request(profile), DeepSeekCancellation()) }.isFailure)
        }
        val body = Json.parseToJsonElement(selected.body.toString(Charsets.UTF_8)).jsonObject
        assertTrue(body.containsKey("systemInstruction") && body.containsKey("contents"))
        assertEquals(JsonPrimitive(24576), body.getValue("generationConfig").jsonObject.getValue("thinkingConfig").jsonObject["thinkingBudget"])
    }

    @Test fun catalogEffortDialectsAndCustomOverridesUseTheSelectedModelNotTheDefault() {
        for (provider in AssistantProviderCatalog.providers.filter { it.protocol == AssistantProtocol.OPENAI_COMPATIBLE && it.id != "deepseek" }) {
            val connection = provider.connection()
            if (connection.baseUrl.contains('{')) continue
            for (model in connection.models.filter { connection.protocolFor(it) == AssistantProtocol.OPENAI_COMPATIBLE }) {
                for (effort in model.efforts) {
                    DeepSeekRequest.create("r", "c", "draft:d", "Explain", null, connection = connection, modelId = model.id, effort = DeepSeekEffort.valueOf(effort))
                }
            }
        }
        val connection = profile(AssistantProtocol.OPENAI_COMPATIBLE).copy(compatibility = AssistantCompatibility(developerRole = true, maxTokensField = "max_completion_tokens", thinkingFormat = "qwen"))
        val body = Json.parseToJsonElement(request(connection).body.toString(Charsets.UTF_8)).jsonObject
        assertEquals(JsonPrimitive(true), body["enable_thinking"])
        assertFalse(body.containsKey("reasoning_effort"))
        val onlyHigh = connection.copy(models = listOf(connection.defaultModel.copy(efforts = listOf("HIGH"))), defaultEffort = "HIGH")
        assertTrue(DeepSeekRequest.connectionTest(connection = onlyHigh).thinkingEnabled)
    }

    @Test fun httpHeadersAndNoCreditErrorsStaySeparateWithoutLeakingErrorBodies() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var auth: String? = null
        server.createContext("/messages") { exchange ->
            auth = exchange.requestHeaders.getFirst("x-api-key")
            exchange.requestBody.close()
            exchange.responseHeaders.set("Content-Type", "application/json")
            exchange.sendResponseHeaders(429, 0)
            exchange.responseBody.use { it.write("{\"error\":{\"code\":\"insufficient_quota\",\"message\":\"private account fixture\"}}".toByteArray()) }
        }
        server.start()
        try {
            val result = runCatching { AssistantClient(URL("http://127.0.0.1:${server.address.port}/messages")).complete(request(profile(AssistantProtocol.ANTHROPIC_MESSAGES), false), "fixture-provider-key") }.exceptionOrNull() as DeepSeekException
            assertEquals("fixture-provider-key", auth)
            assertEquals(DeepSeekFailure.INSUFFICIENT_BALANCE, result.reason)
            assertFalse(result.toString().contains("private") || result.toString().contains("fixture-provider-key"))
        } finally {
            server.stop(0)
        }
    }

    @Test fun attachmentsAndEndpointPrefixesFollowEachNativeProtocol() {
        val image = AssistantAttachment(UUID.randomUUID().toString(), "Image.png", AttachmentKind.IMAGE, "image/png", 3, 10, 20)
        val attachment = AttachmentContent(image, images = listOf(byteArrayOf(1, 2, 3)))
        for (protocol in listOf(AssistantProtocol.ANTHROPIC_MESSAGES, AssistantProtocol.GOOGLE_GENERATIVE_AI)) {
            val profile = profile(protocol)
            val selected = DeepSeekRequest.create("r", "c", "draft:d", "Read", null, connection = profile, modelId = "model", attachments = listOf(attachment))
            val body = Json.parseToJsonElement(selected.body.toString(Charsets.UTF_8)).jsonObject
            assertTrue(body.toString().contains("AQID") && body.toString().contains("image/png"))
            assertFalse(body.toString().contains("image_url"))
            assertEquals(if (protocol == AssistantProtocol.ANTHROPIC_MESSAGES) "https://example.org/v1/messages" else "https://example.org/v1/models/model:generateContent", AssistantClient.endpoint(profile, "model").toString())
        }
        val connection = AssistantProviderCatalog.providers.first { it.id == "fireworks" }.connection()
        val completions = connection.models.first { it.protocol == AssistantProtocol.OPENAI_COMPATIBLE }
        val messages = connection.models.first { it.protocol == AssistantProtocol.ANTHROPIC_MESSAGES }
        assertEquals("https://api.fireworks.ai/inference/v1/chat/completions", AssistantClient.endpoint(connection, completions.id).toString())
        assertEquals("https://api.fireworks.ai/inference/v1/messages", AssistantClient.endpoint(connection, messages.id).toString())
        val custom = profile(AssistantProtocol.OPENAI_RESPONSES).copy(baseUrl = "https://example.org/proxy", models = listOf(AssistantModelConfig("model", basePath = "/v1/")))
        assertEquals("https://example.org/proxy/v1/responses", AssistantClient.endpoint(custom, "model").toString())
    }

    @Test fun mistralChunksAndCompatibleReasoningAliasesKeepThinkingSeparateFromWorkflowJson() {
        val selected = request(profile(AssistantProtocol.OPENAI_COMPATIBLE), false)
        val root = Json.parseToJsonElement("{\"object\":\"chat.completion\",\"model\":\"model\",\"choices\":[{\"index\":0,\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\",\"content\":[{\"type\":\"thinking\",\"thinking\":[{\"type\":\"text\",\"text\":\"Checking\"}]},{\"type\":\"text\",\"text\":$escaped}]}}]}").jsonObject
        assertArrayEquals(response, AssistantClient.decode(root.toString().toByteArray(), selected))
        assertEquals("Checking", selected.latestThinking!!.text)
        val aliases = Json.parseToJsonElement("{\"reasoning\":\"One thought\",\"reasoning_text\":\"One thought\"}").jsonObject
        assertEquals("One thought", AssistantClient.reasoningText(aliases, selected))
    }
}

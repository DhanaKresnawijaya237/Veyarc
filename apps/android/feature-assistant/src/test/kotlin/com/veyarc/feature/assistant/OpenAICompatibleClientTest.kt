package com.veyarc.feature.assistant

import com.sun.net.httpserver.HttpServer
import com.veyarc.data.storage.AssistantConnection
import com.veyarc.data.storage.AssistantModelConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.net.URL
import java.util.UUID

class OpenAICompatibleClientTest {
    private val profile = AssistantConnection(UUID.randomUUID().toString(), "Custom", "https://example.org/v1", models = listOf(AssistantModelConfig("model-alias", jsonOutput = true, efforts = listOf("OFF", "HIGH"))), defaultModelId = "model-alias")
    private fun request(stream: Boolean = true, reply: (String) -> Unit = {}) = DeepSeekRequest.create("r", "c", "draft:d", "Explain", null, streaming = stream, connection = profile, modelId = profile.defaultModelId, onReply = reply)
    private val content = AssistantResponseCodec.encode(AssistantResponse(1, "r", "c", "draft:d", AssistantOutcome.EXPLANATION, "First paragraph. Second paragraph."))

    @Test fun customRequestOmitsDeepSeekExtensionsAndHonorsConfiguredCapabilities() {
        val body = Json.parseToJsonElement(request().body.toString(Charsets.UTF_8)).jsonObject
        assertEquals(JsonPrimitive("model-alias"), body["model"])
        assertFalse(body.containsKey("thinking"))
        assertFalse(body.containsKey("max_tokens"))
        assertFalse(body.containsKey("reasoning_effort"))
        assertTrue(body.containsKey("response_format"))
        val plain = profile.copy(models = listOf(profile.defaultModel.copy(jsonOutput = false)))
        val noJsonMode = DeepSeekRequest.create("r", "c", "draft:d", "Explain", null, connection = plain, modelId = plain.defaultModelId)
        assertFalse(Json.parseToJsonElement(noJsonMode.body.toString(Charsets.UTF_8)).jsonObject.containsKey("response_format"))
    }

    @Test fun streamingAcceptsDatedAliasAndUsageButStillRequiresValidatedCompletion() = server { server, endpoint ->
        val updates = mutableListOf<String>()
        val selected = request { updates += it }
        var valid = true
        server.createContext("/chat/completions") { exchange ->
            assertEquals("Bearer fixture-custom-key", exchange.requestHeaders.getFirst("Authorization"))
            exchange.requestBody.close()
            val text = content.toString(Charsets.UTF_8)
            val wire = chunk(text.take(120), null, role = true) + chunk(text.drop(120), if (valid) "stop" else "length") +
                "data: {\"id\":\"completion\",\"object\":\"chat.completion.chunk\",\"model\":\"model-alias-2026\",\"choices\":[],\"usage\":{\"total_tokens\":20}}\n\n" + "data: [DONE]\n\n"
            exchange.responseHeaders.set("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.use { it.write(wire.toByteArray()) }
        }
        assertArrayEquals(content, ChatCompletionClient(endpoint).complete(selected, "fixture-custom-key"))
        assertEquals("First paragraph. Second paragraph.", selected.latestReply)
        assertTrue(updates.isNotEmpty())
        valid = false
        assertEquals(DeepSeekFailure.INVALID_RESPONSE, (runCatching { ChatCompletionClient(endpoint).complete(request(), "fixture-custom-key") }.exceptionOrNull() as DeepSeekException).reason)
    }

    @Test fun errorBodiesNeverExposeCredentialsOrServerDetails() = server { server, endpoint ->
        server.createContext("/chat/completions") { exchange ->
            exchange.requestBody.close()
            exchange.sendResponseHeaders(401, 0)
            exchange.responseBody.use { it.write("fixture-custom-key sensitive server details".toByteArray()) }
        }
        val error = runCatching { ChatCompletionClient(endpoint).complete(request(false), "fixture-custom-key") }.exceptionOrNull()!!
        assertEquals(DeepSeekFailure.KEY_REQUIRED, (error as DeepSeekException).reason)
        assertFalse(error.toString().contains("fixture-custom-key"))
        assertFalse(error.toString().contains("sensitive"))
    }

    private fun chunk(text: String, finish: String?, role: Boolean = false): String = "data: " + buildJsonObject {
        put("id", "completion")
        put("object", "chat.completion.chunk")
        put("model", "model-alias-2026")
        put(
            "choices",
            JsonArray(
                listOf(
                    buildJsonObject {
                        put("index", 0)
                        put(
                            "delta",
                            buildJsonObject {
                                if (role) put("role", "assistant")
                                put("content", text)
                            },
                        )
                        put("finish_reason", finish?.let(::JsonPrimitive) ?: JsonNull)
                    },
                ),
            ),
        )
    }.toString() + "\n\n"

    private fun server(test: (HttpServer, URL) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.start()
        try {
            test(server, URL("http://127.0.0.1:${server.address.port}/chat/completions"))
        } finally {
            server.stop(0)
        }
    }
}

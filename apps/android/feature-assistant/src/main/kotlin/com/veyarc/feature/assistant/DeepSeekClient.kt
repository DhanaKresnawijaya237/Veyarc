package com.veyarc.feature.assistant

import com.veyarc.data.storage.AssistantConnection
import com.veyarc.data.storage.AssistantKeyStore
import com.veyarc.data.storage.AssistantModelConfig
import com.veyarc.model.WorkflowJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Call
import okhttp3.ConnectionPool
import okhttp3.CookieJar
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.net.URL
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.GZIPInputStream

enum class DeepSeekFailure(val message: String) {
    KEY_REQUIRED("Add or replace this connection's API key in Settings."),
    ACCESS_DENIED("The provider denied access. Check your account and API key."),
    INSUFFICIENT_BALANCE("Check your provider account balance."),
    INVALID_REQUEST("The request is invalid. Review the message and workflow."),
    REQUEST_TOO_LARGE("This message or workflow is too large to send. Shorten it and try again."),
    RATE_LIMITED("Provider rate limit reached. Try again later."),
    UNAVAILABLE("The provider is unavailable. Try again later."),
    NETWORK("Could not connect to the provider. Check your connection."),
    TIMEOUT("The request timed out. Retry only when ready; charges may still apply."),
    INVALID_RESPONSE("The provider returned an incomplete or unsupported response."),
    RESPONSE_TOO_LARGE("The provider's response exceeded the local size limit."),
    BUSY("An AI request is already running."),
    CANCELLED("The AI request was cancelled. No response was accepted."),
}

/** No raw body, headers, key, or underlying exception is exposed to callers/logging. */
class DeepSeekException(val reason: DeepSeekFailure) : Exception(reason.message)

/** Blocking adapter. Call off the UI thread. App dispatch and lifecycle binding belong to 4E. */
class ChatCompletionClient internal constructor(
    private val endpoint: URL,
    private val connectTimeoutMs: Int = 15000,
    private val readTimeoutMs: Int = 90000,
    private val deadlineMs: Long? = null,
) {
    constructor() : this(URL("https://api.deepseek.com/chat/completions"))
    constructor(connection: AssistantConnection) : this(URL(connection.also { it.validate() }.baseUrl.trimEnd('/') + "/chat/completions"))

    init {
        require(
            (endpoint.protocol == "https" && endpoint.userInfo == null && endpoint.query == null && endpoint.ref == null) ||
                (endpoint.protocol == "http" && endpoint.host == "127.0.0.1" && endpoint.userInfo == null),
        )
        require(connectTimeoutMs > 0 && readTimeoutMs > 0 && (deadlineMs == null || deadlineMs > 0))
    }

    private val busy = AtomicBoolean(false)
    private val transport = OkHttpClient.Builder()
        .connectTimeout(connectTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
        .readTimeout(readTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
        .writeTimeout(readTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
        .callTimeout(deadlineMs ?: 0, TimeUnit.MILLISECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .cookieJar(CookieJar.NO_COOKIES)
        .connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS))
        .build()

    /** Owner cancellation closes the same attempt used by explicit Stop/key-change cancellation. */
    suspend fun await(request: AssistantRequest, apiKey: String, cancellation: DeepSeekCancellation = DeepSeekCancellation()): ByteArray = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancellation.cancel() }
        // The blocking worker always releases its connection in complete()'s finally block.
        CoroutineScope(Dispatchers.IO).launch {
            continuation.resumeWith(runCatching { complete(request, apiKey, cancellation) })
        }
    }

    /** Explicit discovery sends a credential, but no chat, attachments or workflow. */
    suspend fun discoverModels(apiKey: String, cancellation: DeepSeekCancellation = DeepSeekCancellation()): List<AssistantModelConfig> = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancellation.cancel() }
        CoroutineScope(Dispatchers.IO).launch { continuation.resumeWith(runCatching { listModels(apiKey, cancellation) }) }
    }

    internal fun listModels(apiKey: String, cancellation: DeepSeekCancellation = DeepSeekCancellation()): List<AssistantModelConfig> {
        if (!AssistantKeyStore.isValidEntry(apiKey)) throw DeepSeekException(DeepSeekFailure.KEY_REQUIRED)
        if (!busy.compareAndSet(false, true)) throw DeepSeekException(DeepSeekFailure.BUSY)
        var call: Call? = null
        try {
            cancellation.checkActive()
            val http = Request.Builder().url(endpoint.toString().removeSuffix("/chat/completions") + "/models")
                .header("Authorization", "Bearer $apiKey").header("Accept", "application/json").header("Accept-Encoding", "identity").get().build()
            val active = transport.newCall(http)
            call = active
            cancellation.attach(active)
            val models = active.execute().use { response ->
                cancellation.checkActive()
                if (response.code != 200) throw DeepSeekException(if (response.code == 401) DeepSeekFailure.KEY_REQUIRED else DeepSeekFailure.UNAVAILABLE)
                require(response.header("Content-Type")?.substringBefore(';')?.trim()?.lowercase() == "application/json")
                val input = when (response.header("Content-Encoding")?.lowercase()) {
                    null, "identity" -> response.body.byteStream()
                    "gzip" -> GZIPInputStream(response.body.byteStream())
                    else -> throw DeepSeekException(DeepSeekFailure.INVALID_RESPONSE)
                }
                val root = BoundedResponse(input, cancellation::checkActive, 512 * 1024).use { parseObject(it.readBytes()) }
                require(root["error"] == null || root["error"] == JsonNull)
                val data = root["data"] as JsonArray
                require(data.size in 1..4096)
                // Availability is not a capability declaration. New models start as text-only.
                data.map { AssistantModelConfig((it as JsonObject).string("id")).also(AssistantModelConfig::validate) }.distinctBy { it.id }
            }
            cancellation.checkActive()
            cancellation.finish()
            return models
        } catch (error: Exception) {
            cancellation.checkActive()
            throw when (error) {
                is DeepSeekException -> error
                is InterruptedIOException -> DeepSeekException(DeepSeekFailure.TIMEOUT)
                is IOException -> DeepSeekException(DeepSeekFailure.NETWORK)
                else -> DeepSeekException(DeepSeekFailure.INVALID_RESPONSE)
            }
        } finally {
            cancellation.detach()
            call?.cancel()
            busy.set(false)
        }
    }

    /** Returns original content bytes, ready for the existing state/validator boundary. No repairs. */
    fun complete(request: AssistantRequest, apiKey: String, cancellation: DeepSeekCancellation = DeepSeekCancellation()): ByteArray {
        if (!AssistantKeyStore.isValidEntry(apiKey)) throw DeepSeekException(DeepSeekFailure.KEY_REQUIRED)
        if (!busy.compareAndSet(false, true)) throw DeepSeekException(DeepSeekFailure.BUSY)
        var call: Call? = null
        val started = System.nanoTime()
        fun checkActive() {
            cancellation.checkActive()
            if (deadlineMs != null && TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) >= deadlineMs) throw DeepSeekException(DeepSeekFailure.TIMEOUT)
        }
        try {
            checkActive()
            val body = object : RequestBody() {
                override fun contentType() = "application/json".toMediaType()
                override fun contentLength() = request.body.size.toLong()
                override fun isOneShot() = true // Also prohibits status-driven follow-up POSTs.
                override fun writeTo(sink: BufferedSink) {
                    checkActive()
                    sink.write(request.body)
                }
            }
            val http = Request.Builder().url(endpoint)
                .header("Authorization", "Bearer $apiKey")
                .header("Accept", if (request.streaming) "text/event-stream" else "application/json")
                .header("Accept-Encoding", "identity")
                .post(body).build()
            val active = transport.newCall(http)
            active.timeout().timeout(deadlineMs ?: 0, TimeUnit.MILLISECONDS)
            call = active
            cancellation.attach(active)
            val content = active.execute().use { response ->
                checkActive()
                if (response.code != 200) {
                    throw DeepSeekException(
                        when (response.code) {
                            401 -> DeepSeekFailure.KEY_REQUIRED
                            402 -> DeepSeekFailure.INSUFFICIENT_BALANCE
                            403 -> DeepSeekFailure.ACCESS_DENIED
                            400, 413, 422 -> DeepSeekFailure.INVALID_REQUEST
                            429 -> DeepSeekFailure.RATE_LIMITED
                            in 500..599 -> DeepSeekFailure.UNAVAILABLE
                            else -> DeepSeekFailure.INVALID_RESPONSE
                        },
                    )
                }
                require(response.header("Content-Type")?.substringBefore(';')?.trim()?.lowercase() == if (request.streaming) "text/event-stream" else "application/json")
                val raw = response.body.byteStream()
                val input = when (response.header("Content-Encoding")?.lowercase()) {
                    null, "identity" -> raw
                    "gzip" -> GZIPInputStream(raw)
                    else -> throw DeepSeekException(DeepSeekFailure.INVALID_RESPONSE)
                }
                BoundedResponse(input, ::checkActive, responseLimit(request)).use { bounded ->
                    if (request.streaming) DeepSeekStream.read(bounded, request, cancellation) else decode(bounded.readBytes(), request)
                }
            }
            checkActive()
            cancellation.finish()
            return content
        } catch (error: Exception) {
            cancellation.checkActive()
            throw when {
                error is InterruptedIOException -> DeepSeekException(DeepSeekFailure.TIMEOUT)
                error is DeepSeekException -> error
                error is IOException -> DeepSeekException(DeepSeekFailure.NETWORK)
                else -> DeepSeekException(DeepSeekFailure.INVALID_RESPONSE)
            }
        } finally {
            cancellation.detach()
            try {
                call?.cancel()
            } finally {
                busy.set(false)
            }
        }
    }

    private class BoundedResponse(input: InputStream, private val checkActive: () -> Unit, private val maxBytes: Int) : FilterInputStream(input) {
        private var total = 0
        override fun read(): Int {
            checkActive()
            val value = `in`.read()
            count(if (value < 0) 0 else 1)
            return value
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            checkActive()
            val size = `in`.read(buffer, offset, minOf(length, maxBytes - total + 1))
            count(maxOf(size, 0))
            return size
        }
        private fun count(size: Int) {
            checkActive()
            total += size
            if (total > maxBytes) throw DeepSeekException(DeepSeekFailure.RESPONSE_TOO_LARGE)
        }
    }

    companion object {
        internal const val MAX_RESPONSE_BYTES = 4 * 1024 * 1024
        internal const val MAX_REASONING_BYTES = 2 * 1024 * 1024

        // Thinking adds many SSE envelopes; each final JSON response still has a byte guard.
        internal fun responseLimit(request: AssistantRequest) = if (request.strictDeepSeek && !request.thinkingEnabled) MAX_RESPONSE_BYTES else 32 * 1024 * 1024

        internal fun reasoningBytes(fields: JsonObject, request: AssistantRequest): Int {
            if (fields["reasoning_content"] == null || fields["reasoning_content"] == JsonNull) return 0
            val size = fields.string("reasoning_content").toByteArray(Charsets.UTF_8).size
            require(!request.strictDeepSeek || request.thinkingEnabled || size == 0)
            if (size > MAX_REASONING_BYTES) throw DeepSeekException(DeepSeekFailure.RESPONSE_TOO_LARGE)
            return size
        }

        internal fun decode(bytes: ByteArray, request: AssistantRequest): ByteArray = try {
            if (bytes.size > responseLimit(request)) throw DeepSeekException(DeepSeekFailure.RESPONSE_TOO_LARGE)
            val envelope = parseObject(bytes)
            require(envelope.string("object") == "chat.completion")
            // The official completion example echoes the alias. Do not guess dated wire-model IDs.
            require(validWireModel(envelope.string("model"), request))
            val choice = (envelope["choices"] as JsonArray).single() as JsonObject
            require(choice["index"] == JsonPrimitive(0) && choice.string("finish_reason") == "stop")
            val message = choice["message"] as JsonObject
            require(message.string("role") == "assistant")
            listOf(envelope, choice, message).forEach { fields ->
                listOf("error", "tool_calls", "function_call", "refusal", "content_filter_results").forEach {
                    require(fields[it] == null || fields[it] == JsonNull)
                }
            }
            reasoningBytes(message, request)
            val content = message.string("content").toByteArray(Charsets.UTF_8)
            if (content.size > AssistantResponseCodec.MAX_BYTES) throw DeepSeekException(DeepSeekFailure.RESPONSE_TOO_LARGE)
            AssistantResponseCodec.decode(content, request.requestId, request.conversationId, request.baseSnapshotId)
            if (request.thinkingEnabled && message["reasoning_content"] != null && message["reasoning_content"] != JsonNull) {
                AssistantThinkingBuffer(request).apply {
                    append(message.string("reasoning_content"))
                    finish()
                }
            }
            content
        } catch (error: DeepSeekException) {
            throw error
        } catch (_: Exception) {
            throw DeepSeekException(DeepSeekFailure.INVALID_RESPONSE)
        }

        internal fun validWireModel(model: String, request: AssistantRequest): Boolean = if (request.strictDeepSeek) model == request.modelId else model.isNotBlank() && model.length <= 256 && model.none(Char::isISOControl)

        internal fun parseObject(bytes: ByteArray): JsonObject {
            val json = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
            WorkflowJson.checkNesting(json, 64)
            AssistantResponseCodec.rejectDuplicateKeys(json)
            return WorkflowJson.format.parseToJsonElement(json) as JsonObject
        }

        internal fun JsonObject.string(key: String): String = (getValue(key) as JsonPrimitive).also { require(it.isString) }.content
    }
}

// Existing DeepSeek callers use the same transport and retain strict preset validation.
typealias DeepSeekClient = ChatCompletionClient

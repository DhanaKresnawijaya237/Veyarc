package com.veyarc.data.storage

import kotlinx.serialization.Serializable
import java.net.URI
import java.util.UUID

@Serializable
enum class AssistantProtocol { DEEPSEEK, OPENAI_COMPATIBLE }

@Serializable
data class AssistantModelConfig(
    val id: String,
    val name: String = id,
    val vision: Boolean = false,
    val jsonOutput: Boolean = false,
    val efforts: List<String> = listOf("OFF"),
) {
    fun validate() {
        require(id.isNotBlank() && id.length <= 256 && id.none(Char::isISOControl))
        require(name.isNotBlank() && name.length <= 256 && name.none(Char::isISOControl))
        require(efforts.isNotEmpty() && efforts.distinct() == efforts && efforts.all { it in EFFORTS })
        require("OFF" in efforts)
    }

    companion object {
        val EFFORTS = listOf("OFF", "LOW", "MEDIUM", "HIGH", "MAX")
    }
}

@Serializable
data class AssistantConnection(
    val id: String,
    val name: String,
    val baseUrl: String,
    val protocol: AssistantProtocol = AssistantProtocol.OPENAI_COMPATIBLE,
    val models: List<AssistantModelConfig>,
    val defaultModelId: String,
    val defaultEffort: String = "OFF",
) {
    fun validate() {
        require(validId(id))
        require(name.isNotBlank() && name.length <= 80 && name.none(Char::isISOControl))
        validateBaseUrl(baseUrl)
        require(models.isNotEmpty() && models.map { it.id }.distinct().size == models.size)
        models.forEach(AssistantModelConfig::validate)
        require(models.any { it.id == defaultModelId && defaultEffort in it.efforts })
        if (protocol == AssistantProtocol.DEEPSEEK) require(baseUrl.trimEnd('/') == "https://api.deepseek.com")
    }

    val defaultModel: AssistantModelConfig get() = models.first { it.id == defaultModelId }

    companion object {
        const val DEEPSEEK_ID = "deepseek"
        fun validId(id: String): Boolean = id == DEEPSEEK_ID || runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)
        fun validateBaseUrl(url: String) {
            require(url.length <= 2048 && url.none { it.isWhitespace() || it.isISOControl() })
            val uri = URI(url)
            require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null)
            require(uri.port == -1 || uri.port in 1..65535)
        }
        fun deepSeek(modelId: String = "deepseek-flash", effort: String = "OFF"): AssistantConnection = AssistantConnection(
            DEEPSEEK_ID,
            "DeepSeek",
            "https://api.deepseek.com",
            AssistantProtocol.DEEPSEEK,
            listOf(
                AssistantModelConfig("deepseek-flash", "V4.1 Flash", vision = true, jsonOutput = true, efforts = listOf("OFF", "LOW", "HIGH", "MAX")),
                AssistantModelConfig("deepseek-v4-pro", "V4 Pro", jsonOutput = true, efforts = listOf("OFF", "LOW", "HIGH", "MAX")),
            ),
            modelId,
            effort,
        ).also { it.validate() }
    }
}

@Serializable
data class AssistantConnections(val connections: List<AssistantConnection>, val defaultConnectionId: String, val version: Int = 1) {
    fun validate() {
        require(version == 1 && connections.isNotEmpty())
        require(connections.map { it.id }.distinct().size == connections.size)
        connections.forEach(AssistantConnection::validate)
        require(connections.any { it.id == defaultConnectionId })
    }
}

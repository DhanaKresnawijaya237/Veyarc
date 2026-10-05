package com.veyarc.data.storage

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID

@Serializable
data class AssistantProvider(val id: String, val name: String, val baseUrl: String, val protocol: AssistantProtocol, val models: List<AssistantModelConfig>) {
    fun connection(id: String = UUID.randomUUID().toString()): AssistantConnection {
        val model = models.first()
        return AssistantConnection(id, name, baseUrl, protocol, models, model.id, model.efforts.first(), providerId = this.id, apiVersion = if (this.id == "azure-openai-responses") "2025-04-01-preview" else null)
    }
}

/** Offline metadata from the harness's pinned registry, never inferred from a model's name. */
object AssistantProviderCatalog {
    @Serializable private data class Catalog(val providers: List<AssistantProvider>)
    val providers: List<AssistantProvider> by lazy {
        val json = checkNotNull(javaClass.getResourceAsStream("/assistant-providers.json")).bufferedReader().use { it.readText() }
        Json { ignoreUnknownKeys = true }.decodeFromString<Catalog>(json).providers.also { providers ->
            require(providers.map { it.id }.distinct().size == providers.size)
            providers.forEach { provider -> provider.models.forEach(AssistantModelConfig::validate) }
        }
    }
    fun models(connection: AssistantConnection): List<AssistantModelConfig> = if (connection.catalogProviderId == null) emptyList() else providers.firstOrNull { it.id == connection.catalogProviderId }?.models.orEmpty()
}

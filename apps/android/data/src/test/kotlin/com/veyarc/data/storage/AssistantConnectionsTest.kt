package com.veyarc.data.storage

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class AssistantConnectionsTest {
    @Test fun nativeOpenAIIsExplicitAndExistingCompatibleProfilesStayCompatible() {
        val profile = AssistantConnection.openAI()
        profile.validate()
        assertEquals(AssistantProtocol.OPENAI_RESPONSES, profile.protocol)
        assertTrue(profile.defaultModel.vision && profile.defaultModel.reasoningSummary)
        assertEquals(listOf("OFF", "LOW", "MEDIUM", "HIGH", "XHIGH"), profile.defaultModel.efforts)
        assertEquals(profile, Json.decodeFromString<AssistantConnection>(Json.encodeToString(profile)))
        profile.copy(baseUrl = "https://example.org/v1").validate()
        val compatible = profile.copy(protocol = AssistantProtocol.OPENAI_COMPATIBLE)
        assertEquals(AssistantProtocol.OPENAI_COMPATIBLE, Json.decodeFromString<AssistantConnection>(Json.encodeToString(compatible)).protocol)
    }

    @Test fun catalogUsesDeclaredCapabilitiesAndKeepsEffortMappingsAndProtocolPaths() {
        val providers = AssistantProviderCatalog.providers
        assertEquals(33, providers.size)
        val moonshot = providers.first { it.id == "moonshotai" }.connection()
        moonshot.validate()
        assertTrue(moonshot.models.first { it.id == "kimi-k2.6" }.vision)
        assertTrue(!moonshot.models.first { it.id == "kimi-k2-thinking" }.vision)
        val fireworks = providers.first { it.id == "fireworks" }.connection()
        fireworks.validate()
        assertTrue(fireworks.models.any { fireworks.protocolFor(it) == AssistantProtocol.ANTHROPIC_MESSAGES })
        assertTrue(fireworks.models.any { fireworks.baseUrlFor(it).endsWith("/v1") })
        val onlyHigh = AssistantModelConfig("reasoner", efforts = listOf("HIGH"), effortValues = mapOf("HIGH" to "high"))
        onlyHigh.validate()
        val model = onlyHigh.copy(compatibility = AssistantCompatibility(reasoningEffort = false), catalogCompatibility = AssistantCompatibility(developerRole = true, store = true))
        val configured = moonshot.copy(models = listOf(model), defaultModelId = model.id, defaultEffort = "HIGH", compatibility = AssistantCompatibility(store = false))
        val compat = configured.compatibilityFor(model)
        assertEquals(false, compat.reasoningEffort)
        assertEquals(false, compat.store)
        assertEquals(true, compat.developerRole)
        assertEquals(configured, Json.decodeFromString<AssistantConnection>(Json.encodeToString(configured)))
        assertTrue(runCatching { model.copy(basePath = "//different-host").validate() }.isFailure)
        assertTrue(providers.first { it.id == "anthropic" }.connection().canDiscoverModels)
        assertTrue(!configured.copy(providerId = null, protocol = AssistantProtocol.ANTHROPIC_MESSAGES).canDiscoverModels)
    }

    @Test fun profilesValidateAddressesModelsAndDefaultsWithoutCredentials() {
        val deepSeek = AssistantConnection.deepSeek("deepseek-v4-pro", "HIGH")
        val custom = AssistantConnection(UUID.randomUUID().toString(), "My server", "https://example.org/v1", models = listOf(AssistantModelConfig("model-a")), defaultModelId = "model-a")
        val profiles = AssistantConnections(listOf(deepSeek, custom), custom.id)
        profiles.validate()
        assertEquals(profiles, Json.decodeFromString<AssistantConnections>(Json.encodeToString(profiles)))
        listOf("http://example.org", "https://key@example.org", "https://example.org?key=secret", "https://example.org#key", "https://example.org:99999", "https://example.org/\n").forEach { bad ->
            assertTrue(runCatching { custom.copy(baseUrl = bad).validate() }.isFailure)
        }
        assertTrue(runCatching { custom.copy(id = "../secret").validate() }.isFailure)
        assertTrue(runCatching { custom.copy(defaultModelId = "missing").validate() }.isFailure)
        assertTrue(runCatching { custom.copy(defaultEffort = "HIGH").validate() }.isFailure)
        assertTrue(runCatching { profiles.copy(connections = listOf(custom, custom)).validate() }.isFailure)
    }
}

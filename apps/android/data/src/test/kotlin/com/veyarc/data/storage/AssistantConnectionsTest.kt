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
        assertTrue(runCatching { profile.copy(baseUrl = "https://example.org/v1").validate() }.isFailure)
        val compatible = profile.copy(protocol = AssistantProtocol.OPENAI_COMPATIBLE)
        assertEquals(AssistantProtocol.OPENAI_COMPATIBLE, Json.decodeFromString<AssistantConnection>(Json.encodeToString(compatible)).protocol)
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

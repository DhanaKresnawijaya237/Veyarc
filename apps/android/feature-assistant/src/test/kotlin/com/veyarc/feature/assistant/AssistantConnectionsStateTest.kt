package com.veyarc.feature.assistant

import com.veyarc.data.storage.AssistantConnection
import com.veyarc.data.storage.AssistantConnections
import com.veyarc.data.storage.AssistantModelConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class AssistantConnectionsStateTest {
    @Test fun nativeSummarySurvivesChatPersistenceWithoutChangingLegacyThoughts() {
        val profile = AssistantConnection.openAI()
        val turn = PreviewTurn(UUID.randomUUID().toString(), "Explain", PreviewScenario.EXPLANATION, status = PreviewReplyStatus.STOPPED, effort = DeepSeekEffort.HIGH, provider = true, thinking = AssistantThinking("Checking the steps", summary = true), connectionId = profile.id, modelId = profile.defaultModelId, providerName = profile.name, modelName = profile.defaultModel.name)
        val chat = PreviewConversation(UUID.randomUUID().toString(), null, turns = listOf(turn), connectionId = profile.id, modelId = profile.defaultModelId, providerName = profile.name, modelName = profile.defaultModel.name)
        assertEquals(chat, AssistantPreviewState.decode(AssistantPreviewState.encode(chat)))
        assertFalse(AssistantThinking("Legacy thoughts").summary)
    }

    @Test fun legacyPayloadGetsDeepSeekBindingWithoutChangingMessagesOrMetadata() {
        val turn = PreviewTurn(UUID.randomUUID().toString(), "hello", PreviewScenario.EXPLANATION, model = DeepSeekModel.PRO, status = PreviewReplyStatus.STOPPED)
        val chat = PreviewConversation(UUID.randomUUID().toString(), null, turns = listOf(turn), model = DeepSeekModel.PRO)
        val stored = AssistantPreviewState.encode(chat)
        val bindingFields = setOf("connectionId", "modelId", "providerName", "modelName")
        val objectValue = Json.parseToJsonElement(stored.payload.toString(Charsets.UTF_8)).jsonObject
        val legacy = JsonObject(
            objectValue.filterKeys { it !in bindingFields }.toMutableMap().apply {
                put("turns", kotlinx.serialization.json.JsonArray(listOf(JsonObject(objectValue.getValue("turns").let { it as kotlinx.serialization.json.JsonArray }.single().jsonObject.filterKeys { it !in bindingFields }))))
            },
        )
        assertEquals(chat, AssistantPreviewState.decode(stored.copy(payload = legacy.toString().toByteArray())))
    }

    @Test fun defaultsDoNotSwitchExistingChatsAndRemovedProfilesFailClosed() = runBlocking {
        val custom = AssistantConnection(UUID.randomUUID().toString(), "My server", "https://example.org/v1", models = listOf(AssistantModelConfig("my-model", "My model")), defaultModelId = "my-model")
        val initial = AssistantConnections(listOf(AssistantConnection.deepSeek(), custom), AssistantConnection.DEEPSEEK_ID)
        var saved: AssistantConnections? = null
        val state = AssistantPreviewState(scope = this, providerCall = { error("No request expected") }, onConnectionsChanged = { saved = it })
        state.restoreConnections(initial)
        state.open(AssistantPreviewContext())
        val old = state.conversation!!
        assertTrue(state.saveConnections(initial.copy(defaultConnectionId = custom.id)))
        assertEquals(custom.id, saved!!.defaultConnectionId)
        assertEquals(old.connectionId, state.conversation!!.connectionId)
        state.selectConnectionModel(custom.id, custom.defaultModelId)
        state.enableProvider()
        state.compose("Hello")
        assertTrue(state.canSend)
        assertEquals("My server My model", state.activeModelLabel)
        assertTrue(state.saveConnections(initial.copy(connections = listOf(initial.connections.first()))))
        assertFalse(state.canSend)
        assertEquals(custom.id, state.conversation!!.connectionId)
        state.selectConnectionModel(AssistantConnection.DEEPSEEK_ID, "deepseek-flash")
        assertTrue(state.canSend)
        state.dispose()
    }

    @Test fun discoveryPreservesKnownFeaturesAndCancelledResultsStayOutOfSettings() = runBlocking {
        val result = CompletableDeferred<List<AssistantModelConfig>>()
        var calls = 0
        val state = AssistantPreviewState(scope = this, providerCall = { error("No chat expected") }, modelDiscovery = {
            calls++
            withContext(NonCancellable) { result.await() }
        })
        val custom = AssistantConnection.deepSeek().copy(providerId = null, baseUrl = "https://example.org/v1")
        state.restoreConnections(AssistantConnections(listOf(custom), custom.id))
        state.discoverModels(AssistantConnection.DEEPSEEK_ID)
        kotlinx.coroutines.withTimeout(5000) { while (calls == 0) kotlinx.coroutines.delay(1) }
        state.stopModelDiscovery()
        result.complete(listOf(AssistantModelConfig("deepseek-flash")))
        yield()
        assertTrue(state.discoveredModels.isEmpty())
        state.discoverModels(AssistantConnection.DEEPSEEK_ID)
        kotlinx.coroutines.withTimeout(5000) { while (state.discoveringModels) kotlinx.coroutines.delay(1) }
        assertTrue(state.discoveredModels.single().vision)
        assertEquals(listOf("OFF", "LOW", "HIGH", "MAX"), state.discoveredModels.single().efforts)
        state.dispose()
    }

    @Test fun testsEveryProfileWithoutEnablingChatAndReportsFailuresSeparately() = runBlocking {
        val first = AssistantConnection.deepSeek()
        val second = AssistantConnection.openAI()
        val calls = mutableListOf<String>()
        val state = AssistantPreviewState(scope = this, providerCall = { request ->
            calls += request.connection.id
            if (request.connection.id == first.id) throw DeepSeekException(DeepSeekFailure.INSUFFICIENT_BALANCE)
            AssistantResponseCodec.encode(AssistantResponse(1, request.requestId, request.conversationId, request.baseSnapshotId, AssistantOutcome.EXPLANATION, "Connected"))
        })
        state.restoreConnections(AssistantConnections(listOf(first, second), first.id))
        state.testConnection()
        yield()
        assertEquals(listOf(first.id, second.id), calls)
        assertFalse(state.providerEnabled)
        assertTrue(state.connectionTestResults[first.id]!!.contains("balance"))
        assertEquals("Connected", state.connectionTestResults[second.id])
        assertEquals("1 of 2 connections ready.", state.connectionTestStatus)
        assertTrue(state.conversations.isEmpty())
        state.dispose()
    }

    @Test fun newChatWaitsForSavedDefaultConnectionBeforeItIsCreated() = runBlocking {
        val custom = AssistantConnection(UUID.randomUUID().toString(), "Custom", "https://example.org/v1", models = listOf(AssistantModelConfig("my-model", efforts = listOf("OFF", "MEDIUM"))), defaultModelId = "my-model", defaultEffort = "MEDIUM")
        val state = AssistantPreviewState(scope = this, providerCall = { error("No request expected") }, onConnectionsChanged = {})
        state.open(AssistantPreviewContext())
        assertTrue(state.conversation == null)
        state.restoreConnections(AssistantConnections(listOf(custom), custom.id))
        assertEquals(custom.id, state.conversation!!.connectionId)
        assertEquals(DeepSeekEffort.MEDIUM, state.conversation!!.effort)
        state.setEffort(DeepSeekEffort.HIGH)
        assertEquals(DeepSeekEffort.MEDIUM, state.conversation!!.effort)
        state.dispose()
    }

    @Test fun switchingModelsUsesSavedProviderEffortAndFallsBackWhenUnsupported() = runBlocking {
        val deepSeek = AssistantConnection.deepSeek(effort = "HIGH")
        val custom = AssistantConnection(
            UUID.randomUUID().toString(),
            "Custom",
            "https://example.org/v1",
            models = listOf(
                AssistantModelConfig("default", efforts = listOf("OFF", "MEDIUM")),
                AssistantModelConfig("other", efforts = listOf("OFF", "MEDIUM")),
                AssistantModelConfig("text-only"),
            ),
            defaultModelId = "default",
            defaultEffort = "MEDIUM",
        )
        val state = AssistantPreviewState(scope = this, providerCall = { error("No requests expected") })
        try {
            state.restoreConnections(AssistantConnections(listOf(deepSeek, custom), deepSeek.id))
            state.open(AssistantPreviewContext())
            state.setEffort(DeepSeekEffort.OFF)
            state.selectConnectionModel(deepSeek.id, "deepseek-v4-pro")
            assertEquals(DeepSeekEffort.HIGH, state.conversation!!.effort)
            state.selectConnectionModel(custom.id, "default")
            assertEquals(DeepSeekEffort.MEDIUM, state.conversation!!.effort)
            state.setEffort(DeepSeekEffort.OFF)
            state.selectConnectionModel(custom.id, "other")
            assertEquals(DeepSeekEffort.MEDIUM, state.conversation!!.effort)
            state.selectConnectionModel(custom.id, "text-only")
            assertEquals(DeepSeekEffort.OFF, state.conversation!!.effort)
            assertEquals("MEDIUM", state.connections.connections.last().defaultEffort)
        } finally {
            state.dispose()
        }
    }
}

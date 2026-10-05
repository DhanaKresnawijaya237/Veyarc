package com.veyarc.feature.assistant

import com.veyarc.data.storage.AssistantConnection
import com.veyarc.data.storage.AssistantConnections
import com.veyarc.data.storage.AssistantModelConfig
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class AssistantConnectionsStateTest {
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
}

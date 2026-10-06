package com.veyarc.app

import com.veyarc.data.storage.AssistantCompatibility
import com.veyarc.data.storage.AssistantModelConfig
import org.junit.Assert.assertEquals
import org.junit.Test

class AssistantConnectionEditorTest {
    @Test fun listedAdaptiveLevelsDoNotOfferAliasesButBudgetThinkingKeepsDistinctBudgets() {
        val model = AssistantModelConfig("model", efforts = listOf("OFF", "MINIMAL", "LOW", "HIGH"), effortValues = mapOf("OFF" to "off", "MINIMAL" to "low", "LOW" to "low", "HIGH" to "high"), catalogCompatibility = AssistantCompatibility(adaptiveThinking = true))
        val listed = listedModelOptions(model)
        assertEquals(listOf("OFF", "LOW", "HIGH"), listed.efforts)
        assertEquals(mapOf("OFF" to "off", "LOW" to "low", "HIGH" to "high"), listed.effortValues)
        listed.validate()
        val budgetModel = model.copy(catalogCompatibility = AssistantCompatibility(adaptiveThinking = false))
        assertEquals(budgetModel, listedModelOptions(budgetModel))
    }
}

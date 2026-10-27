@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.veyarc.feature.assistant

import com.veyarc.model.AndCondition
import com.veyarc.model.AssetReference
import com.veyarc.model.Condition
import com.veyarc.model.IfElse
import com.veyarc.model.NotCondition
import com.veyarc.model.OrCondition
import com.veyarc.model.Repeat
import com.veyarc.model.RunLimits
import com.veyarc.model.SwipePath
import com.veyarc.model.VariableDeclaration
import com.veyarc.model.ViewportSpec
import com.veyarc.model.WaitUntil
import com.veyarc.model.WhileStep
import com.veyarc.model.WorkflowJson
import com.veyarc.model.WorkflowStep
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.encodeToStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.security.MessageDigest

/** Explicit shared-field allowlist. Raw editor text is preserved, even when not yet valid. */
@Serializable
data class AssistantDraftContext(
    val workflowId: String,
    val lastSavedRevision: Int?,
    val latestKnownRevision: Int?,
    val name: String,
    val allowedPackagesText: String,
    val viewport: ViewportSpec,
    val runLimits: RunLimits,
    val variables: Map<String, VariableDeclaration>,
    val assets: List<AssetReference>,
    val steps: List<WorkflowStep>,
    val schemaVersion: Int = 1,
)

/** An immutable text base, persisted with its turn. It grants no permission to apply or execute. */
@Serializable
data class AssistantContextSnapshot(val workflowId: String, val snapshotId: String, val json: String) {
    fun validate() {
        val bytes = json.toByteArray(Charsets.UTF_8)
        require(bytes.size in 1..MAX_BYTES && snapshotId == digest(bytes))
        WorkflowJson.checkNesting(json, 64)
        val decoded = WorkflowJson.decodeStrict(AssistantDraftContext.serializer(), json)
        require(decoded.workflowId == workflowId)
        checkBounds(decoded)
    }

    companion object {
        const val MAX_BYTES = 256 * 1024
        const val MAX_STEPS = 200
        const val MAX_DEPTH = 12

        fun capture(draft: AssistantDraftContext): AssistantContextSnapshot {
            checkBounds(draft)
            val bytes = ByteArrayOutputStream()
            val bounded = object : OutputStream() {
                override fun write(value: Int) {
                    require(bytes.size() < MAX_BYTES) { "Workflow context is too large to send safely. No blocks were omitted." }
                    bytes.write(value)
                }
                override fun write(buffer: ByteArray, offset: Int, length: Int) {
                    require(length <= MAX_BYTES - bytes.size()) { "Workflow context is too large to send safely. No blocks were omitted." }
                    bytes.write(buffer, offset, length)
                }
            }
            WorkflowJson.format.encodeToStream(AssistantDraftContext.serializer(), draft.copy(variables = draft.variables.toSortedMap()), bounded)
            val payload = bytes.toByteArray()
            return AssistantContextSnapshot(draft.workflowId, digest(payload), payload.toString(Charsets.UTF_8))
        }

        private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

        internal fun checkBounds(draft: AssistantDraftContext) {
            require(draft.schemaVersion == 1 && draft.workflowId.length in 1..128)
            require(draft.name.length <= 1000 && draft.allowedPackagesText.length <= 4000) { "Workflow name or package text exceeds the context limit. Shorten it before sending." }
            require(draft.variables.size <= 100 && draft.assets.size <= 64) { "Workflow context supports at most 100 variables and 64 image references." }
            var steps = 0
            var conditions = 0
            fun condition(value: Condition, depth: Int) {
                require(++conditions <= 400 && depth <= MAX_DEPTH) { "Workflow conditions exceed the context limit: 400 conditions, 12 nesting levels." }
                when (value) {
                    is AndCondition -> value.conditions.forEach { condition(it, depth + 1) }
                    is OrCondition -> value.conditions.forEach { condition(it, depth + 1) }
                    is NotCondition -> condition(value.condition, depth + 1)
                    else -> Unit
                }
            }
            fun visit(values: List<WorkflowStep>, depth: Int) {
                require(depth <= MAX_DEPTH) { "Workflow context supports at most 12 nesting levels." }
                values.forEach { step ->
                    require(++steps <= MAX_STEPS) { "Workflow context supports at most 200 blocks. No blocks were omitted." }
                    when (step) {
                        is com.veyarc.model.ReusableSection -> visit(step.bodySteps, depth + 1)

                        is Repeat -> visit(step.bodySteps, depth + 1)

                        is WhileStep -> {
                            condition(step.condition, 1)
                            visit(step.bodySteps, depth + 1)
                        }

                        is IfElse -> {
                            condition(step.condition, 1)
                            visit(step.thenSteps, depth + 1)
                            visit(step.elseSteps, depth + 1)
                        }

                        is WaitUntil -> condition(step.condition, 1)

                        is SwipePath -> require(step.points.size <= 4096) { "A swipe exceeds the context limit of 4096 points." }

                        else -> Unit
                    }
                }
            }
            visit(draft.steps, 1)
        }
    }
}

data class AssistantContextCapture(val snapshot: AssistantContextSnapshot? = null, val error: String? = null, val localBindingId: String? = null)

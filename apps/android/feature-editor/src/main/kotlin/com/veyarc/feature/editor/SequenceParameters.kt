package com.veyarc.feature.editor

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.veyarc.model.LiteralOperand
import com.veyarc.model.ReusableSection
import com.veyarc.model.RunSection

@Composable
internal fun SequenceParameters(section: ReusableSection, draft: WorkflowDraft, onChange: (Set<String>, Set<String>) -> Unit) {
    var expanded by rememberSaveable(section.id) { mutableStateOf(false) }
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth().clickable(indication = null, interactionSource = null) { expanded = !expanded }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Inputs & outputs", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                Text("${section.inputs.size} in · ${section.outputs.size} out", style = MaterialTheme.typography.labelMedium)
            }
            AnimatedVisibility(expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(if (section.inputs.isEmpty() && section.outputs.isEmpty()) "No parameters: variables are shared." else "Private values. Only chosen outputs return.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    listOf(true, false).forEach { input ->
                        val names = if (input) section.inputs else section.outputs
                        if (!input) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(if (input) "Inputs" else "Outputs", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                            VariableChoice(if (input) "Add input" else "Add output", "Add", draft.variables.filterKeys { it !in names }.map { VeyarcChoice(it.key, it.key, detail = it.value.type.displayName()) }) { name ->
                                onChange(if (input) names + name else section.inputs, if (input) section.outputs else names + name)
                            }
                        }
                        names.forEach { name ->
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                                draft.variables[name]?.let { Text(it.type.displayName(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                IconButton(onClick = { onChange(if (input) names - name else section.inputs, if (input) section.outputs else names - name) }) { Icon(Icons.Outlined.Close, "Remove ${if (input) "input" else "output"} $name") }
                            }
                        }
                    }
                    if (draft.variables.isEmpty()) Text("Add workflow variables first.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
internal fun SequenceCallFields(section: ReusableSection, call: RunSection, draft: WorkflowDraft, onChange: (RunSection) -> Unit) {
    if (section.inputs.isEmpty() && section.outputs.isEmpty()) return
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (section.inputs.isNotEmpty()) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Inputs for this call", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    IconButton(enabled = call.inputs.isNotEmpty(), onClick = { onChange(call.copy(inputs = emptyMap())) }) { Icon(Icons.Outlined.Restore, "Reset sequence inputs") }
                }
                section.inputs.forEach { name ->
                    draft.variables[name]?.let { declaration ->
                        OperandField(name, call.inputs[name] ?: LiteralOperand(declaration.initialValue), draft.variables, declaration.type) { onChange(call.copy(inputs = call.inputs + (name to it))) }
                    }
                }
            }
            if (section.outputs.isNotEmpty()) {
                if (section.inputs.isNotEmpty()) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                Text("Save outputs to", style = MaterialTheme.typography.titleMedium)
                section.outputs.forEach { name ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                        val choices = draft.variables.filter { (key, value) -> value.type == draft.variables[name]?.type && (key == call.outputs[name] || key !in call.outputs.values) }
                        VariableChoice("Save $name to", call.outputs[name] ?: "Discard", listOf(VeyarcChoice("discard", "Discard", groupTitle = "Output")) + choices.map { VeyarcChoice("variable:${it.key}", it.key, group = 1, groupTitle = "Variables") }) { selected ->
                            onChange(call.copy(outputs = if (selected == "discard") call.outputs - name else call.outputs + (name to selected.removePrefix("variable:"))))
                        }
                    }
                }
            }
        }
    }
}

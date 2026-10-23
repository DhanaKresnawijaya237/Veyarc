package com.veyarc.feature.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DataObject
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.veyarc.model.ArithmeticOperator
import com.veyarc.model.CaptureImage
import com.veyarc.model.ComparisonOperator
import com.veyarc.model.LiteralOperand
import com.veyarc.model.NumberValue
import com.veyarc.model.Operand
import com.veyarc.model.ScalarValue
import com.veyarc.model.SetVariable
import com.veyarc.model.VariableCalculation
import com.veyarc.model.VariableComparisonCondition
import com.veyarc.model.VariableDeclaration
import com.veyarc.model.VariableName
import com.veyarc.model.VariableOperand
import com.veyarc.model.VariableType
import com.veyarc.model.variableType

@Composable
internal fun VariableSection(session: WorkflowEditorSession, draft: WorkflowDraft, onChange: (WorkflowDraft) -> Unit) {
    var editing by rememberSaveable(draft.workflowId) { mutableStateOf(false) }
    var selectedName by rememberSaveable(draft.workflowId) { mutableStateOf<String?>(null) }
    val used = androidx.compose.runtime.remember(draft.steps) { draft.usedVariableNames() }
    EditorSetupSection("Variables", Icons.Outlined.DataObject, session.variablesExpanded, { session.variablesExpanded = !session.variablesExpanded }, {
        selectedName = null
        editing = true
    }, "Add variable") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Starting values reset for each run.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            draft.variables.forEach { (name, declaration) ->
                Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(14.dp)).padding(start = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(
                        Modifier.weight(1f).clickable(indication = null, interactionSource = null) {
                            selectedName = name
                            editing = true
                        }.padding(vertical = 8.dp),
                    ) {
                        Text(name, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyLarge)
                        Text("${declaration.type.displayName()} · ${declaration.initialValue.editorText().ifEmpty { "Empty text" }}", style = MaterialTheme.typography.bodySmall, maxLines = 2)
                    }
                    IconButton(enabled = name !in used, onClick = { onChange(draft.removeVariable(name)) }) { Icon(Icons.Outlined.Delete, "Delete variable $name") }
                }
            }
            if (draft.variables.isEmpty()) Text("Add a variable to use it in blocks and conditions.", style = MaterialTheme.typography.bodySmall)
        }
    }
    if (editing) {
        VariableDeclarationDialog(draft, selectedName, selectedName in used, { editing = false }) { name, declaration ->
            onChange(draft.saveVariable(selectedName, name, declaration))
            session.variablesExpanded = true
            editing = false
        }
    }
}

@Composable
private fun VariableDeclarationDialog(draft: WorkflowDraft, originalName: String?, used: Boolean, onDismiss: () -> Unit, onSave: (String, VariableDeclaration) -> Unit) {
    val original = draft.variables[originalName]
    var name by rememberSaveable { mutableStateOf(originalName.orEmpty()) }
    var type by rememberSaveable { mutableStateOf(original?.type ?: VariableType.NUMBER) }
    var text by rememberSaveable { mutableStateOf((original?.initialValue ?: type.defaultValue()).editorText()) }
    val value = parseVariableValue(type, text)
    val nameError = when {
        name.isEmpty() -> null
        runCatching { VariableName(name) }.isFailure -> "Start with a lowercase letter. Use a–z, 0–9 or _, up to 64 characters."
        name != originalName && name in draft.variables -> "This name is already used."
        else -> null
    }
    EditorSheetDialog(compact = true, onDismiss = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (originalName == null) "Add variable" else "Edit variable", style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Name") }, singleLine = true, isError = nameError != null)
            nameError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            if (used) {
                Text(type.displayName(), style = MaterialTheme.typography.bodyLarge)
                Text("Remove its uses before changing the type or deleting it.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                InlineVariableOptions(VariableType.entries.map { it.name to it.displayName() }, type.name) {
                    type = VariableType.valueOf(it)
                    text = type.defaultValue().editorText()
                }
            }
            ScalarValueField(type, text, { text = it }, "Starting value")
            Button(onClick = { onSave(name, VariableDeclaration(type, value!!)) }, enabled = name.isNotEmpty() && nameError == null && value != null, modifier = Modifier.align(Alignment.End)) { Text("Save") }
        }
    }
}

@Composable
internal fun VariableChoice(title: String, label: String, options: List<VeyarcChoice>, selectedId: String? = null, onSelect: (String) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Surface(onClick = { expanded = true }, enabled = options.isNotEmpty(), shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, contentColor = MaterialTheme.colorScheme.primary) {
        Text(label, Modifier.padding(horizontal = 12.dp, vertical = 8.dp), style = MaterialTheme.typography.bodyMedium)
    }
    if (expanded) {
        VeyarcChoicePicker(title = title, options = options, selectedId = selectedId, onDismiss = { expanded = false }, onSelect = {
            expanded = false
            onSelect(it.id)
        })
    }
}

@Composable
private fun ScalarValueField(type: VariableType, text: String, onChange: (String) -> Unit, label: String) {
    if (type == VariableType.BOOLEAN) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            InlineVariableOptions(listOf("true" to "True", "false" to "False"), text, onChange)
        }
    } else {
        TextField(
            shape = RoundedCornerShape(14.dp),
            colors = TextFieldDefaults.colors(focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent),
            value = text, onValueChange = onChange, modifier = Modifier.fillMaxWidth(), label = { Text(label) },
            keyboardOptions = KeyboardOptions(keyboardType = if (type == VariableType.NUMBER) KeyboardType.Decimal else KeyboardType.Text),
            singleLine = type == VariableType.NUMBER, maxLines = if (type == VariableType.NUMBER) 1 else 4,
            isError = parseVariableValue(type, text) == null,
            supportingText = if (parseVariableValue(type, text) == null) {
                { Text(if (type == VariableType.NUMBER) "Enter a finite number." else "Keep text within 4,096 characters.") }
            } else {
                null
            },
        )
    }
}

@Composable
private fun ScalarValueButton(value: ScalarValue, onChange: (ScalarValue) -> Unit) {
    var text by rememberNumericEditorText(value.editorText())
    ScalarValueField(value.variableType(), text, {
        text = it
        parseVariableValue(value.variableType(), it)?.let(onChange)
    }, "Value")
}

@Composable
private fun InlineVariableOptions(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { (id, label) ->
            Surface(
                onClick = { onSelect(id) },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp),
                color = if (id == selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = if (id == selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
            ) {
                Text(label, Modifier.padding(horizontal = 6.dp, vertical = 12.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
internal fun SetVariableFields(step: SetVariable, variables: Map<String, VariableDeclaration>, imageResults: Set<String> = emptySet(), onChange: (SetVariable) -> Unit) {
    androidx.compose.runtime.CompositionLocalProvider(LocalImageResults provides imageResults) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Assign to", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                VariableChoice("Variable", step.name.value, variables.map { VeyarcChoice(it.key, it.key) }, step.name.value) { name ->
                    onChange(step.copy(name = VariableName(name), value = variables.getValue(name).initialValue, source = null, calculation = null))
                }
            }
            val type = variables[step.name.value]?.type ?: step.value.variableType()
            OperandField("Value", step.source ?: LiteralOperand(step.value), variables, type) { source ->
                onChange(if (source is LiteralOperand) step.copy(value = source.value, source = null) else step.copy(source = source))
            }
            if (type == VariableType.NUMBER) {
                VariableChoice("Calculation", step.calculation?.operator?.displayName() ?: "Set value", listOf(VeyarcChoice("none", "Set value")) + ArithmeticOperator.entries.map { VeyarcChoice(it.name, it.displayName()) }, step.calculation?.operator?.name ?: "none") { selected ->
                    onChange(step.copy(calculation = if (selected == "none") null else VariableCalculation(ArithmeticOperator.valueOf(selected), step.calculation?.operand ?: LiteralOperand(NumberValue(1.0)))))
                }
                step.calculation?.let { calculation ->
                    OperandField("With", calculation.operand, variables, VariableType.NUMBER) { onChange(step.copy(calculation = calculation.copy(operand = it))) }
                }
            }
            if (step.name.value !in variables) Text("Add this variable in Variables, or choose another.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
internal fun NumberVariableBinding(title: String, selected: VariableName?, variables: Map<String, VariableDeclaration>, onChange: (VariableName?) -> Unit) {
    val numbers = variables.filterValues { it.type == VariableType.NUMBER }
    if (numbers.isNotEmpty() || selected != null) {
        VariableChoice(title, selected?.value ?: "Use variable", listOf(VeyarcChoice("fixed", "Fixed number")) + numbers.map { VeyarcChoice("variable:${it.key}", it.key) }, selected?.let { "variable:${it.value}" } ?: "fixed") {
            onChange(if (it == "fixed") null else VariableName(it.removePrefix("variable:")))
        }
    }
}

@Composable
internal fun CaptureImageFields(step: CaptureImage, onRename: (VariableName) -> Unit) = RunValueNameFields(step.name, "Save capture as", onRename)

@Composable
internal fun RunValueNameFields(value: VariableName, title: String, onRename: (VariableName) -> Unit) {
    var editing by rememberSaveable { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Column {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text("This run only", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Surface(onClick = { editing = true }, shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, contentColor = MaterialTheme.colorScheme.primary) {
            Text(value.value, Modifier.padding(horizontal = 14.dp, vertical = 10.dp), style = MaterialTheme.typography.bodyMedium)
        }
    }
    if (editing) {
        var name by rememberSaveable { mutableStateOf(value.value) }
        val parsed = runCatching { VariableName(name) }.getOrNull()
        EditorSheetDialog(compact = true, onDismiss = { editing = false }) {
            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Name", style = MaterialTheme.typography.titleLarge)
                OutlinedTextField(
                    name,
                    { name = it },
                    Modifier.fillMaxWidth(),
                    singleLine = true,
                    isError = parsed == null,
                    supportingText = { Text("Use lowercase letters, numbers and _. Reuse a name to replace its value.") },
                )
                Button(enabled = parsed != null, onClick = {
                    onRename(parsed!!)
                    editing = false
                }, modifier = Modifier.align(Alignment.End)) { Text("Apply") }
            }
        }
    }
}

@Composable
internal fun VariableComparisonFields(condition: VariableComparisonCondition, variables: Map<String, VariableDeclaration>, onChange: (VariableComparisonCondition) -> Unit) {
    val type = when (val left = condition.left) {
        is LiteralOperand -> left.value.variableType()
        is VariableOperand -> variables[left.name.value]?.type
        is com.veyarc.model.ImageResultOperand -> if (left.field == com.veyarc.model.ImageResultField.FOUND) VariableType.BOOLEAN else VariableType.NUMBER
    }
    OperandField("Compare", condition.left, variables, null) { left ->
        val nextType = when (left) {
            is LiteralOperand -> left.value.variableType()
            is VariableOperand -> variables.getValue(left.name.value).type
            is com.veyarc.model.ImageResultOperand -> if (left.field == com.veyarc.model.ImageResultField.FOUND) VariableType.BOOLEAN else VariableType.NUMBER
        }
        onChange(if (nextType == type) condition.copy(left = left) else condition.copy(left = left, operator = ComparisonOperator.EQUALS, right = LiteralOperand(nextType.defaultValue())))
    }
    val operators = ComparisonOperator.entries.filter { type == VariableType.NUMBER || it == ComparisonOperator.EQUALS || it == ComparisonOperator.NOT_EQUALS }
    VariableChoice("Comparison", condition.operator.displayName(), operators.map { VeyarcChoice(it.name, it.displayName()) }, condition.operator.name) { onChange(condition.copy(operator = ComparisonOperator.valueOf(it))) }
    OperandField("With", condition.right, variables, type ?: VariableType.NUMBER) { onChange(condition.copy(right = it)) }
}

@Composable
private fun OperandField(title: String, operand: Operand, variables: Map<String, VariableDeclaration>, type: VariableType?, onChange: (Operand) -> Unit) {
    val available = variables.filterValues { type == null || it.type == type }
    val types = type?.let(::listOf) ?: VariableType.entries
    val results = LocalImageResults.current.flatMap { name ->
        com.veyarc.model.ImageResultField.entries.mapNotNull { field ->
            val fieldType = if (field == com.veyarc.model.ImageResultField.FOUND) VariableType.BOOLEAN else VariableType.NUMBER
            if (type == null || type == fieldType) VeyarcChoice("result:$name:${field.name}", "$name / ${field.name.lowercase()}", group = 2) else null
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        VariableChoice(title, if (operand is LiteralOperand) "${operand.value.variableType().displayName()} value" else operand.displayName(), available.map { VeyarcChoice("variable:${it.key}", it.key) } + types.map { VeyarcChoice("literal:${it.name}", "${it.displayName()} value", group = 1) } + results) { selection ->
            onChange(
                if (selection.startsWith("result:")) {
                    val parts = selection.split(':')
                    com.veyarc.model.ImageResultOperand(VariableName(parts[1]), com.veyarc.model.ImageResultField.valueOf(parts[2]))
                } else if (selection.startsWith("variable:")) {
                    VariableOperand(VariableName(selection.removePrefix("variable:")))
                } else {
                    LiteralOperand(VariableType.valueOf(selection.removePrefix("literal:")).defaultValue())
                },
            )
        }
    }
    if (operand is LiteralOperand) ScalarValueButton(operand.value) { onChange(LiteralOperand(it)) }
    if (operand is VariableOperand && operand.name.value !in variables) Text("${operand.name.value} is missing from Variables.", color = MaterialTheme.colorScheme.error)
}

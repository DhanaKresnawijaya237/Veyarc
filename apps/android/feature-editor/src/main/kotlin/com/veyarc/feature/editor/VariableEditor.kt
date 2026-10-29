package com.veyarc.feature.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DataObject
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Image
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
    var useChoices by rememberSaveable { mutableStateOf(original?.choices?.isNotEmpty() == true) }
    var choices by rememberSaveable { mutableStateOf(original?.choices.orEmpty()) }
    var editingChoice by rememberSaveable { mutableStateOf<Int?>(null) }
    var editingDefault by rememberSaveable { mutableStateOf(false) }
    var choiceText by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue()) }
    val reviewChoices = if (type == VariableType.STRING && useChoices) choices.mapIndexed { index, option -> if (index == editingChoice) choiceText.text.trim() else option } else emptyList()
    val reviewText = if (type == VariableType.STRING && useChoices && editingChoice != null && editingDefault) choiceText.text.trim() else text
    val choicesValid = type != VariableType.STRING || !useChoices || (reviewChoices.isNotEmpty() && reviewText in reviewChoices && reviewChoices.distinct().size == reviewChoices.size && reviewChoices.all { it.isNotBlank() && it.length <= 4096 })
    val value = parseVariableValue(type, reviewText)
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val finishChoice = {
        choices = reviewChoices
        text = reviewText
        editingChoice = null
        focusManager.clearFocus()
        keyboard?.hide()
    }
    val nameError = when {
        name.isEmpty() -> null
        runCatching { VariableName(name) }.isFailure -> "Start with a lowercase letter. Use a–z, 0–9 or _, up to 64 characters."
        name != originalName && name in draft.variables -> "This name is already used."
        else -> null
    }
    EditorSheetDialog(compact = true, onDismiss = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (originalName == null) "Add variable" else "Edit variable", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                Button(onClick = { onSave(name, VariableDeclaration(type, value!!, reviewChoices)) }, enabled = name.isNotEmpty() && nameError == null && value != null && choicesValid) { Text("Save") }
            }
            OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Name") }, singleLine = true, isError = nameError != null)
            nameError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            if (used) {
                Text(type.displayName(), style = MaterialTheme.typography.bodyLarge)
                Text("Remove its uses before changing the type or deleting it.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                InlineVariableOptions(VariableType.entries.map { it.name to it.displayName() }, type.name) {
                    type = VariableType.valueOf(it)
                    text = type.defaultValue().editorText()
                    useChoices = false
                    editingChoice = null
                }
            }
            if (type == VariableType.STRING) {
                InlineVariableOptions(listOf("text" to "Free text", "choices" to "Choices"), if (useChoices) "choices" else "text") {
                    if (useChoices && choicesValid) finishChoice()
                    useChoices = it == "choices"
                    editingChoice = null
                    if (useChoices) {
                        if (choices.isEmpty() && text.isNotBlank() && text.length <= 4096) choices = listOf(text)
                        if (text !in choices) text = choices.firstOrNull().orEmpty()
                    }
                }
            }
            if (type == VariableType.STRING && useChoices) {
                Text("Options", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    choices.forEachIndexed { index, option ->
                        val editOption = {
                            if (choicesValid) {
                                choices = reviewChoices
                                text = reviewText
                                editingDefault = reviewText == reviewChoices[index]
                                choiceText = TextFieldValue(reviewChoices[index], TextRange(0, reviewChoices[index].length))
                                editingChoice = index
                            }
                        }
                        Surface(
                            modifier = Modifier.combinedClickable(
                                interactionSource = null,
                                indication = null,
                                onClick = {
                                    if (choicesValid) {
                                        finishChoice()
                                        text = reviewChoices[index]
                                    }
                                },
                                onDoubleClick = editOption,
                                onLongClickLabel = "Edit option",
                                onLongClick = editOption,
                            ),
                            shape = RoundedCornerShape(12.dp),
                            color = if (reviewText == reviewChoices[index]) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                        ) {
                            Row(Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                if (editingChoice == index) {
                                    val focus = remember { FocusRequester() }
                                    LaunchedEffect(Unit) {
                                        focus.requestFocus()
                                        keyboard?.show()
                                    }
                                    BasicTextField(
                                        choiceText,
                                        { choiceText = it },
                                        Modifier.widthIn(min = 64.dp, max = 180.dp).focusRequester(focus).semantics { contentDescription = "Option text" },
                                        singleLine = true,
                                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                        keyboardActions = KeyboardActions(onDone = { if (choicesValid) finishChoice() }),
                                    )
                                } else {
                                    Text(option, Modifier.widthIn(max = 180.dp), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                                }
                                if (reviewText == reviewChoices[index]) Text("Default", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                IconButton(onClick = {
                                    choices = reviewChoices.filterIndexed { optionIndex, _ -> optionIndex != index }
                                    text = reviewText.takeIf { it in choices } ?: choices.firstOrNull().orEmpty()
                                    editingChoice = null
                                    focusManager.clearFocus()
                                    keyboard?.hide()
                                }, modifier = Modifier.size(32.dp)) { Icon(Icons.Outlined.Close, "Remove option $option", Modifier.size(16.dp)) }
                            }
                        }
                    }
                    IconButton(onClick = {
                        val option = generateSequence(choices.size + 1) { it + 1 }.map { "Option $it" }.first { it !in reviewChoices }
                        choices = reviewChoices + option
                        text = reviewText.takeIf { it in reviewChoices } ?: option
                        editingDefault = text == option
                        choiceText = TextFieldValue(option, TextRange(0, option.length))
                        editingChoice = choices.lastIndex
                    }, enabled = editingChoice == null || choicesValid, modifier = Modifier.size(40.dp)) { Icon(Icons.Outlined.Add, "Add option") }
                }
                if (editingChoice != null && !choicesValid) Text("Use a unique, non-empty option up to 4,096 characters.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                if (choices.isEmpty()) Text("Add an option, then tap one to set the default.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                ScalarValueField(type, text, { text = it }, "Starting value")
            }
        }
    }
}

@Composable
fun VariableChoice(title: String, label: String, options: List<VeyarcChoice>, selectedId: String? = null, modifier: Modifier = Modifier, textStyle: TextStyle = MaterialTheme.typography.bodyMedium, onSelect: (String) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Surface(modifier = modifier, onClick = { expanded = true }, enabled = options.isNotEmpty(), shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, contentColor = MaterialTheme.colorScheme.primary) {
        Text(label, Modifier.padding(horizontal = 12.dp, vertical = 8.dp), style = textStyle)
    }
    if (expanded) {
        VeyarcChoicePicker(title = title, options = options, selectedId = selectedId, onDismiss = { expanded = false }, onSelect = {
            expanded = false
            onSelect(it.id)
        })
    }
}

@Composable
fun ScalarValueField(type: VariableType, text: String, onChange: (String) -> Unit, label: String?, modifier: Modifier = Modifier) {
    if (type == VariableType.BOOLEAN) {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            label?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            InlineVariableOptions(listOf("true" to "True", "false" to "False"), text, onChange)
        }
    } else {
        TextField(
            shape = RoundedCornerShape(14.dp),
            colors = TextFieldDefaults.colors(focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent),
            value = text, onValueChange = onChange, modifier = modifier.fillMaxWidth(), label = label?.let { { Text(it) } },
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
private fun ScalarValueButton(value: ScalarValue, showLabel: Boolean = true, onChange: (ScalarValue) -> Unit) {
    var text by rememberNumericEditorText(value.editorText())
    ScalarValueField(value.variableType(), text, {
        text = it
        parseVariableValue(value.variableType(), it)?.let(onChange)
    }, if (showLabel) "Value" else null)
}

@Composable
internal fun InlineVariableOptions(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
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
internal fun NumberValueControl(title: String, selected: VariableName?, variables: Map<String, VariableDeclaration>, onChange: (VariableName?) -> Unit, modifier: Modifier = Modifier, fixedValue: @Composable () -> Unit) {
    val options = numberValueChoices(variables)
    var choosing by rememberSaveable { mutableStateOf(false) }
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        if (selected == null) {
            fixedValue()
            if (options.size > 1) {
                IconButton(onClick = { choosing = true }, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Outlined.DataObject, "Choose $title", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                }
            }
        } else {
            Text(selected.value, Modifier.widthIn(max = 160.dp).clickable(indication = null, interactionSource = null) { choosing = true }.padding(vertical = 6.dp), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    if (choosing) {
        VeyarcChoicePicker(title, options, selected?.let { "variable:${it.value}" } ?: "fixed", onDismiss = { choosing = false }, onSelect = {
            onChange(if (it.id == "fixed") null else VariableName(it.id.removePrefix("variable:")))
            choosing = false
        })
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
    val leftValue: @Composable () -> Unit = {
        OperandField("Value", condition.left, variables, null, emphasized = true) { left ->
            val nextType = when (left) {
                is LiteralOperand -> left.value.variableType()
                is VariableOperand -> variables.getValue(left.name.value).type
                is com.veyarc.model.ImageResultOperand -> if (left.field == com.veyarc.model.ImageResultField.FOUND) VariableType.BOOLEAN else VariableType.NUMBER
            }
            onChange(if (nextType == type) condition.copy(left = left) else condition.copy(left = left, operator = ComparisonOperator.EQUALS, right = LiteralOperand(nextType.defaultValue())))
        }
    }
    val rightValue: @Composable () -> Unit = { OperandField("Compare with", condition.right, variables, type ?: VariableType.NUMBER, emphasized = true) { onChange(condition.copy(right = it)) } }
    val operators = ComparisonOperator.entries.filter { type == VariableType.NUMBER || it == ComparisonOperator.EQUALS || it == ComparisonOperator.NOT_EQUALS }
    val comparison: @Composable () -> Unit = {
        VariableChoice("Comparison", condition.operator.displayName(), operators.map { VeyarcChoice(it.name, it.displayName()) }, condition.operator.name, textStyle = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)) { onChange(condition.copy(operator = ComparisonOperator.valueOf(it))) }
    }
    if (LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            ComparisonOperandPanel(Modifier.weight(1f), leftValue)
            comparison()
            ComparisonOperandPanel(Modifier.weight(1f), rightValue)
        }
    } else {
        ComparisonOperandPanel(content = leftValue)
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { comparison() }
        ComparisonOperandPanel(content = rightValue)
    }
    Text("True when ${condition.comparisonText()}.", Modifier.fillMaxWidth(), textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun ComparisonOperandPanel(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(modifier = modifier, shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }
}

@Composable
internal fun OperandField(title: String, operand: Operand, variables: Map<String, VariableDeclaration>, type: VariableType?, emphasized: Boolean = false, onChange: (Operand) -> Unit) {
    val available = variables.filterValues { type == null || it.type == type }
    val types = type?.let(::listOf) ?: VariableType.entries
    val resultFields = imageResultFields(type)
    val results = if (resultFields.isEmpty()) emptyList() else LocalImageResults.current.map { name -> VeyarcChoice("result:$name", name, group = 2, groupTitle = "Image results", icon = Icons.Outlined.Image) }
    val sourceChoice: @Composable (Modifier) -> Unit = { modifier ->
        VariableChoice(
            title,
            when (operand) {
                is LiteralOperand -> operand.value.variableType().displayName()
                is com.veyarc.model.ImageResultOperand -> operand.name.value
                else -> operand.displayName()
            },
            available.map { VeyarcChoice("variable:${it.key}", it.key, groupTitle = "Variables", icon = Icons.Outlined.DataObject) } + types.map { VeyarcChoice("literal:${it.name}", it.displayName(), group = 1, groupTitle = "Fixed values") } + results,
            modifier = modifier,
        ) { selection ->
            onChange(
                if (selection.startsWith("result:")) {
                    val field = (operand as? com.veyarc.model.ImageResultOperand)?.field?.takeIf { it in resultFields } ?: resultFields.first()
                    com.veyarc.model.ImageResultOperand(VariableName(selection.removePrefix("result:")), field)
                } else if (selection.startsWith("variable:")) {
                    VariableOperand(VariableName(selection.removePrefix("variable:")))
                } else {
                    LiteralOperand(VariableType.valueOf(selection.removePrefix("literal:")).defaultValue())
                },
            )
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = if (emphasized) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium, fontWeight = if (emphasized) FontWeight.Bold else null, color = if (emphasized) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
        sourceChoice(Modifier.widthIn(max = 140.dp))
    }
    if (operand is com.veyarc.model.ImageResultOperand) {
        if (resultFields.size > 1) {
            InlineVariableOptions(resultFields.map { it.name to it.displayName() }, operand.field.name) { field ->
                onChange(operand.copy(field = com.veyarc.model.ImageResultField.valueOf(field)))
            }
        } else {
            Text(operand.field.displayName(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        }
    }
    if (operand is LiteralOperand) ScalarValueButton(operand.value, showLabel = !emphasized) { onChange(LiteralOperand(it)) }
    if (operand is VariableOperand && operand.name.value !in variables) Text("${operand.name.value} is missing from Variables.", color = MaterialTheme.colorScheme.error)
}

@Composable
internal fun ImagePositionFields(binding: com.veyarc.model.ImagePositionBinding?, searches: Map<String, List<com.veyarc.model.FindImage>>, variables: Map<String, VariableDeclaration> = emptyMap(), onValidityChange: (Boolean) -> Unit = {}, onChange: (com.veyarc.model.ImagePositionBinding?) -> Unit) {
    val live = searches.filterValues { definitions -> definitions.all { it.image.capturedBackground == null } }
    var validX by rememberSaveable(binding?.name?.value) { mutableStateOf(true) }
    var validY by rememberSaveable(binding?.name?.value) { mutableStateOf(true) }
    if (live.isEmpty() && binding == null) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Position", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            VariableChoice("Tap position", binding?.name?.value ?: "Fixed point", listOf(VeyarcChoice("fixed", "Fixed point")) + live.keys.map { VeyarcChoice("result:$it", it) }, binding?.let { "result:${it.name.value}" } ?: "fixed") { selected ->
                validX = true
                validY = true
                onValidityChange(true)
                onChange(if (selected == "fixed") null else com.veyarc.model.ImagePositionBinding(VariableName(selected.removePrefix("result:"))))
            }
        }
        binding?.let { value ->
            Text("Center of the matched image", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ImageOffsetField("X offset", value.offsetXPx, value.offsetXVariable, variables, Modifier.weight(1f), {
                    validX = it
                    onValidityChange(validX && validY)
                }, { onChange(value.copy(offsetXVariable = it)) }) { onChange(value.copy(offsetXPx = it)) }
                ImageOffsetField("Y offset", value.offsetYPx, value.offsetYVariable, variables, Modifier.weight(1f), {
                    validY = it
                    onValidityChange(validX && validY)
                }, { onChange(value.copy(offsetYVariable = it)) }) { onChange(value.copy(offsetYPx = it)) }
            }
            if (!validX || !validY) Text("Enter a whole number of pixels.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ImageOffsetField(label: String, value: Int, selected: VariableName?, variables: Map<String, VariableDeclaration>, modifier: Modifier, onValidityChange: (Boolean) -> Unit, onVariableChange: (VariableName?) -> Unit, onChange: (Int) -> Unit) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            NumberValueControl(label, selected, variables, {
                onValidityChange(true)
                onVariableChange(it)
            }, Modifier.weight(1f, fill = false)) {
                InlineLongField(value.toLong(), Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong(), resetKey = null, label = label, onValidityChange = onValidityChange) { onChange(it.toInt()) }
            }
            Text("px", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

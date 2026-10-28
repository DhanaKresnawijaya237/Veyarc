package com.veyarc.feature.runner

import com.veyarc.feature.editor.editorText
import com.veyarc.feature.editor.parseVariableValue
import com.veyarc.model.WorkflowDocument

internal fun WorkflowDocument.startingValues(): Map<String, String> = variables.mapValues { it.value.initialValue.editorText() }

/** Compile a private run copy; the saved revision and its defaults stay unchanged. */
internal fun WorkflowDocument.withStartingValues(values: Map<String, String>): WorkflowDocument? {
    if (values.keys != variables.keys) return null
    val declarations = variables.mapValues { (name, declaration) ->
        if (declaration.choices.isNotEmpty() && values.getValue(name) !in declaration.choices) return null
        val value = parseVariableValue(declaration.type, values.getValue(name)) ?: return null
        declaration.copy(initialValue = value)
    }
    return copy(variables = declarations)
}

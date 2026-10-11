package com.veyarc.feature.editor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable

/** Keep typed zeros when the numeric model echoes an edit back to the field. */
@Composable
internal fun rememberNumericEditorText(value: String, resetKey: Any? = null): MutableState<String> {
    val text = rememberSaveable(resetKey) { mutableStateOf(value) }
    LaunchedEffect(value) {
        if (!sameNumericValue(text.value, value)) text.value = value
    }
    return text
}

internal fun sameNumericValue(typed: String, model: String): Boolean {
    val expected = model.toBigDecimalOrNull() ?: return false
    return typed.toBigDecimalOrNull()?.compareTo(expected) == 0
}

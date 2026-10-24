package com.veyarc.feature.editor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.veyarc.model.ReusableSection

@Composable
internal fun ReusableSectionName(name: String, otherNames: List<String>, onRename: (String) -> Unit) {
    var editing by remember { mutableStateOf(false) }
    var text by remember(name) { mutableStateOf(name) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            name,
            Modifier.clickable(indication = null, interactionSource = null) {
                text = name
                editing = true
            }.padding(vertical = 6.dp).testTag("section-name"),
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.titleMedium,
        )
        Text("Runs when called by a Run section block.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (editing) {
        val valid = text.isNotBlank() && text.trim().length <= 80 && otherNames.none { it.equals(text.trim(), ignoreCase = true) }
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text("Section name") },
            text = { OutlinedTextField(text, { text = it }, modifier = Modifier.fillMaxWidth().testTag("section-name-input"), singleLine = true, isError = !valid) },
            confirmButton = {
                TextButton(enabled = valid, onClick = {
                    onRename(text.trim())
                    editing = false
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editing = false }) { Text("Cancel") } },
        )
    }
}

@Composable
internal fun SectionCallPicker(sections: List<ReusableSection>, selectedId: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Text(sections.firstOrNull { it.id == selectedId }?.name ?: "Choose a section", Modifier.clickable(indication = null, interactionSource = null) { expanded = true }.padding(vertical = 6.dp).testTag("section-call-picker"), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        if (expanded) {
            VeyarcChoicePicker(
                title = "Run section",
                options = sections.map { VeyarcChoice(it.id, it.name) },
                selectedId = selectedId,
                onDismiss = { expanded = false },
                onSelect = {
                    expanded = false
                    onSelect(it.id)
                },
            )
        }
    }
}

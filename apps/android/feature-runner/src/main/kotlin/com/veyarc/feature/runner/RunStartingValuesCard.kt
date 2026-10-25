package com.veyarc.feature.runner

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.veyarc.feature.editor.ScalarValueField
import com.veyarc.feature.editor.displayName
import com.veyarc.model.WorkflowDocument

@Composable
internal fun RunStartingValuesCard(document: WorkflowDocument, values: Map<String, String>, onChange: (String, String) -> Unit, onReset: () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = if (maxWidth >= 600.dp) 2 else 1
        Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Starting values", style = MaterialTheme.typography.titleMedium)
                        Text("Only for this run", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = onReset, enabled = values != document.startingValues()) {
                        Icon(Icons.Outlined.Restore, contentDescription = "Reset starting values")
                    }
                }
                document.variables.entries.chunked(columns).forEachIndexed { index, entries ->
                    if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        entries.forEach { (name, declaration) ->
                            key(name) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        Text(name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                                        Text(declaration.type.displayName(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    ScalarValueField(declaration.type, values[name].orEmpty(), { onChange(name, it) }, label = null, modifier = Modifier.testTag("run-input-$name"))
                                }
                            }
                        }
                        if (entries.size < columns) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

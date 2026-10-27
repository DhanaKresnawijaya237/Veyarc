package com.veyarc.feature.editor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun EditorIssueRow(draft: WorkflowDraft, issue: DraftIssue, showTitle: Boolean = true, onClick: () -> Unit) {
    val display = remember(draft.steps, draft.assetNames, issue) { draft.issueDisplay(issue) }
    Column(Modifier.fillMaxWidth().clickable(indication = null, interactionSource = null, onClick = onClick).padding(vertical = 6.dp)) {
        if (showTitle) Text(display.title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Text(display.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
}

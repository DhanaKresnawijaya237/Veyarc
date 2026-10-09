package com.veyarc.feature.assistant

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@Composable
internal fun AssistantWelcome(state: AssistantPreviewState) {
    val colors = MaterialTheme.colorScheme
    BoxWithConstraints(
        Modifier.fillMaxSize().clipToBounds().testTag("assistant-welcome"),
    ) {
        val compact = maxHeight < 280.dp
        Column(
            Modifier.align(Alignment.Center).widthIn(max = if (compact) 680.dp else 420.dp).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = if (compact) 12.dp else 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("How can I help?", style = if (compact) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
            Text("Create, edit or explore a workflow.", color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
            if (state.context.needsSetup) Text("Choose an app and orientation in the editor first.", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, textAlign = TextAlign.Center)
            val suggestions = if (state.context.needsSetup || state.context.workflowId == null) listOf("Help me create a workflow", "What details do you need?") else listOf("Explain this workflow", "Suggest an improvement", "Describe a change")
            val suggestionButton: @Composable (String, Modifier) -> Unit = { suggestion, modifier ->
                FilledTonalButton(
                    onClick = { state.compose(suggestion) },
                    modifier = modifier,
                    shape = RoundedCornerShape(20.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(containerColor = colors.surfaceContainerHigh.copy(alpha = 0.92f), contentColor = colors.onSurface),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
                ) { Text(suggestion, textAlign = TextAlign.Center) }
            }
            if (compact) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    suggestions.forEach { suggestionButton(it, Modifier.weight(1f)) }
                }
            } else {
                suggestions.forEach { suggestionButton(it, Modifier.fillMaxWidth()) }
            }
        }
    }
}

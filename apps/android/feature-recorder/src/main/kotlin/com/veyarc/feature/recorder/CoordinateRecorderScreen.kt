package com.veyarc.feature.recorder

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun CoordinateRecorderScreen(
    controller: CoordinateRecordingController,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler {
        controller.stop()
        onBack()
    }
    Column(
        modifier = modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Coordinate recording", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Third-party recording is currently unavailable. Veyarc will not start the old " +
                "accessibility interception path because it can disrupt normal phone input.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Button(onClick = onBack) { Text("Back") }
    }
}

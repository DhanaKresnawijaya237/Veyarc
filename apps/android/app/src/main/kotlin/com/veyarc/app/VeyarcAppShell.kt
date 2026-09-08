package com.veyarc.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

enum class VeyarcDestination(
    val label: String,
    val icon: ImageVector,
) {
    HOME("Home", Icons.Outlined.Home),
    WORKFLOWS("Workflows", Icons.Outlined.Folder),
    EDITOR("Editor", Icons.Outlined.Edit),
    RUNS("Runs", Icons.Outlined.History),
    SETTINGS("Settings", Icons.Outlined.Settings),
}

@Composable
fun VeyarcAppShell(
    selectedDestination: VeyarcDestination,
    onDestinationSelected: (VeyarcDestination) -> Unit,
    content: @Composable () -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        if (usesNavigationRail(maxWidth.value, maxHeight.value)) {
            Row(modifier = Modifier.fillMaxSize()) {
                NavigationRail {
                    VeyarcDestination.entries.forEach { destination ->
                        NavigationRailItem(
                            selected = selectedDestination == destination,
                            onClick = { onDestinationSelected(destination) },
                            icon = { DestinationIcon(destination) },
                            label = { Text(destination.label) },
                        )
                    }
                }
                Box(modifier = Modifier.weight(1f)) { content() }
            }
        } else {
            Scaffold(
                bottomBar = {
                    NavigationBar {
                        VeyarcDestination.entries.forEach { destination ->
                            NavigationBarItem(
                                selected = selectedDestination == destination,
                                onClick = { onDestinationSelected(destination) },
                                icon = { DestinationIcon(destination) },
                                label = { Text(destination.label) },
                            )
                        }
                    }
                },
            ) { padding ->
                Box(
                    modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(padding),
                ) {
                    content()
                }
            }
        }
    }
}

@Composable
private fun DestinationIcon(destination: VeyarcDestination) {
    Icon(
        imageVector = destination.icon,
        contentDescription = destination.label,
    )
}

@Composable
fun HomeFoundationScreen(
    onRecord: () -> Unit,
    onOpenWorkflows: () -> Unit,
) {
    FoundationScreen(
        eyebrow = "VEYARC",
        title = "Automate what you repeat",
        body = "Record a workflow, refine its steps, then run it again when you need it.",
    ) {
        Button(onClick = onRecord) { Text("Start recording") }
        OutlinedButton(onClick = onOpenWorkflows) { Text("Open workflows") }
    }
}

@Composable
fun DestinationFoundationScreen(
    destination: VeyarcDestination,
    onOpenWorkflows: () -> Unit,
    onOpenAccessibility: () -> Unit,
) {
    val (title, body) =
        when (destination) {
            VeyarcDestination.EDITOR ->
                "Workflow editor" to "Choose a saved workflow to open it in the editor."

            VeyarcDestination.RUNS ->
                "Runs" to "Detailed run history remains available from the workflow library while this manager is being separated."

            VeyarcDestination.SETTINGS ->
                "Settings" to "Accessibility, Shizuku, and controller setup will live here."

            else -> error("$destination already has dedicated content")
        }
    FoundationScreen(
        eyebrow = destination.label.uppercase(),
        title = title,
        body = body,
    ) {
        if (destination == VeyarcDestination.SETTINGS) {
            Button(onClick = onOpenAccessibility) { Text("Open device setup") }
        } else {
            Button(onClick = onOpenWorkflows) { Text("Open workflows") }
        }
    }
}

@Composable
private fun FoundationScreen(
    eyebrow: String,
    title: String,
    body: String,
    actions: @Composable () -> Unit,
) {
    Column(
        modifier =
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().widthIn(max = 680.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = eyebrow,
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelLarge,
            )
            Text(text = title, style = MaterialTheme.typography.headlineMedium)
            Text(
                text = body,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyLarge,
            )
            Card(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    actions()
                }
            }
        }
    }
}

internal fun usesNavigationRail(
    widthDp: Float,
    heightDp: Float,
): Boolean = widthDp >= 840f && heightDp >= 480f

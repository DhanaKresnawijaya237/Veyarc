package com.veyarc.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity

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
internal fun VeyarcAppShell(
    selectedDestination: VeyarcDestination,
    onDestinationSelected: (VeyarcDestination) -> Unit,
    selectionBar: ManagerSelectionBarState? = null,
    content: @Composable () -> Unit,
) {
    val destinationStates = rememberSaveableStateHolder()
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        if (usesNavigationRail(maxWidth.value, maxHeight.value)) {
            Row(modifier = Modifier.fillMaxSize()) {
                if (selectionBar != null) {
                    ManagerSelectionRail(selectionBar)
                } else {
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
                }
                Box(modifier = Modifier.weight(1f)) {
                    destinationStates.SaveableStateProvider(selectedDestination.name) { content() }
                }
            }
        } else {
            val showLabels = LocalDensity.current.fontScale < 1.5f
            Scaffold(
                bottomBar = {
                    if (selectionBar != null) {
                        ManagerSelectionBar(selectionBar)
                    } else {
                        NavigationBar {
                            VeyarcDestination.entries.forEach { destination ->
                                NavigationBarItem(
                                    selected = selectedDestination == destination,
                                    onClick = { onDestinationSelected(destination) },
                                    icon = { DestinationIcon(destination) },
                                    label = if (showLabels) {
                                        { Text(destination.label, maxLines = 1) }
                                    } else {
                                        null
                                    },
                                )
                            }
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
                    destinationStates.SaveableStateProvider(selectedDestination.name) { content() }
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

internal fun usesNavigationRail(
    widthDp: Float,
    heightDp: Float,
): Boolean = widthDp >= 840f && heightDp >= 480f

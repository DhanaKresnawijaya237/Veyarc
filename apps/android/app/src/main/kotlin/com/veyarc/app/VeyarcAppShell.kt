package com.veyarc.app

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
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

internal val LocalMainPageActive = compositionLocalOf { true }

@Composable
internal fun VeyarcAppShell(
    selectedDestination: VeyarcDestination,
    onDestinationSelected: (VeyarcDestination) -> Unit,
    selectionBar: ManagerSelectionBarState? = null,
    showNavigation: Boolean = true,
    content: @Composable (VeyarcDestination) -> Unit,
) {
    val destinationStates = rememberSaveableStateHolder()

    @Composable
    fun DestinationContent() {
        AnimatedContent(selectedDestination, modifier = Modifier.fillMaxSize(), transitionSpec = {
            val direction = if (targetState.ordinal > initialState.ordinal) 1 else -1
            (slideInHorizontally(tween(190)) { it * direction } togetherWith slideOutHorizontally(tween(190)) { -it * direction }).using(SizeTransform(clip = true))
        }, label = "page navigation") { destination ->
            destinationStates.SaveableStateProvider(destination.name) {
                CompositionLocalProvider(LocalMainPageActive provides (destination == selectedDestination)) { content(destination) }
            }
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        if (!showNavigation) {
            DestinationContent()
        } else if (usesNavigationRail(maxWidth.value, maxHeight.value)) {
            Row(modifier = Modifier.fillMaxSize()) {
                if (selectionBar != null) {
                    ManagerSelectionRail(selectionBar)
                } else {
                    NavigationRail {
                        Spacer(Modifier.weight(1f))
                        VeyarcDestination.entries.forEach { destination ->
                            NavigationRailItem(
                                modifier = Modifier.width(80.dp),
                                selected = selectedDestination == destination,
                                onClick = { onDestinationSelected(destination) },
                                icon = { DestinationIcon(destination) },
                                label = { Text(destination.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            )
                            Spacer(Modifier.weight(1f))
                        }
                    }
                }
                Box(modifier = Modifier.weight(1f)) {
                    DestinationContent()
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
                    DestinationContent()
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
): Boolean = widthDp > heightDp || (widthDp >= 840f && heightDp >= 480f)

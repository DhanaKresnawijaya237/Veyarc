package com.veyarc.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.veyarc.app.ui.theme.VeyarcTheme
import com.veyarc.data.storage.WorkflowRepository
import com.veyarc.feature.editor.WorkflowDraft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppShellAccessibilityTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun compactNavigationRemainsReachableAtTwoHundredPercentFontScale() {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                VeyarcTheme(darkTheme = false) {
                    var selected by rememberSaveable { mutableStateOf(VeyarcDestination.HOME) }
                    VeyarcAppShell(
                        selectedDestination = selected,
                        onDestinationSelected = { selected = it },
                    ) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            Text("Selected ${selected.label}")
                        }
                    }
                }
            }
        }

        VeyarcDestination.entries.forEach { destination ->
            composeRule.onNodeWithContentDescription(destination.label, useUnmergedTree = true).assertExists().performClick()
            composeRule.onNodeWithText("Selected ${destination.label}").assertExists()
        }
    }

    @Test
    fun shellSurvivesThemeChangesWithoutLosingDestination() {
        composeRule.setContent {
            var darkTheme by rememberSaveable { mutableStateOf(false) }
            var selected by rememberSaveable { mutableStateOf(VeyarcDestination.HOME) }
            VeyarcTheme(darkTheme = darkTheme) {
                VeyarcAppShell(
                    selectedDestination = selected,
                    onDestinationSelected = { selected = it },
                ) {
                    Column {
                        Text("Selected ${selected.label}")
                        androidx.compose.material3.Button(onClick = { darkTheme = !darkTheme }) {
                            Text("Toggle theme")
                        }
                    }
                }
            }
        }

        composeRule.onNodeWithContentDescription("Settings", useUnmergedTree = true).performClick()
        composeRule.onNodeWithText("Toggle theme").performClick()
        composeRule.onNodeWithText("Selected Settings").assertExists()
    }

    @Test
    fun folderDragCommitsTheHoveredOrder() {
        val initial =
            listOf(
                ManagerFolderItem("all", "All"),
                ManagerFolderItem("favorites", "Favorites"),
                ManagerFolderItem("unfiled", "Unfiled"),
            )
        var committedOrder: List<String>? = null

        composeRule.setContent {
            var folders by remember { mutableStateOf(initial) }
            VeyarcTheme(darkTheme = false) {
                ReorderableFolderRow(
                    folders = folders,
                    selectedKey = "all",
                    onSelect = {},
                    onManage = {},
                    onOrderChanged = { order ->
                        committedOrder = order
                        folders = order.map { key -> initial.first { it.key == key } }
                    },
                    onCreate = {},
                )
            }
        }

        composeRule.onNodeWithText("All").performTouchInput {
            down(center)
            advanceEventTime(700)
            moveTo(center + Offset(320f, 0f))
            up()
        }

        composeRule.waitUntil(timeoutMillis = 5_000) { committedOrder != null }
        assertEquals(listOf("favorites", "unfiled", "all"), committedOrder)
        composeRule.waitForIdle()
        composeRule.onNodeWithText("All").assertHeightIsEqualTo(40.dp)
        val allLeft = composeRule.onNodeWithText("All").fetchSemanticsNode().boundsInRoot.left
        val unfiledLeft = composeRule.onNodeWithText("Unfiled").fetchSemanticsNode().boundsInRoot.left
        assertTrue("Dropped folder should remain in its new position", allLeft > unfiledLeft)
    }

    @Test
    fun selectedFolderMovesTowardThePickerCenter() {
        val folders = (1..10).map { ManagerFolderItem("folder-$it", "Folder $it") }

        composeRule.setContent {
            var selected by remember { mutableStateOf(folders.first().key) }
            VeyarcTheme(darkTheme = false) {
                ReorderableFolderRow(
                    folders = folders,
                    selectedKey = selected,
                    onSelect = { selected = it },
                    onManage = {},
                    onOrderChanged = {},
                    onCreate = {},
                )
            }
        }

        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText("Folder 6"))
        composeRule.onNodeWithText("Folder 6").performClick()
        composeRule.waitForIdle()

        val rootCenter = composeRule.onRoot().fetchSemanticsNode().boundsInRoot.center.x
        val folderCenter = composeRule.onNodeWithText("Folder 6").fetchSemanticsNode().boundsInRoot.center.x
        assertTrue("Selected folder should be near the picker center", kotlin.math.abs(rootCenter - folderCenter) < 80f)
    }
}

@RunWith(AndroidJUnit4::class)
class MainActivityNavigationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun allFiveDestinationsAreReachable() {
        val destinations =
            listOf(
                "Home" to "Home",
                "Workflows" to "Workflow Manager",
                "Editor" to "Workflow Editor",
                "Runs" to "Runs Manager",
                "Settings" to "Connections and permissions",
            )

        destinations.forEach { (label, heading) ->
            composeRule.onNodeWithContentDescription(label, useUnmergedTree = true).assertExists()
            composeRule.onNodeWithContentDescription(label, useUnmergedTree = true).performClick()
            composeRule.waitForText(heading)
        }
    }

    @Test
    fun destinationSurvivesRecreationAndBackReturnsHome() {
        composeRule.onNodeWithContentDescription("Runs", useUnmergedTree = true).performClick()
        composeRule.waitForText("Runs Manager")

        composeRule.activityRule.scenario.recreate()
        composeRule.waitForText("Runs Manager")

        composeRule.runOnUiThread {
            composeRule.activity.onBackPressedDispatcher.onBackPressed()
        }
        composeRule.waitForText("Home")
    }

    @Test
    fun workflowLongPressEntersSelectionMode() {
        val repository = WorkflowRepository(composeRule.activity)
        val draft = WorkflowDraft.newDefault().copy(name = "Selection mode test ${System.nanoTime()}")
        val bundle = draft.buildBundle().bundle ?: error("Expected valid workflow")
        repository.saveRevision(bundle)

        try {
            composeRule.onNodeWithContentDescription("Workflows", useUnmergedTree = true).performClick()
            composeRule.waitForText(draft.name)
            composeRule.onNode(hasText(draft.name) and hasClickAction()).performTouchInput { longClick() }
            composeRule.waitForText("1 selected")
            composeRule.onNodeWithContentDescription("Leave selection mode", useUnmergedTree = true).assertExists()
        } finally {
            runCatching { repository.deleteWorkflow(draft.workflowId) }
            repository.close()
        }
    }

    @Test
    fun deletingFolderExplainsThatWorkflowsAreRetained() {
        val repository = WorkflowRepository(composeRule.activity)
        val folder = repository.createWorkflowFolder("UI confirmation test")

        try {
            composeRule.onNodeWithContentDescription("Workflows", useUnmergedTree = true).performClick()
            composeRule.waitForText(folder.name)
            composeRule.onNode(hasText(folder.name) and hasClickAction()).performTouchInput { doubleClick() }
            composeRule.onNodeWithContentDescription("Delete ${folder.name}").performClick()

            composeRule.onNodeWithText("Delete ${folder.name}?").assertExists()
            composeRule.onNodeWithText("The folder will be removed. Its workflows will remain in Unfiled.").assertExists()
            composeRule.onNodeWithText("Cancel").performClick()
        } finally {
            runCatching { repository.deleteWorkflowFolder(folder.folderId) }
            repository.close()
        }
    }

    private fun androidx.compose.ui.test.junit4.AndroidComposeTestRule<*, *>.waitForText(text: String) {
        waitUntil(timeoutMillis = 10_000) {
            onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()
        }
    }
}

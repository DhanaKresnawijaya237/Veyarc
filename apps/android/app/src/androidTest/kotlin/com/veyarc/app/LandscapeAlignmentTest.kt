package com.veyarc.app

import android.app.UiAutomation
import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.veyarc.app.ui.theme.VeyarcTheme
import com.veyarc.data.storage.WorkflowRepository
import com.veyarc.feature.editor.WorkflowDraft
import com.veyarc.feature.editor.WorkflowEditorScreen
import com.veyarc.feature.editor.WorkflowEditorSession
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LandscapeAlignmentTest {
    @get:Rule val compose = createComposeRule()

    @Before fun landscape() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.setRotation(UiAutomation.ROTATION_FREEZE_90)
        compose.waitUntil(10_000) { instrumentation.targetContext.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
    }

    @After fun portrait() {
        InstrumentationRegistry.getInstrumentation().uiAutomation.setRotation(UiAutomation.ROTATION_FREEZE_0)
    }

    @Test fun selectionActionsKeepThePageIconPositions() {
        var selection by mutableStateOf<ManagerSelectionBarState?>(null)
        var fontScale by mutableStateOf(1f)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                VeyarcTheme { VeyarcAppShell(VeyarcDestination.WORKFLOWS, {}, selectionBar = selection) {} }
            }
        }
        listOf(1f, 1.6f).forEach { scale ->
            compose.runOnIdle {
                selection = null
                fontScale = scale
            }
            val pageIcons = VeyarcDestination.entries.map { compose.onNodeWithContentDescription(it.label, useUnmergedTree = true).assertIsDisplayed().fetchSemanticsNode().boundsInRoot.center }
            listOf(0, 1).forEach { count ->
                compose.runOnIdle { selection = ManagerSelectionBarState(count, if (count > 0) ({}) else null, null, {}, {}, null) }
                listOf("Copy", "Rename", "Move", "Delete", "Details").forEachIndexed { index, action ->
                    val center = compose.onNodeWithContentDescription(action, useUnmergedTree = true).assertIsDisplayed().fetchSemanticsNode().boundsInRoot.center
                    assertEquals("$action horizontal position at font scale $scale", pageIcons[index].x, center.x, 1f)
                    assertEquals("$action vertical position at font scale $scale", pageIcons[index].y, center.y, 1f)
                }
            }
        }
    }

    @Test fun emptyAndLoadedEditorTitlesAlignWithTheOtherPages() {
        val repository = WorkflowRepository(InstrumentationRegistry.getInstrumentation().targetContext)
        val session = WorkflowEditorSession()
        val pages = MainPageState()
        var destination by mutableStateOf(VeyarcDestination.HOME)
        var fontScale by mutableStateOf(1f)
        try {
            compose.setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                    VeyarcTheme(darkTheme = true) {
                        Surface(color = MaterialTheme.colorScheme.background) {
                            VeyarcAppShell(destination, { destination = it }) { page ->
                                Box(Modifier.fillMaxSize().testTag("page-content")) {
                                    when (page) {
                                        VeyarcDestination.HOME -> HomeDashboardScreen(repository, 0, {}, {}, pages.home)
                                        VeyarcDestination.WORKFLOWS -> WorkflowManagerScreen(repository, 0, {}, {}, pageState = pages.workflows)
                                        VeyarcDestination.EDITOR -> WorkflowEditorScreen(repository, session, {}, {})
                                        VeyarcDestination.RUNS -> RunManagerScreen(repository, 0, {}, pageState = pages.runs)
                                        VeyarcDestination.SETTINGS -> SettingsScreen(repository = repository, refreshGeneration = 0, themePreference = ThemePreference.DARK, onThemePreferenceChange = {}, storageState = pages.settings)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            val titles = mapOf(VeyarcDestination.HOME to "Home", VeyarcDestination.WORKFLOWS to "Workflow Manager", VeyarcDestination.EDITOR to "Workflow Editor", VeyarcDestination.RUNS to "Runs Manager", VeyarcDestination.SETTINGS to "Settings")
            listOf(1f, 1.6f).forEach { scale ->
                compose.runOnIdle {
                    destination = VeyarcDestination.HOME
                    fontScale = scale
                }
                val reference = titlePosition(compose.onNode(hasText("Home") and hasAnyAncestor(hasTestTag("page-content")), useUnmergedTree = true))
                titles.forEach { (page, title) ->
                    compose.runOnIdle { destination = page }
                    val node = if (page == VeyarcDestination.EDITOR && session.draft != null) compose.onNodeWithTag("workflow-title") else compose.onNode(hasText(title) and hasAnyAncestor(hasTestTag("page-content")), useUnmergedTree = true)
                    val position = titlePosition(node)
                    assertEquals("$title left edge at font scale $scale", reference.first, position.first, 1f)
                    assertEquals("$title baseline at font scale $scale", reference.second, position.second, 1f)
                }
                compose.runOnIdle {
                    destination = VeyarcDestination.EDITOR
                    session.open(WorkflowDraft.newDefault().copy(name = "Aligned workflow").buildBundle().bundle!!)
                }
                val loaded = titlePosition(compose.onNodeWithTag("workflow-title"))
                assertEquals(reference.first, loaded.first, 1f)
                assertEquals(reference.second, loaded.second, 1f)
            }
        } finally {
            repository.close()
        }
    }

    private fun titlePosition(node: SemanticsNodeInteraction): Pair<Float, Float> {
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val bounds = node.fetchSemanticsNode().boundsInRoot
        return bounds.left to bounds.top + layouts.single().firstBaseline
    }
}

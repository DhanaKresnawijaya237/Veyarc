package com.veyarc.feature.editor

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.LocalPinnableContainer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.veyarc.data.storage.WorkflowAssetDescriptor
import com.veyarc.engine.ConditionObservation
import com.veyarc.model.AssetId
import com.veyarc.model.Condition
import com.veyarc.model.ImageAbsentCondition
import com.veyarc.model.ImageMatchMode
import com.veyarc.model.ImagePresentCondition
import com.veyarc.model.PixelColorCondition
import com.veyarc.model.backgroundAssetId
import com.veyarc.model.bundle.BundleAsset
import com.veyarc.model.capturedBackgroundName
import com.veyarc.model.capturedImageName
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.Locale

private data class SavedConditionCheck(val sourceId: String, val observation: ConditionObservation? = null, val error: String? = null)

@Composable
internal fun ImageCheckTestPanel(condition: Condition, assets: List<WorkflowAssetDescriptor>, names: Map<String, String>, loadAsset: suspend (AssetId) -> BundleAsset?, loadPreview: suspend (AssetId, Int) -> TemplatePreviewData?, width: Int, height: Int) {
    if (condition.capturedImageName() != null || condition.capturedBackgroundName() != null) {
        Text("Captured images are checked during the run.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    val findBest = LocalFindImagePreview.current
    var showImages by rememberSaveable { mutableStateOf(false) }
    var expanded by rememberSaveable { mutableStateOf(true) }
    var sourceId by rememberSaveable { mutableStateOf<String?>(null) }
    var testedIds by remember(condition, assets, width, height) { mutableStateOf<List<String>>(emptyList()) }
    val scope = rememberCoroutineScope()
    var checkJob by remember { mutableStateOf<Job?>(null) }
    DisposableEffect(condition, assets, width, height) {
        onDispose { checkJob?.cancel() }
    }
    val container = LocalPinnableContainer.current
    val hasChecks = testedIds.isNotEmpty()
    DisposableEffect(container, hasChecks) {
        // Keep results and in-flight checks alive when their lazy-list item scrolls off screen.
        val handle = if (hasChecks) container?.pin() else null
        onDispose { handle?.release() }
    }
    var busy by remember(condition, assets, width, height) { mutableStateOf(false) }
    // Edits discard old checks instead of rerunning them.
    var checks by remember(condition, assets, width, height) { mutableStateOf<List<SavedConditionCheck>>(emptyList()) }
    val colorSearch = condition is PixelColorCondition
    val imageId = sourceId ?: condition.backgroundAssetId()?.value
    fun runCheck(id: String) {
        if (busy) return
        sourceId = id
        checks = checks.filterNot { it.sourceId == id }
        testedIds = listOf(id) + testedIds.filterNot { it == id }
        expanded = true
        busy = true
        // Tests start only from a user action, never from restored UI state.
        checkJob = scope.launch {
            try {
                val check = try {
                    val image = requireNotNull(loadAsset(AssetId(id))) { "The test image is unavailable." }
                    val observation = if (condition is PixelColorCondition) {
                        testColorCondition(condition, image, width, height)
                    } else {
                        val targetId = when (condition) {
                            is ImagePresentCondition -> condition.assetId
                            is ImageAbsentCondition -> condition.assetId
                            else -> error("Select an image or colour condition.")
                        }
                        val target = requireNotNull(loadAsset(requireNotNull(targetId))) { "The target image is unavailable." }
                        testImageCondition(condition, target, image, width, height, findBest)
                    }
                    SavedConditionCheck(id, observation)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    SavedConditionCheck(id, error = failure.message ?: "Could not check this image.")
                }
                checks = checks + check
            } finally {
                busy = false
            }
        }
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { showImages = true }, enabled = !busy) { Icon(Icons.Outlined.Image, "Choose test background") }
        TextButton(onClick = { if (imageId == null) showImages = true else runCheck(imageId) }, enabled = !busy, modifier = Modifier.weight(1f).testTag("test-image-check")) {
            if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text(if (colorSearch) "Test colour search" else "Test image check")
        }
        IconButton(onClick = { expanded = !expanded }, enabled = testedIds.isNotEmpty(), modifier = Modifier.testTag("toggle-image-checks").semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" }) {
            Icon(if (expanded) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown, if (expanded) "Hide test checks" else "Show test checks")
        }
    }
    AnimatedVisibility(expanded && testedIds.isNotEmpty(), enter = expandVertically(tween(160)) + fadeIn(tween(110)), exit = shrinkVertically(tween(140)) + fadeOut(tween(90))) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (condition is PixelColorCondition) Text(if (condition.region == null) "Checks the selected pixel in this background." else "Searches the selected area in this background.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            testedIds.forEach { id ->
                val check = checks.firstOrNull { it.sourceId == id }
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth().testTag(if (check == null) "image-check-pending" else "image-check-result")) {
                    Column(Modifier.padding(14.dp).testTag("image-check-source-$id"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (check == null) {
                            Text("Checking image…", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (!colorSearch) Text("Score …", style = MaterialTheme.typography.bodyMedium)
                        }
                        check?.observation?.let { observation ->
                            Text(imageCheckDescription(condition, observation), color = if (observation.result) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error, style = MaterialTheme.typography.titleSmall)
                            if (condition is PixelColorCondition) {
                                observation.colorMatch?.let { match ->
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        ColorResultSample("Target", match.targetArgb, Modifier.weight(1f))
                                        ColorResultSample("Closest", match.closestArgb, Modifier.weight(1f))
                                    }
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text("Difference ${match.difference}", style = MaterialTheme.typography.bodyMedium)
                                        Text("Tolerance ±${match.tolerance}", style = MaterialTheme.typography.bodyMedium)
                                    }
                                }
                            } else {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("Score ${observation.matchScore?.let(::imageCheckPercent) ?: "unavailable"}", style = MaterialTheme.typography.bodyMedium)
                                    Text("Threshold ${observation.threshold?.let(::imageCheckPercent) ?: "—"}", style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                        check?.observation?.matchedArea?.let { area ->
                            ImageConditionPreview(area, width, height, assetId = null, backgroundAssetId = AssetId(id), loadPreview = loadPreview, onRegionChange = null, resultPreview = true)
                        }
                        check?.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                        Text(names[id] ?: "Test image", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
    if (showImages) {
        TemplatePickerSheet(visible = true, title = "Test background", assets = assets, assetNames = names, displayWidthPx = width, displayHeightPx = height, fullScreenOnly = true, selectedAssetId = imageId, allowNone = false, onDismiss = { showImages = false }, onSelect = { selected ->
            selected?.value?.let(::runCheck)
            showImages = false
        }, loadPreview = loadPreview)
    }
}

@Composable
private fun ColorResultSample(label: String, argb: Long, modifier: Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(24.dp).background(Color(argb.toInt()), CircleShape).border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape))
        Column {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(String.format(Locale.ROOT, "#%08X", argb), style = MaterialTheme.typography.bodySmall)
        }
    }
}

internal fun imageCheckDescription(condition: Condition, observation: ConditionObservation): String = when (condition) {
    is PixelColorCondition -> if (observation.result) "Colour found" else "Colour not found"

    is ImageAbsentCondition -> if (observation.result) "Image is absent" else "Image appears in the area"

    is ImagePresentCondition -> if (condition.matchMode == ImageMatchMode.SCREEN_STATE) {
        if (observation.result) "Screen matches" else "Screen does not match"
    } else {
        if (observation.result) "Image appears in the area" else "Image is absent"
    }

    else -> error("Select an image or colour condition.")
}

private fun imageCheckPercent(value: Double): String = String.format(Locale.getDefault(), "%.1f%%", value * 100)

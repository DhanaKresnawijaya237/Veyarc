package com.veyarc.feature.editor

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import kotlinx.coroutines.CancellationException
import java.util.Locale

private data class SavedConditionCheck(val sourceId: String, val observation: ConditionObservation? = null, val error: String? = null)

@Composable
internal fun ImageCheckTestPanel(condition: Condition, assets: List<WorkflowAssetDescriptor>, names: Map<String, String>, loadAsset: suspend (AssetId) -> BundleAsset?, loadPreview: suspend (AssetId, Int) -> TemplatePreviewData?, width: Int, height: Int) {
    var showImages by rememberSaveable { mutableStateOf(false) }
    var expanded by rememberSaveable { mutableStateOf(true) }
    var sourceId by rememberSaveable { mutableStateOf<String?>(null) }
    var testedIds by rememberSaveable(condition, assets, width, height) { mutableStateOf<List<String>>(emptyList()) }
    var request by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    // Edits discard old checks instead of rerunning them.
    var checks by remember(condition, assets, width, height) { mutableStateOf<List<SavedConditionCheck>>(emptyList()) }
    val colorSearch = condition is PixelColorCondition
    val imageId = sourceId ?: condition.backgroundAssetId()?.value
    fun runCheck(id: String) {
        sourceId = id
        checks = checks.filterNot { it.sourceId == id }
        testedIds = listOf(id) + testedIds.filterNot { it == id }
        expanded = true
        request++
    }
    LaunchedEffect(condition, assets, width, height, testedIds, request) {
        if (testedIds.isEmpty()) return@LaunchedEffect
        busy = true
        try {
            for (id in testedIds) {
                if (checks.any { it.sourceId == id }) continue
                val check = try {
                    val image = requireNotNull(loadAsset(AssetId(id))) { "The test image is unavailable." }
                    val observation = if (condition is PixelColorCondition) {
                        testColorCondition(condition, image)
                    } else {
                        val targetId = when (condition) {
                            is ImagePresentCondition -> condition.assetId
                            is ImageAbsentCondition -> condition.assetId
                            else -> error("Select an image or colour condition.")
                        }
                        val target = requireNotNull(loadAsset(targetId)) { "The target image is unavailable." }
                        testImageCondition(condition, target, image, width, height)
                    }
                    SavedConditionCheck(id, observation)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    SavedConditionCheck(id, error = failure.message ?: "Could not check this image.")
                }
                checks = checks + check
            }
        } finally {
            busy = false
        }
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { showImages = true }, enabled = !busy) { Icon(Icons.Outlined.Image, if (colorSearch) "Choose colour search image" else "Choose test background") }
        TextButton(onClick = { if (imageId == null) showImages = true else runCheck(imageId) }, enabled = !busy, modifier = Modifier.weight(1f).testTag("test-image-check")) {
            if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text(if (colorSearch) "Test colour search" else "Test image check")
        }
        IconButton(onClick = { expanded = !expanded }, enabled = testedIds.isNotEmpty(), modifier = Modifier.testTag("toggle-image-checks").semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" }) {
            Icon(if (expanded) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown, if (expanded) "Hide test checks" else "Show test checks")
        }
    }
    AnimatedVisibility(expanded && testedIds.isNotEmpty(), enter = expandVertically(tween(160)) + fadeIn(tween(110)), exit = shrinkVertically(tween(140)) + fadeOut(tween(90))) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (colorSearch) Text("Searches the whole selected image for this colour.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                                Text("Tolerance ±${condition.tolerance}", style = MaterialTheme.typography.bodyMedium)
                            } else {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("Score ${observation.matchScore?.let(::imageCheckPercent) ?: "unavailable"}", style = MaterialTheme.typography.bodyMedium)
                                    Text("Threshold ${observation.threshold?.let(::imageCheckPercent) ?: "—"}", style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                        check?.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                        Text(names[id] ?: "Test image", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
    if (showImages) {
        TemplatePickerSheet(visible = true, title = if (colorSearch) "Colour search image" else "Test background", assets = assets, assetNames = names, displayWidthPx = width, displayHeightPx = height, fullScreenOnly = !colorSearch, allowAnySize = colorSearch, selectedAssetId = imageId, allowNone = false, onDismiss = { showImages = false }, onSelect = { selected ->
            selected?.value?.let(::runCheck)
            showImages = false
        }, loadPreview = loadPreview)
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

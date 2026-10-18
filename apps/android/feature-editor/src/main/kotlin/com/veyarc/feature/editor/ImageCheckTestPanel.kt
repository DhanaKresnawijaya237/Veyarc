package com.veyarc.feature.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Image
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
import androidx.compose.ui.unit.dp
import com.veyarc.data.storage.WorkflowAssetDescriptor
import com.veyarc.engine.ConditionObservation
import com.veyarc.model.AssetId
import com.veyarc.model.Condition
import com.veyarc.model.ImageAbsentCondition
import com.veyarc.model.ImagePresentCondition
import com.veyarc.model.backgroundAssetId
import com.veyarc.model.bundle.BundleAsset
import kotlinx.coroutines.CancellationException
import java.util.Locale

@Composable
internal fun ImageCheckTestPanel(condition: Condition, assets: List<WorkflowAssetDescriptor>, names: Map<String, String>, loadAsset: suspend (AssetId) -> BundleAsset?, loadPreview: suspend (AssetId, Int) -> TemplatePreviewData?, width: Int, height: Int) {
    var showImages by rememberSaveable { mutableStateOf(false) }
    var sourceId by rememberSaveable { mutableStateOf<String?>(null) }
    var request by remember(condition) { mutableIntStateOf(0) }
    var busy by remember(condition, sourceId) { mutableStateOf(false) }
    var result by remember(condition, sourceId) { mutableStateOf<ConditionObservation?>(null) }
    var error by remember(condition, sourceId) { mutableStateOf<String?>(null) }
    val imageId = sourceId ?: condition.backgroundAssetId()?.value
    LaunchedEffect(condition, imageId, request) {
        if (request == 0 || imageId == null) return@LaunchedEffect
        busy = true
        error = null
        result = null
        try {
            val targetId = when (condition) {
                is ImagePresentCondition -> condition.assetId
                is ImageAbsentCondition -> condition.assetId
                else -> throw IllegalArgumentException("Select an image condition.")
            }
            val target = requireNotNull(loadAsset(targetId)) { "The target image is unavailable." }
            val background = requireNotNull(loadAsset(AssetId(imageId))) { "The test image is unavailable." }
            result = testImageCondition(condition, target, background, width, height)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = failure.message ?: "Could not compare these images."
        } finally {
            busy = false
        }
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { if (imageId == null) showImages = true else request++ }, enabled = !busy, modifier = Modifier.weight(1f).testTag("test-image-check")) {
            if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Test image check")
        }
        IconButton(onClick = { showImages = true }, enabled = !busy) { Icon(Icons.Outlined.Image, "Choose test background") }
    }
    result?.let { observation ->
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth().testTag("image-check-result")) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(if (observation.result) "Condition met" else "Condition not met", color = if (observation.result) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error, style = MaterialTheme.typography.titleSmall)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Score ${observation.matchScore?.let(::imageCheckPercent) ?: "unavailable"}", style = MaterialTheme.typography.bodyMedium)
                    Text("Threshold ${observation.threshold?.let(::imageCheckPercent) ?: "—"}", style = MaterialTheme.typography.bodyMedium)
                }
                Text(names[imageId] ?: "Saved background", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    if (showImages) {
        TemplatePickerSheet(visible = true, title = "Test background", assets = assets, assetNames = names, displayWidthPx = width, displayHeightPx = height, fullScreenOnly = true, selectedAssetId = imageId, allowNone = false, onDismiss = { showImages = false }, onSelect = { selected ->
            sourceId = selected?.value
            request++
            showImages = false
        }, loadPreview = loadPreview)
    }
}

private fun imageCheckPercent(value: Double): String = String.format(Locale.getDefault(), "%.1f%%", value * 100)

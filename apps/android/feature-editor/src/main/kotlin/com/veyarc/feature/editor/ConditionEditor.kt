@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.veyarc.feature.editor

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.CenterFocusStrong
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.StayCurrentLandscape
import androidx.compose.material.icons.outlined.StayCurrentPortrait
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.veyarc.data.storage.WorkflowAssetDescriptor
import com.veyarc.model.AndCondition
import com.veyarc.model.AssetId
import com.veyarc.model.Condition
import com.veyarc.model.DisplayOrientation
import com.veyarc.model.ForegroundPackageCondition
import com.veyarc.model.ImageAbsentCondition
import com.veyarc.model.ImageMatchMode
import com.veyarc.model.ImagePresentCondition
import com.veyarc.model.NormalizedCoordinate
import com.veyarc.model.NormalizedRect
import com.veyarc.model.NotCondition
import com.veyarc.model.OrCondition
import com.veyarc.model.OrientationCondition
import com.veyarc.model.PackageName
import com.veyarc.model.PixelColorCondition
import com.veyarc.model.StepResultCondition
import com.veyarc.model.VariableComparisonCondition
import com.veyarc.model.WorkflowIds
import com.veyarc.model.WorkflowJson
import com.veyarc.model.backgroundAssetId
import com.veyarc.model.bundle.BundleAsset
import kotlin.math.roundToInt

private const val MAX_COMPOSED_CONDITIONS = 8
private const val MISSING_TEMPLATE_ASSET_ID = "template-missing"
private val ConditionEditorSaver = Saver<Condition, String>(
    save = { WorkflowJson.encodeCanonical(Condition.serializer(), it) },
    restore = { WorkflowJson.decodeStrict(Condition.serializer(), it) },
)

internal fun Condition.usesCompactEditorSheet(): Boolean = this is ForegroundPackageCondition ||
    this is OrientationCondition ||
    this is StepResultCondition ||
    this is VariableComparisonCondition

@Composable
internal fun ConditionEditorDialog(
    initialCondition: Condition,
    assets: List<WorkflowAssetDescriptor>,
    assetNames: Map<String, String>,
    defaultPackage: String,
    allowedPackages: List<String>,
    onAddAllowedPackage: (String) -> Unit,
    onAddTemplate: () -> Unit,
    loadAsset: suspend (AssetId) -> BundleAsset?,
    loadPreview: suspend (AssetId, Int) -> TemplatePreviewData?,
    previewWidthPx: Int,
    previewHeightPx: Int,
    onDismiss: () -> Unit,
    onApply: (Condition) -> Unit,
) {
    var condition by rememberSaveable(initialCondition, stateSaver = ConditionEditorSaver) { mutableStateOf(initialCondition) }
    val compact = condition.usesCompactEditorSheet()
    EditorSheetDialog(
        compact = compact,
        onDismiss = onDismiss,
        compactMaxHeightFraction = 0.72f,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(start = 16.dp, top = if (compact) 20.dp else 0.dp, end = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Edit condition",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Button(onClick = { onApply(condition) }) { Text("Apply") }
                }
                Text(
                    "Conditions are checked only at their step's polling interval. Screen checks use temporary frames; no video is saved.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ConditionNodeEditor(
                    condition = condition,
                    assets = assets,
                    assetNames = assetNames,
                    defaultPackage = defaultPackage,
                    allowedPackages = allowedPackages,
                    onAddAllowedPackage = onAddAllowedPackage,
                    onAddTemplate = onAddTemplate,
                    loadAsset = loadAsset,
                    loadPreview = loadPreview,
                    previewWidthPx = previewWidthPx,
                    previewHeightPx = previewHeightPx,
                    onChange = { condition = it },
                )
            }
        }
    }
}

@Composable
private fun ConditionNodeEditor(
    condition: Condition,
    assets: List<WorkflowAssetDescriptor>,
    assetNames: Map<String, String>,
    defaultPackage: String,
    allowedPackages: List<String>,
    onAddAllowedPackage: (String) -> Unit,
    onAddTemplate: () -> Unit,
    loadAsset: suspend (AssetId) -> BundleAsset?,
    loadPreview: suspend (AssetId, Int) -> TemplatePreviewData?,
    previewWidthPx: Int,
    previewHeightPx: Int,
    onChange: (Condition) -> Unit,
    showTypePicker: Boolean = true,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (showTypePicker) {
            ConditionKindPicker(condition, defaultPackage, onChange, Modifier.fillMaxWidth(), showDividers = true)
        }
        when (condition) {
            is PixelColorCondition -> PixelConditionFields(condition, assets, assetNames, onAddTemplate, loadAsset, loadPreview, previewWidthPx, previewHeightPx, onChange)

            is ImagePresentCondition -> ImageConditionFields(
                condition = condition,
                assets = assets,
                assetNames = assetNames,
                onAddTemplate = onAddTemplate,
                loadAsset = loadAsset,
                loadPreview = loadPreview,
                previewWidthPx = previewWidthPx,
                previewHeightPx = previewHeightPx,
                onChange = { onChange(it) },
            )

            is ImageAbsentCondition -> ImageConditionFields(
                condition = condition,
                assets = assets,
                assetNames = assetNames,
                onAddTemplate = onAddTemplate,
                loadAsset = loadAsset,
                loadPreview = loadPreview,
                previewWidthPx = previewWidthPx,
                previewHeightPx = previewHeightPx,
                onChange = { onChange(it) },
            )

            is ForegroundPackageCondition -> PackageConditionFields(condition, allowedPackages, onAddAllowedPackage, onChange)

            is OrientationCondition -> OrientationConditionFields(condition, onChange)

            is AndCondition -> ComposedConditionFields(
                label = "Every condition must be true",
                conditions = condition.conditions,
                assets = assets,
                assetNames = assetNames,
                defaultPackage = defaultPackage,
                allowedPackages = allowedPackages,
                onAddAllowedPackage = onAddAllowedPackage,
                onAddTemplate = onAddTemplate,
                loadAsset = loadAsset,
                loadPreview = loadPreview,
                previewWidthPx = previewWidthPx,
                previewHeightPx = previewHeightPx,
                onChange = { children -> onChange(condition.copy(conditions = children)) },
            )

            is OrCondition -> ComposedConditionFields(
                label = "At least one condition must be true",
                conditions = condition.conditions,
                assets = assets,
                assetNames = assetNames,
                defaultPackage = defaultPackage,
                allowedPackages = allowedPackages,
                onAddAllowedPackage = onAddAllowedPackage,
                onAddTemplate = onAddTemplate,
                loadAsset = loadAsset,
                loadPreview = loadPreview,
                previewWidthPx = previewWidthPx,
                previewHeightPx = previewHeightPx,
                onChange = { children -> onChange(condition.copy(conditions = children)) },
            )

            is NotCondition -> {
                Text("Invert the nested condition.", style = MaterialTheme.typography.bodySmall)
                NestedConditionEditor(
                    number = 1,
                    condition = condition.condition,
                    assets = assets,
                    assetNames = assetNames,
                    defaultPackage = defaultPackage,
                    allowedPackages = allowedPackages,
                    onAddAllowedPackage = onAddAllowedPackage,
                    onAddTemplate = onAddTemplate,
                    loadAsset = loadAsset,
                    loadPreview = loadPreview,
                    previewWidthPx = previewWidthPx,
                    previewHeightPx = previewHeightPx,
                    onChange = { onChange(condition.copy(condition = it)) },
                )
            }

            else -> Text(
                "This condition type is preserved but does not have an editor yet.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ConditionKindPicker(
    condition: Condition,
    defaultPackage: String,
    onChange: (Condition) -> Unit,
    modifier: Modifier = Modifier,
    showDividers: Boolean = false,
) {
    var expanded by remember(condition::class) { mutableStateOf(false) }
    if (showDividers) {
        Row(
            modifier.heightIn(min = 48.dp).clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
                onClickLabel = "Change condition type",
            ) { expanded = true }.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            HorizontalDivider(Modifier.weight(1f))
            Text(condition.kind().label, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
            HorizontalDivider(Modifier.weight(1f))
        }
    } else {
        Surface(
            onClick = { expanded = true },
            modifier = modifier,
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ) {
            Text(condition.kind().label, Modifier.padding(horizontal = 14.dp, vertical = 12.dp), style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
    if (expanded) {
        VeyarcChoicePicker(
            title = "Condition type",
            options = EditableConditionKind.entries.map { VeyarcChoice(it.name, it.label) },
            selectedId = condition.kind().name,
            onDismiss = { expanded = false },
            onSelect = { choice ->
                expanded = false
                onChange(EditableConditionKind.entries.first { it.name == choice.id }.defaultCondition(defaultPackage))
            },
        )
    }
}

@Composable
private fun PixelConditionFields(
    condition: PixelColorCondition,
    assets: List<WorkflowAssetDescriptor>,
    assetNames: Map<String, String>,
    onAddTemplate: () -> Unit,
    loadAsset: suspend (AssetId) -> BundleAsset?,
    loadPreview: suspend (AssetId, Int) -> TemplatePreviewData?,
    previewWidthPx: Int,
    previewHeightPx: Int,
    onChange: (Condition) -> Unit,
) {
    var xText by rememberNumericEditorText(condition.point.x.toEditorText())
    var yText by rememberNumericEditorText(condition.point.y.toEditorText())
    var colorText by rememberSaveable(condition.expectedArgb) { mutableStateOf(condition.expectedArgb.toArgbText()) }
    var toleranceText by rememberNumericEditorText(condition.tolerance.toString())
    var showRgb by rememberSaveable { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("Point", "Area").forEachIndexed { index, label ->
            val selected = (condition.region != null) == (index == 1)
            Surface(onClick = { onChange(condition.copy(region = if (index == 0) null else condition.region ?: NormalizedRect(0.25, 0.25, 0.5, 0.5))) }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(16.dp), color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh) {
                Text(label, Modifier.padding(12.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center, color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    ColorSampleTools(assets, assetNames, loadAsset, loadPreview, previewWidthPx, previewHeightPx) { point, argb -> onChange(condition.copy(point = point ?: condition.point, expectedArgb = argb)) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .background(Color(condition.expectedArgb.toInt()), MaterialTheme.shapes.medium)
                .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.medium),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Colour preview", style = MaterialTheme.typography.labelLarge)
            Text(
                condition.expectedArgb.toArgbText(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "R ${condition.red} · G ${condition.green} · B ${condition.blue}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Surface(
            Modifier.clip(RoundedCornerShape(14.dp)).toggleable(showRgb, role = Role.Switch, onValueChange = { showRgb = it }),
            color = if (showRgb) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = if (showRgb) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        ) {
            Text("RGB & hex", Modifier.padding(horizontal = 12.dp, vertical = 14.dp), style = MaterialTheme.typography.labelMedium)
        }
    }
    ColorPalette(condition.expectedArgb) { onChange(condition.copy(expectedArgb = it)) }
    if (showRgb) {
        RgbSlider("Red", condition.red, Color(0xFFE53935)) { red -> onChange(condition.withRgb(red = red)) }
        RgbSlider("Green", condition.green, Color(0xFF43A047)) { green -> onChange(condition.withRgb(green = green)) }
        RgbSlider("Blue", condition.blue, Color(0xFF1E88E5)) { blue -> onChange(condition.withRgb(blue = blue)) }
        OutlinedTextField(colorText, { next ->
            colorText = next
            next.parseArgb()?.let { onChange(condition.copy(expectedArgb = it)) }
        }, Modifier.fillMaxWidth(), label = { Text("Hex color · #AARRGGBB") }, singleLine = true)
    }
    val searchRegion = condition.region
    if (searchRegion == null) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ConditionNumberField("X", xText, { next ->
                xText = next
                val x = next.toDoubleOrNull()?.takeIf { it in 0.0..1.0 } ?: return@ConditionNumberField
                val y = yText.toDoubleOrNull()?.takeIf { it in 0.0..1.0 } ?: return@ConditionNumberField
                onChange(condition.copy(point = NormalizedCoordinate(x, y)))
            }, Modifier.weight(1f))
            ConditionNumberField("Y", yText, { next ->
                yText = next
                val y = next.toDoubleOrNull()?.takeIf { it in 0.0..1.0 } ?: return@ConditionNumberField
                val x = xText.toDoubleOrNull()?.takeIf { it in 0.0..1.0 } ?: return@ConditionNumberField
                onChange(condition.copy(point = NormalizedCoordinate(x, y)))
            }, Modifier.weight(1f))
        }
    } else {
        Text("Matches when any pixel in this area has the selected color.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val backgroundAsset by produceState<BundleAsset?>(null, condition.backgroundAssetId) {
            value = condition.backgroundAssetId?.let { loadAsset(it) }
        }
        var showBackgrounds by rememberSaveable { mutableStateOf(false) }
        ImageConditionPreview(region = searchRegion, displayWidthPx = previewWidthPx, displayHeightPx = previewHeightPx, asset = null, backgroundAsset = backgroundAsset, colorSearch = true, onRegionChange = { onChange(condition.copy(region = it)) })
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { showBackgrounds = true }) {
                Icon(Icons.Outlined.Image, contentDescription = "Change background")
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onAddTemplate) {
                Icon(Icons.Outlined.AddPhotoAlternate, contentDescription = "Add image")
            }
        }
        TemplatePickerSheet(
            visible = showBackgrounds,
            title = "Background",
            assets = assets,
            assetNames = assetNames,
            displayWidthPx = previewWidthPx,
            displayHeightPx = previewHeightPx,
            fullScreenOnly = true,
            selectedAssetId = condition.backgroundAssetId?.value,
            allowNone = true,
            onDismiss = { showBackgrounds = false },
            onSelect = { selected ->
                onChange(condition.copy(backgroundAssetId = selected))
                showBackgrounds = false
            },
            loadPreview = loadPreview,
        )
        RegionConditionFields(searchRegion) { onChange(condition.copy(region = it)) }
    }
    ConditionNumberField("Tolerance (0 to 255)", toleranceText, { next ->
        toleranceText = next
        next.toIntOrNull()?.takeIf { it in 0..255 }?.let { onChange(condition.copy(tolerance = it)) }
    })
    ImageCheckTestPanel(condition, assets, assetNames, loadAsset, loadPreview, previewWidthPx, previewHeightPx)
}

@Composable
private fun RgbSlider(
    label: String,
    value: Int,
    color: Color,
    onValueChange: (Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Box(modifier = Modifier.fillMaxWidth().height(36.dp), contentAlignment = Alignment.Center) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Brush.horizontalGradient(listOf(Color.Black, color))),
            )
            Slider(
                modifier = Modifier.semantics { contentDescription = label },
                value = value.toFloat(),
                onValueChange = { onValueChange(it.roundToInt().coerceIn(0, 255)) },
                valueRange = 0f..255f,
                steps = 254,
                colors = SliderDefaults.colors(
                    thumbColor = color,
                    activeTrackColor = Color.Transparent,
                    inactiveTrackColor = Color.Transparent,
                    activeTickColor = Color.Transparent,
                    inactiveTickColor = Color.Transparent,
                ),
            )
        }
    }
}

@Composable
private fun ImageConditionFields(
    condition: Condition,
    assets: List<WorkflowAssetDescriptor>,
    assetNames: Map<String, String>,
    onAddTemplate: () -> Unit,
    loadAsset: suspend (AssetId) -> BundleAsset?,
    loadPreview: suspend (AssetId, Int) -> TemplatePreviewData?,
    previewWidthPx: Int,
    previewHeightPx: Int,
    onChange: (Condition) -> Unit,
) {
    val assetId = when (condition) {
        is ImagePresentCondition -> condition.assetId
        is ImageAbsentCondition -> condition.assetId
        else -> error("Image editor received a non-image condition")
    }
    val selectedAsset by produceState<BundleAsset?>(null, assetId) {
        value = loadAsset(assetId)
    }
    val isScreenState = condition is ImagePresentCondition && condition.matchMode == ImageMatchMode.SCREEN_STATE
    var pickerMode by rememberSaveable { mutableStateOf<ImagePickerMode?>(null) }
    val backgroundAssetId = condition.backgroundAssetId()?.value
    val backgroundAsset by produceState<BundleAsset?>(null, backgroundAssetId) {
        value = backgroundAssetId?.let(::AssetId)?.let { loadAsset(it) }
    }
    val update: (AssetId, NormalizedRect, Double) -> Unit = { selectedAsset, region, threshold ->
        onChange(
            when (condition) {
                is ImagePresentCondition -> condition.copy(assetId = selectedAsset, region = region, matchThreshold = threshold)
                is ImageAbsentCondition -> condition.copy(assetId = selectedAsset, region = region, matchThreshold = threshold)
            },
        )
    }
    ImageConditionPreview(
        region = if (isScreenState) FULL_SCREEN_REGION else condition.region(),
        displayWidthPx = previewWidthPx,
        displayHeightPx = previewHeightPx,
        asset = selectedAsset,
        backgroundAsset = if (isScreenState) null else backgroundAsset,
        screenStateMode = isScreenState,
        onRegionChange = if (isScreenState) {
            null
        } else {
            { region ->
                update(assetId, region, condition.threshold())
            }
        },
    )
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (isScreenState) {
            IconButton(onClick = { pickerMode = ImagePickerMode.SCREEN }) {
                Icon(Icons.Outlined.Image, contentDescription = "Choose screen background")
            }
        } else {
            IconButton(onClick = { pickerMode = ImagePickerMode.BACKGROUND }) {
                Icon(Icons.Outlined.Image, contentDescription = "Change background")
            }
            IconButton(onClick = { pickerMode = ImagePickerMode.TARGET }) {
                Icon(Icons.Outlined.CenterFocusStrong, contentDescription = "Choose target image")
            }
        }
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onAddTemplate) {
            Icon(Icons.Outlined.AddPhotoAlternate, contentDescription = "Add image")
        }
    }
    Text(
        selectedAsset?.let { assetNames[it.assetId.value] ?: "Target image" } ?: "No image selected",
        style = MaterialTheme.typography.bodySmall,
        color = if (selectedAsset == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (!isScreenState) {
        RegionConditionFields(
            region = condition.region(),
            onRegionChange = { update(assetId, it, condition.threshold()) },
        )
    }
    var thresholdText by rememberNumericEditorText(condition.threshold().toEditorText())
    ConditionNumberField("Match threshold (0 to 1)", thresholdText, { next ->
        thresholdText = next
        next.toDoubleOrNull()?.takeIf { it in 0.0..1.0 }?.let { update(assetId, condition.region(), it) }
    })
    ImageCheckTestPanel(condition, assets, assetNames, loadAsset, loadPreview, previewWidthPx, previewHeightPx)
    pickerMode?.let { mode ->
        TemplatePickerSheet(
            visible = true,
            title = if (mode == ImagePickerMode.TARGET) "Target image" else "Background",
            assets = assets,
            assetNames = assetNames,
            displayWidthPx = previewWidthPx,
            displayHeightPx = previewHeightPx,
            fullScreenOnly = mode != ImagePickerMode.TARGET,
            selectedAssetId = when (mode) {
                ImagePickerMode.BACKGROUND -> backgroundAssetId

                ImagePickerMode.SCREEN,
                ImagePickerMode.TARGET,
                -> assetId.value.takeUnless { it == MISSING_TEMPLATE_ASSET_ID }
            },
            allowNone = true,
            onDismiss = { pickerMode = null },
            onSelect = { selected ->
                when (mode) {
                    ImagePickerMode.BACKGROUND -> onChange(
                        when (condition) {
                            is ImagePresentCondition -> condition.copy(backgroundAssetId = selected)
                            is ImageAbsentCondition -> condition.copy(backgroundAssetId = selected)
                            else -> condition
                        },
                    )

                    ImagePickerMode.SCREEN,
                    ImagePickerMode.TARGET,
                    -> update(selected ?: AssetId(MISSING_TEMPLATE_ASSET_ID), condition.region(), condition.threshold())
                }
                pickerMode = null
            },
            loadPreview = loadPreview,
        )
    }
}

private enum class ImagePickerMode { BACKGROUND, SCREEN, TARGET }

@Composable
private fun RegionConditionFields(
    region: NormalizedRect,
    onRegionChange: (NormalizedRect) -> Unit,
) {
    var xText by rememberNumericEditorText(region.x.toEditorText())
    var yText by rememberNumericEditorText(region.y.toEditorText())
    var widthText by rememberNumericEditorText(region.width.toEditorText())
    var heightText by rememberNumericEditorText(region.height.toEditorText())
    fun updateRegion() {
        val x = xText.toDoubleOrNull() ?: return
        val y = yText.toDoubleOrNull() ?: return
        val width = widthText.toDoubleOrNull() ?: return
        val height = heightText.toDoubleOrNull() ?: return
        runCatching { NormalizedRect(x, y, width, height) }.getOrNull()?.let(onRegionChange)
    }
    Text("Screen search region", style = MaterialTheme.typography.labelLarge)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ConditionNumberField("X", xText, {
            xText = it
            updateRegion()
        }, Modifier.weight(1f))
        ConditionNumberField("Y", yText, {
            yText = it
            updateRegion()
        }, Modifier.weight(1f))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ConditionNumberField("Width", widthText, {
            widthText = it
            updateRegion()
        }, Modifier.weight(1f))
        ConditionNumberField("Height", heightText, {
            heightText = it
            updateRegion()
        }, Modifier.weight(1f))
    }
}

@Composable
private fun PackageConditionFields(
    condition: ForegroundPackageCondition,
    allowedPackages: List<String>,
    onAddAllowedPackage: (String) -> Unit,
    onChange: (Condition) -> Unit,
) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val packageName = result.data?.component?.packageName ?: return@rememberLauncherForActivityResult
        onAddAllowedPackage(packageName)
        onChange(condition.copy(packageName = PackageName(packageName)))
    }
    val packages = (allowedPackages + condition.packageName.value).distinct()
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items(packages, key = { it }) { packageName ->
            val selected = condition.packageName.value == packageName
            Surface(
                modifier = Modifier.selectable(selected, role = Role.RadioButton) { onChange(condition.copy(packageName = PackageName(packageName))) },
                shape = RoundedCornerShape(16.dp),
                color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                AllowedApplicationIcon(packageName, Modifier.padding(vertical = 10.dp, horizontal = 4.dp), actionLabel = "Select")
            }
        }
        item(key = "add-app") {
            IconButton(onClick = { picker.launch(allowedApplicationPickerIntent()) }) {
                Icon(Icons.Outlined.Add, "Add allowed app")
            }
        }
    }
}

@Composable
private fun OrientationConditionFields(
    condition: OrientationCondition,
    onChange: (Condition) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        DisplayOrientation.entries.forEach { orientation ->
            val selected = condition.orientation == orientation
            val portrait = orientation == DisplayOrientation.PORTRAIT
            Surface(
                modifier = Modifier.weight(1f).selectable(selected, role = Role.RadioButton) { onChange(condition.copy(orientation = orientation)) },
                shape = RoundedCornerShape(18.dp),
                color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
            ) {
                Column(Modifier.padding(vertical = 18.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(if (portrait) Icons.Outlined.StayCurrentPortrait else Icons.Outlined.StayCurrentLandscape, null, Modifier.size(30.dp))
                    Text(if (portrait) "Portrait" else "Landscape", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

@Composable
private fun ComposedConditionFields(
    label: String,
    conditions: List<Condition>,
    assets: List<WorkflowAssetDescriptor>,
    assetNames: Map<String, String>,
    defaultPackage: String,
    allowedPackages: List<String>,
    onAddAllowedPackage: (String) -> Unit,
    onAddTemplate: () -> Unit,
    loadAsset: suspend (AssetId) -> BundleAsset?,
    loadPreview: suspend (AssetId, Int) -> TemplatePreviewData?,
    previewWidthPx: Int,
    previewHeightPx: Int,
    onChange: (List<Condition>) -> Unit,
) {
    Text(label, style = MaterialTheme.typography.bodySmall)
    var childKeys by rememberSaveable { mutableStateOf(conditions.map { WorkflowIds.newUuid() }) }
    conditions.forEachIndexed { index, child ->
        key(childKeys[index]) {
            NestedConditionEditor(
                number = index + 1,
                condition = child,
                assets = assets,
                assetNames = assetNames,
                defaultPackage = defaultPackage,
                allowedPackages = allowedPackages,
                onAddAllowedPackage = onAddAllowedPackage,
                onAddTemplate = onAddTemplate,
                loadAsset = loadAsset,
                loadPreview = loadPreview,
                previewWidthPx = previewWidthPx,
                previewHeightPx = previewHeightPx,
                onChange = { updated -> onChange(conditions.toMutableList().apply { this[index] = updated }) },
                onRemove = if (conditions.size <= 2) {
                    null
                } else {
                    {
                        childKeys = childKeys.toMutableList().apply { removeAt(index) }
                        onChange(conditions.toMutableList().apply { removeAt(index) })
                    }
                },
            )
        }
    }
    TextButton(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        enabled = conditions.size < MAX_COMPOSED_CONDITIONS,
        onClick = {
            childKeys = childKeys + WorkflowIds.newUuid()
            onChange(conditions + defaultCondition(defaultPackage))
        },
    ) {
        Icon(Icons.Outlined.Add, null, Modifier.size(18.dp))
        Text("Add nested condition", Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun NestedConditionEditor(
    number: Int,
    condition: Condition,
    assets: List<WorkflowAssetDescriptor>,
    assetNames: Map<String, String>,
    defaultPackage: String,
    allowedPackages: List<String>,
    onAddAllowedPackage: (String) -> Unit,
    onAddTemplate: () -> Unit,
    loadAsset: suspend (AssetId) -> BundleAsset?,
    loadPreview: suspend (AssetId, Int) -> TemplatePreviewData?,
    previewWidthPx: Int,
    previewHeightPx: Int,
    onChange: (Condition) -> Unit,
    onRemove: (() -> Unit)? = null,
) {
    var expanded by rememberSaveable { mutableStateOf(true) }
    val fields = rememberSaveableStateHolder()
    val focus = LocalFocusManager.current
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    role = Role.Button,
                    onClickLabel = if (expanded) "Collapse condition" else "Expand condition",
                ) {
                    if (expanded) focus.clearFocus()
                    expanded = !expanded
                }
                .semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" }
                .padding(start = 14.dp, top = 10.dp, end = 8.dp, bottom = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Condition $number", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            ConditionKindPicker(condition, defaultPackage, onChange, Modifier.weight(1.3f))
            if (onRemove != null) {
                IconButton(onClick = onRemove) { Icon(Icons.Outlined.Delete, contentDescription = "Remove nested condition") }
            }
        }
        AnimatedVisibility(expanded, enter = expandVertically(tween(160)) + fadeIn(tween(100)), exit = shrinkVertically(tween(140)) + fadeOut(tween(90))) {
            fields.SaveableStateProvider("fields") {
                Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp)) {
                    ConditionNodeEditor(condition, assets, assetNames, defaultPackage, allowedPackages, onAddAllowedPackage, onAddTemplate, loadAsset, loadPreview, previewWidthPx, previewHeightPx, onChange, showTypePicker = false)
                }
            }
        }
    }
}

@Composable
private fun ConditionNumberField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        singleLine = true,
    )
}

internal enum class EditableConditionKind(val label: String) {
    PIXEL("Pixel colour"),
    SCREEN_STATE("Screen state matches"),
    IMAGE_PRESENT("Image appears somewhere"),
    IMAGE_ABSENT("Image absent"),
    FOREGROUND("Foreground package"),
    ORIENTATION("Orientation"),
    AND("All conditions"),
    OR("Any condition"),
    NOT("Not condition"),
    ;

    fun defaultCondition(defaultPackage: String): Condition = when (this) {
        PIXEL -> PixelColorCondition(NormalizedCoordinate(0.5, 0.5), 0xFFFFFFFFL, 16)

        SCREEN_STATE -> ImagePresentCondition(
            assetId = AssetId(MISSING_TEMPLATE_ASSET_ID),
            region = FULL_SCREEN_REGION,
            matchThreshold = 0.98,
            matchMode = ImageMatchMode.SCREEN_STATE,
        )

        IMAGE_PRESENT -> ImagePresentCondition(
            assetId = AssetId(MISSING_TEMPLATE_ASSET_ID),
            region = NormalizedRect(0.25, 0.25, 0.5, 0.5),
            matchThreshold = 0.9,
        )

        IMAGE_ABSENT -> ImageAbsentCondition(
            assetId = AssetId(MISSING_TEMPLATE_ASSET_ID),
            region = NormalizedRect(0.25, 0.25, 0.5, 0.5),
            matchThreshold = 0.9,
        )

        FOREGROUND -> ForegroundPackageCondition(defaultPackage.toPackageName())

        ORIENTATION -> OrientationCondition(DisplayOrientation.PORTRAIT)

        AND -> AndCondition(
            listOf(
                PIXEL.defaultCondition(defaultPackage),
                ForegroundPackageCondition(defaultPackage.toPackageName()),
            ),
        )

        OR -> OrCondition(
            listOf(
                PIXEL.defaultCondition(defaultPackage),
                ForegroundPackageCondition(defaultPackage.toPackageName()),
            ),
        )

        NOT -> NotCondition(PIXEL.defaultCondition(defaultPackage))
    }
}

private fun Condition.kind(): EditableConditionKind = when (this) {
    is PixelColorCondition -> EditableConditionKind.PIXEL

    is ImagePresentCondition -> when (matchMode) {
        ImageMatchMode.APPEARS_WITHIN_REGION -> EditableConditionKind.IMAGE_PRESENT
        ImageMatchMode.SCREEN_STATE -> EditableConditionKind.SCREEN_STATE
    }

    is ImageAbsentCondition -> EditableConditionKind.IMAGE_ABSENT

    is ForegroundPackageCondition -> EditableConditionKind.FOREGROUND

    is OrientationCondition -> EditableConditionKind.ORIENTATION

    is AndCondition -> EditableConditionKind.AND

    is OrCondition -> EditableConditionKind.OR

    is NotCondition -> EditableConditionKind.NOT

    else -> EditableConditionKind.FOREGROUND
}

private fun defaultCondition(defaultPackage: String): Condition = EditableConditionKind.PIXEL.defaultCondition(defaultPackage)

private fun String.toPackageName(): PackageName = runCatching { PackageName(trim()) }
    .getOrElse { PackageName("com.veyarc.app") }

internal fun Double.toEditorText(): String = "%.4f".format(java.util.Locale.US, this).trimEnd('0').trimEnd('.')

private fun Long.toArgbText(): String = "#%08X".format(this)

private fun String.parseArgb(): Long? {
    val normalized = trim().removePrefix("#").removePrefix("0x").removePrefix("0X")
    if (normalized.length != 8) return null
    return normalized.toLongOrNull(16)?.takeIf { it in 0L..0xFFFF_FFFFL }
}

private val PixelColorCondition.red: Int
    get() = ((expectedArgb shr 16) and 0xFF).toInt()

private val PixelColorCondition.green: Int
    get() = ((expectedArgb shr 8) and 0xFF).toInt()

private val PixelColorCondition.blue: Int
    get() = (expectedArgb and 0xFF).toInt()

private fun PixelColorCondition.withRgb(
    red: Int = this.red,
    green: Int = this.green,
    blue: Int = this.blue,
): PixelColorCondition {
    val alpha = (expectedArgb shr 24) and 0xFF
    val argb = (alpha shl 24) or
        (red.coerceIn(0, 255).toLong() shl 16) or
        (green.coerceIn(0, 255).toLong() shl 8) or
        blue.coerceIn(0, 255).toLong()
    return copy(expectedArgb = argb)
}

private fun Condition.region(): NormalizedRect = when (this) {
    is ImagePresentCondition -> region
    is ImageAbsentCondition -> region
    else -> NormalizedRect(0.25, 0.25, 0.5, 0.5)
}

private fun Condition.threshold(): Double = when (this) {
    is ImagePresentCondition -> matchThreshold
    is ImageAbsentCondition -> matchThreshold
    else -> 0.9
}

private val FULL_SCREEN_REGION = NormalizedRect(0.0, 0.0, 1.0, 1.0)

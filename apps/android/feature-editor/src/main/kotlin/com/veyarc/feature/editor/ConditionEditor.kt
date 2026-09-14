@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.veyarc.feature.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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
import com.veyarc.model.bundle.BundleAsset
import kotlin.math.roundToInt

private const val MAX_COMPOSED_CONDITIONS = 8
private const val MISSING_TEMPLATE_ASSET_ID = "template-missing"

@Composable
internal fun ConditionEditorDialog(
    initialCondition: Condition,
    assets: List<BundleAsset>,
    defaultPackage: String,
    onAddTemplate: () -> Unit,
    previewWidthPx: Int,
    previewHeightPx: Int,
    onDismiss: () -> Unit,
    onApply: (Condition, Set<AssetId>) -> Unit,
) {
    var condition by remember(initialCondition) { mutableStateOf(initialCondition) }
    var removedTemplateIds by remember(initialCondition) { mutableStateOf(emptySet<AssetId>()) }
    val visibleAssets = assets.filterNot { it.assetId in removedTemplateIds }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Edit condition",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Button(onClick = { onApply(condition, removedTemplateIds) }) { Text("Apply") }
                }
                Text(
                    "Conditions are checked only at their step's polling interval. Screen checks use temporary frames; no video is saved.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ConditionNodeEditor(
                    condition = condition,
                    assets = visibleAssets,
                    defaultPackage = defaultPackage,
                    onAddTemplate = onAddTemplate,
                    previewWidthPx = previewWidthPx,
                    previewHeightPx = previewHeightPx,
                    onRemoveTemplate = { assetId ->
                        removedTemplateIds = removedTemplateIds + assetId
                    },
                    onChange = { condition = it },
                )
            }
        }
    }
}

@Composable
private fun ConditionNodeEditor(
    condition: Condition,
    assets: List<BundleAsset>,
    defaultPackage: String,
    onAddTemplate: () -> Unit,
    previewWidthPx: Int,
    previewHeightPx: Int,
    onRemoveTemplate: (AssetId) -> Unit,
    onChange: (Condition) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ConditionKindPicker(
            condition = condition,
            defaultPackage = defaultPackage,
            assets = assets,
            onChange = onChange,
        )
        when (condition) {
            is PixelColorCondition -> PixelConditionFields(condition, onChange)

            is ImagePresentCondition -> ImageConditionFields(
                condition = condition,
                assets = assets,
                onAddTemplate = onAddTemplate,
                onRemoveTemplate = onRemoveTemplate,
                previewWidthPx = previewWidthPx,
                previewHeightPx = previewHeightPx,
                onChange = { onChange(it) },
            )

            is ImageAbsentCondition -> ImageConditionFields(
                condition = condition,
                assets = assets,
                onAddTemplate = onAddTemplate,
                onRemoveTemplate = onRemoveTemplate,
                previewWidthPx = previewWidthPx,
                previewHeightPx = previewHeightPx,
                onChange = { onChange(it) },
            )

            is ForegroundPackageCondition -> PackageConditionFields(condition, onChange)

            is OrientationCondition -> OrientationConditionFields(condition, onChange)

            is AndCondition -> ComposedConditionFields(
                label = "Every condition must be true",
                conditions = condition.conditions,
                assets = assets,
                defaultPackage = defaultPackage,
                onAddTemplate = onAddTemplate,
                onRemoveTemplate = onRemoveTemplate,
                previewWidthPx = previewWidthPx,
                previewHeightPx = previewHeightPx,
                onChange = { children -> onChange(condition.copy(conditions = children)) },
            )

            is OrCondition -> ComposedConditionFields(
                label = "At least one condition must be true",
                conditions = condition.conditions,
                assets = assets,
                defaultPackage = defaultPackage,
                onAddTemplate = onAddTemplate,
                onRemoveTemplate = onRemoveTemplate,
                previewWidthPx = previewWidthPx,
                previewHeightPx = previewHeightPx,
                onChange = { children -> onChange(condition.copy(conditions = children)) },
            )

            is NotCondition -> {
                Text("Invert the nested condition.", style = MaterialTheme.typography.bodySmall)
                ConditionNodeEditor(
                    condition = condition.condition,
                    assets = assets,
                    defaultPackage = defaultPackage,
                    onAddTemplate = onAddTemplate,
                    onRemoveTemplate = onRemoveTemplate,
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
    assets: List<BundleAsset>,
    onChange: (Condition) -> Unit,
) {
    var expanded by remember(condition::class) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Condition type", style = MaterialTheme.typography.labelLarge)
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { expanded = true }) {
                Text(condition.kind().label)
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                EditableConditionKind.entries.forEach { kind ->
                    DropdownMenuItem(
                        text = { Text(kind.label) },
                        onClick = {
                            expanded = false
                            onChange(kind.defaultCondition(defaultPackage, assets))
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun PixelConditionFields(
    condition: PixelColorCondition,
    onChange: (Condition) -> Unit,
) {
    var xText by remember(condition) { mutableStateOf(condition.point.x.toEditorText()) }
    var yText by remember(condition) { mutableStateOf(condition.point.y.toEditorText()) }
    var colorText by remember(condition) { mutableStateOf(condition.expectedArgb.toArgbText()) }
    var toleranceText by remember(condition) { mutableStateOf(condition.tolerance.toString()) }
    Text("Compare one normalized screen point (0 to 1) with an ARGB colour.", style = MaterialTheme.typography.bodySmall)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .background(Color(condition.expectedArgb.toInt()), MaterialTheme.shapes.medium)
                .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.medium),
        )
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
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
    }
    RgbSlider("Red", condition.red) { red -> onChange(condition.withRgb(red = red)) }
    RgbSlider("Green", condition.green) { green -> onChange(condition.withRgb(green = green)) }
    RgbSlider("Blue", condition.blue) { blue -> onChange(condition.withRgb(blue = blue)) }
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
    OutlinedTextField(
        value = colorText,
        onValueChange = { next ->
            colorText = next
            next.parseArgb()?.let { onChange(condition.copy(expectedArgb = it)) }
        },
        modifier = Modifier.fillMaxWidth(),
        label = { Text("Expected ARGB (for example #FF00AA11)") },
        singleLine = true,
    )
    ConditionNumberField("Tolerance (0 to 255)", toleranceText, { next ->
        toleranceText = next
        next.toIntOrNull()?.takeIf { it in 0..255 }?.let { onChange(condition.copy(tolerance = it)) }
    })
}

@Composable
private fun RgbSlider(
    label: String,
    value: Int,
    onValueChange: (Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
            Text(value.toString(), style = MaterialTheme.typography.bodyMedium)
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onValueChange(it.roundToInt().coerceIn(0, 255)) },
            valueRange = 0f..255f,
            steps = 254,
        )
    }
}

@Composable
private fun ImageConditionFields(
    condition: Condition,
    assets: List<BundleAsset>,
    onAddTemplate: () -> Unit,
    onRemoveTemplate: (AssetId) -> Unit,
    previewWidthPx: Int,
    previewHeightPx: Int,
    onChange: (Condition) -> Unit,
) {
    val assetId = when (condition) {
        is ImagePresentCondition -> condition.assetId
        is ImageAbsentCondition -> condition.assetId
        else -> error("Image editor received a non-image condition")
    }
    val selectedAsset = assets.firstOrNull { it.assetId == assetId }
    val selectedAssetIndex = assets.indexOfFirst { it.assetId == assetId }
    val isScreenState = condition is ImagePresentCondition && condition.matchMode == ImageMatchMode.SCREEN_STATE
    var removeConfirmationVisible by remember(assetId) { mutableStateOf(false) }
    var assetMenuExpanded by remember(condition) { mutableStateOf(false) }
    val update: (AssetId, NormalizedRect, Double) -> Unit = { selectedAsset, region, threshold ->
        onChange(
            when (condition) {
                is ImagePresentCondition -> condition.copy(assetId = selectedAsset, region = region, matchThreshold = threshold)
                is ImageAbsentCondition -> condition.copy(assetId = selectedAsset, region = region, matchThreshold = threshold)
            },
        )
    }
    Text(
        if (isScreenState) {
            "Compare one screenshot against the entire current screen at a fixed position. Veyarc checks a coarse representation first, then verifies the full-resolution pixels."
        } else {
            "Set where to look. X and Y are the top-left position; W and H are the search-area size. All values are normalized to the recorded display."
        },
        style = MaterialTheme.typography.bodySmall,
    )
    ImageConditionPreview(
        region = if (isScreenState) FULL_SCREEN_REGION else condition.region(),
        displayWidthPx = previewWidthPx,
        displayHeightPx = previewHeightPx,
        asset = selectedAsset,
        screenStateMode = isScreenState,
        onRegionChange = if (isScreenState) {
            null
        } else {
            { region ->
                update(assetId, region, condition.threshold())
            }
        },
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = { assetMenuExpanded = true }, enabled = assets.isNotEmpty()) {
            Text("Change image")
        }
        DropdownMenu(
            expanded = assetMenuExpanded,
            onDismissRequest = { assetMenuExpanded = false },
        ) {
            assets.forEachIndexed { index, asset ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text("Image ${index + 1}")
                            Text(asset.assetId.value, style = MaterialTheme.typography.labelSmall)
                        }
                    },
                    onClick = {
                        assetMenuExpanded = false
                        val region = condition.region()
                        update(asset.assetId, region, condition.threshold())
                    },
                )
            }
        }
        OutlinedButton(onClick = onAddTemplate) { Text("Add cropped template") }
    }
    Text(
        selectedAsset?.let { "Selected image ${selectedAssetIndex + 1}" } ?: "No image selected",
        style = MaterialTheme.typography.bodySmall,
        color = if (selectedAsset == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (selectedAsset != null) {
        OutlinedButton(
            onClick = { removeConfirmationVisible = true },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Outlined.Delete, contentDescription = null)
            Text("Remove selected image template")
        }
    }
    if (!isScreenState) {
        RegionConditionFields(
            region = condition.region(),
            onRegionChange = { update(assetId, it, condition.threshold()) },
        )
    }
    var thresholdText by remember(condition) { mutableStateOf(condition.threshold().toEditorText()) }
    ConditionNumberField("Match threshold (0 to 1)", thresholdText, { next ->
        thresholdText = next
        next.toDoubleOrNull()?.takeIf { it in 0.0..1.0 }?.let { update(assetId, condition.region(), it) }
    })
    if (removeConfirmationVisible) {
        AlertDialog(
            onDismissRequest = { removeConfirmationVisible = false },
            title = { Text("Remove image template?") },
            text = {
                Text(
                    "This removes the selected encrypted template from the workflow. " +
                        "Other conditions that use it will need a replacement.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        removeConfirmationVisible = false
                        update(
                            AssetId(MISSING_TEMPLATE_ASSET_ID),
                            condition.region(),
                            condition.threshold(),
                        )
                        onRemoveTemplate(assetId)
                    },
                ) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { removeConfirmationVisible = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun RegionConditionFields(
    region: NormalizedRect,
    onRegionChange: (NormalizedRect) -> Unit,
) {
    var xText by remember(region) { mutableStateOf(region.x.toEditorText()) }
    var yText by remember(region) { mutableStateOf(region.y.toEditorText()) }
    var widthText by remember(region) { mutableStateOf(region.width.toEditorText()) }
    var heightText by remember(region) { mutableStateOf(region.height.toEditorText()) }
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
    onChange: (Condition) -> Unit,
) {
    var packageText by remember(condition) { mutableStateOf(condition.packageName.value) }
    OutlinedTextField(
        value = packageText,
        onValueChange = { next ->
            packageText = next
            runCatching { PackageName(next) }.getOrNull()?.let { onChange(condition.copy(packageName = it)) }
        },
        modifier = Modifier.fillMaxWidth(),
        label = { Text("Android package") },
        singleLine = true,
    )
}

@Composable
private fun OrientationConditionFields(
    condition: OrientationCondition,
    onChange: (Condition) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        DisplayOrientation.entries.forEach { orientation ->
            OutlinedButton(onClick = { onChange(condition.copy(orientation = orientation)) }) {
                Text(if (condition.orientation == orientation) "✓ ${orientation.name.lowercase()}" else orientation.name.lowercase())
            }
        }
    }
}

@Composable
private fun ComposedConditionFields(
    label: String,
    conditions: List<Condition>,
    assets: List<BundleAsset>,
    defaultPackage: String,
    onAddTemplate: () -> Unit,
    onRemoveTemplate: (AssetId) -> Unit,
    previewWidthPx: Int,
    previewHeightPx: Int,
    onChange: (List<Condition>) -> Unit,
) {
    Text(label, style = MaterialTheme.typography.bodySmall)
    conditions.forEachIndexed { index, child ->
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Condition ${index + 1}", modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                    if (index >= 2) {
                        IconButton(
                            onClick = { onChange(conditions.toMutableList().apply { removeAt(index) }) },
                        ) {
                            Icon(Icons.Outlined.Delete, contentDescription = "Remove nested condition")
                        }
                    }
                }
                ConditionNodeEditor(
                    condition = child,
                    assets = assets,
                    defaultPackage = defaultPackage,
                    onAddTemplate = onAddTemplate,
                    onRemoveTemplate = onRemoveTemplate,
                    previewWidthPx = previewWidthPx,
                    previewHeightPx = previewHeightPx,
                    onChange = { updated ->
                        onChange(conditions.toMutableList().apply { this[index] = updated })
                    },
                )
            }
        }
    }
    OutlinedButton(
        enabled = conditions.size < MAX_COMPOSED_CONDITIONS,
        onClick = { onChange(conditions + defaultCondition(defaultPackage, assets)) },
    ) {
        Text("Add nested condition")
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

    fun defaultCondition(defaultPackage: String, assets: List<BundleAsset>): Condition = when (this) {
        PIXEL -> PixelColorCondition(NormalizedCoordinate(0.5, 0.5), 0xFFFFFFFFL, 16)

        SCREEN_STATE -> ImagePresentCondition(
            assetId = assets.firstOrNull()?.assetId ?: AssetId(MISSING_TEMPLATE_ASSET_ID),
            region = FULL_SCREEN_REGION,
            matchThreshold = 0.98,
            matchMode = ImageMatchMode.SCREEN_STATE,
        )

        IMAGE_PRESENT -> ImagePresentCondition(
            assetId = assets.firstOrNull()?.assetId ?: AssetId(MISSING_TEMPLATE_ASSET_ID),
            region = NormalizedRect(0.25, 0.25, 0.5, 0.5),
            matchThreshold = 0.9,
        )

        IMAGE_ABSENT -> ImageAbsentCondition(
            assetId = assets.firstOrNull()?.assetId ?: AssetId(MISSING_TEMPLATE_ASSET_ID),
            region = NormalizedRect(0.25, 0.25, 0.5, 0.5),
            matchThreshold = 0.9,
        )

        FOREGROUND -> ForegroundPackageCondition(defaultPackage.toPackageName())

        ORIENTATION -> OrientationCondition(DisplayOrientation.PORTRAIT)

        AND -> AndCondition(
            listOf(
                PIXEL.defaultCondition(defaultPackage, assets),
                ForegroundPackageCondition(defaultPackage.toPackageName()),
            ),
        )

        OR -> OrCondition(
            listOf(
                PIXEL.defaultCondition(defaultPackage, assets),
                ForegroundPackageCondition(defaultPackage.toPackageName()),
            ),
        )

        NOT -> NotCondition(PIXEL.defaultCondition(defaultPackage, assets))
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

private fun defaultCondition(defaultPackage: String, assets: List<BundleAsset>): Condition = EditableConditionKind.PIXEL.defaultCondition(defaultPackage, assets)

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

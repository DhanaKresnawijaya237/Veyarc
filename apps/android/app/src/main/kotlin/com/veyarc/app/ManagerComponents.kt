package com.veyarc.app

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imeAnimationTarget
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.veyarc.data.storage.FolderSummary
import com.veyarc.feature.assistant.RoundedSelectionCheckbox

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CompactManagerHeader(
    title: String,
    searchActive: Boolean,
    query: String,
    selectionMode: Boolean,
    selectedCount: Int,
    onQueryChange: (String) -> Unit,
    onSearchActiveChange: (Boolean) -> Unit,
    onSelectionModeChange: (Boolean) -> Unit,
) {
    val transitionDurationMs = 180
    val searchFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val imeTargetVisible = WindowInsets.imeAnimationTarget.getBottom(LocalDensity.current) > 0
    var keyboardWasVisible by remember { mutableStateOf(false) }
    BackHandler(enabled = LocalMainPageActive.current && (selectionMode || searchActive)) {
        if (searchActive) {
            onSearchActiveChange(false)
            onQueryChange("")
            focusManager.clearFocus()
            keyboardController?.hide()
        } else {
            onSelectionModeChange(false)
        }
    }
    LaunchedEffect(searchActive) {
        if (searchActive) {
            searchFocusRequester.requestFocus()
            keyboardController?.show()
        } else {
            keyboardWasVisible = false
            focusManager.clearFocus()
        }
    }
    LaunchedEffect(imeVisible, imeTargetVisible, searchActive) {
        if (searchActive && imeVisible && imeTargetVisible) keyboardWasVisible = true
        if (searchActive && keyboardWasVisible && !imeTargetVisible) {
            keyboardWasVisible = false
            if (query.isEmpty()) onSearchActiveChange(false)
        }
    }
    BoxWithConstraints(modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        val searchWidth by animateDpAsState(
            targetValue = if (searchActive) maxWidth else 48.dp,
            animationSpec = tween(transitionDurationMs),
            label = "Manager search width",
        )
        val searchEnd by animateDpAsState(
            targetValue = if (searchActive) 0.dp else 48.dp,
            animationSpec = tween(transitionDurationMs),
            label = "Manager search position",
        )
        val headingAlpha by animateFloatAsState(if (searchActive) 0f else 1f, label = "Manager title")
        Row(
            modifier = Modifier.fillMaxWidth().align(Alignment.Center),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val heading = if (selectionMode) {
                if (selectedCount == 0) "Select items" else "$selectedCount selected"
            } else {
                title
            }
            AnimatedContent(
                targetState = heading,
                modifier = Modifier.weight(1f).graphicsLayer { alpha = headingAlpha },
                transitionSpec = {
                    fadeIn(tween(140)) togetherWith fadeOut(tween(90))
                },
                label = "manager heading",
            ) { current ->
                Text(current, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(
                onClick = { onSelectionModeChange(!selectionMode) },
                enabled = !searchActive,
                modifier = Modifier.graphicsLayer { alpha = headingAlpha },
            ) {
                AnimatedContent(selectionMode, label = "manager selection toggle") { selecting ->
                    Icon(
                        if (selecting) Icons.Filled.Close else Icons.Outlined.Checklist,
                        contentDescription = if (selecting) "Leave selection mode" else "Select items",
                    )
                }
            }
        }
        if (!selectionMode || searchActive) {
            Surface(
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = searchEnd).width(searchWidth).height(48.dp),
                shape = RoundedCornerShape(12.dp),
                color = if (searchActive) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = {
                        if (searchActive) {
                            onQueryChange("")
                            keyboardController?.hide()
                        }
                        onSearchActiveChange(!searchActive)
                    }) {
                        Icon(Icons.Filled.Search, contentDescription = "Search")
                    }
                    AnimatedVisibility(
                        visible = searchActive,
                        enter = fadeIn(tween(90, delayMillis = 90)),
                        exit = fadeOut(tween(90)),
                        modifier = Modifier.weight(1f),
                    ) {
                        BasicTextField(
                            value = query,
                            onValueChange = onQueryChange,
                            modifier = Modifier.fillMaxWidth().focusRequester(searchFocusRequester)
                                .semantics { contentDescription = "Search $title" },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            decorationBox = { innerTextField ->
                                Box {
                                    if (query.isEmpty()) Text("Search", maxLines = 1)
                                    innerTextField()
                                }
                            },
                        )
                    }
                    AnimatedVisibility(visible = searchActive, enter = fadeIn(tween(120)), exit = fadeOut(tween(90))) {
                        IconButton(onClick = {
                            onQueryChange("")
                            onSearchActiveChange(false)
                            keyboardController?.hide()
                        }) { Icon(Icons.Filled.Close, contentDescription = "Close search") }
                    }
                }
            }
        }
    }
}

internal data class ManagerSelectionBarState(
    val selectedCount: Int,
    val onCopy: (() -> Unit)?,
    val onRename: (() -> Unit)?,
    val onMove: () -> Unit,
    val onDelete: () -> Unit,
    val onDetails: (() -> Unit)?,
)

@Composable
internal fun ManagerSelectionCheckbox(visible: Boolean, selected: Boolean, enabled: Boolean, onSelectedChange: (Boolean) -> Unit) {
    AnimatedVisibility(visible, enter = expandHorizontally(tween(220), expandFrom = Alignment.Start) + fadeIn(tween(150)), exit = shrinkHorizontally(tween(220), shrinkTowards = Alignment.Start) + fadeOut(tween(100))) {
        RoundedSelectionCheckbox(checked = selected, onCheckedChange = onSelectedChange, enabled = enabled)
    }
}

@Composable
internal fun ManagerSelectionBar(state: ManagerSelectionBarState) {
    NavigationBar {
        ManagerSelectionAction(Icons.Filled.ContentCopy, "Copy", state.onCopy)
        ManagerSelectionAction(Icons.Filled.Edit, "Rename", state.onRename)
        ManagerSelectionAction(Icons.AutoMirrored.Filled.DriveFileMove, "Move", state.onMove.takeIf { state.selectedCount > 0 })
        ManagerSelectionAction(Icons.Filled.Delete, "Delete", state.onDelete.takeIf { state.selectedCount > 0 })
        ManagerSelectionAction(Icons.Filled.Info, "Details", state.onDetails)
    }
}

@Composable
internal fun ManagerSelectionRail(state: ManagerSelectionBarState) {
    NavigationRail {
        Spacer(Modifier.weight(1f))
        ManagerSelectionRailAction(Icons.Filled.ContentCopy, "Copy", state.onCopy)
        Spacer(Modifier.weight(1f))
        ManagerSelectionRailAction(Icons.Filled.Edit, "Rename", state.onRename)
        Spacer(Modifier.weight(1f))
        ManagerSelectionRailAction(Icons.AutoMirrored.Filled.DriveFileMove, "Move", state.onMove.takeIf { state.selectedCount > 0 })
        Spacer(Modifier.weight(1f))
        ManagerSelectionRailAction(Icons.Filled.Delete, "Delete", state.onDelete.takeIf { state.selectedCount > 0 })
        Spacer(Modifier.weight(1f))
        ManagerSelectionRailAction(Icons.Filled.Info, "Details", state.onDetails)
        Spacer(Modifier.weight(1f))
    }
}

@Composable
internal fun ManagerSelectionDetailsDialog(
    title: String,
    lines: List<String>,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.Info, contentDescription = null) },
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                lines.forEach { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.ManagerSelectionAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    action: (() -> Unit)?,
) {
    NavigationBarItem(
        selected = false,
        onClick = { action?.invoke() },
        enabled = action != null,
        icon = { Icon(icon, contentDescription = label) },
        label = { Text(label, maxLines = 1) },
    )
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.ManagerSelectionRailAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    action: (() -> Unit)?,
) {
    NavigationRailItem(
        modifier = Modifier.width(80.dp),
        selected = false,
        onClick = { action?.invoke() },
        enabled = action != null,
        icon = { Icon(icon, contentDescription = label) },
        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
    )
}

internal data class ManagerFolderItem(
    val key: String,
    val label: String,
    val colorArgb: Int? = null,
    val managedFolder: FolderSummary? = null,
)

internal fun reconcileFolderOrder(savedOrder: List<String>, availableKeys: List<String>): List<String> = savedOrder.distinct().filter(availableKeys::contains) + availableKeys.filterNot(savedOrder::contains)

private fun folderDropIndex(
    folders: List<ManagerFolderItem>,
    widths: Map<String, Int>,
    spacingPx: Float,
    draggedKey: String?,
    dragOffsetX: Float,
): Int {
    val sourceIndex = folders.indexOfFirst { it.key == draggedKey }
    if (sourceIndex < 0) return sourceIndex
    var left = 0f
    val centers = folders.associate { folder ->
        val center = left + (widths[folder.key] ?: 0) / 2f
        left += (widths[folder.key] ?: 0) + spacingPx
        folder.key to center
    }
    val draggedCenter = centers.getValue(requireNotNull(draggedKey)) + dragOffsetX
    return folders.count { it.key != draggedKey && (centers[it.key] ?: Float.MAX_VALUE) < draggedCenter }
        .coerceIn(0, folders.lastIndex)
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
internal fun ReorderableFolderRow(
    folders: List<ManagerFolderItem>,
    selectedKey: String,
    onSelect: (String) -> Unit,
    onManage: (FolderSummary) -> Unit,
    onOrderChanged: (List<String>) -> Unit,
    onCreate: () -> Unit,
) {
    val listState = rememberLazyListState()
    val widths = remember { mutableStateMapOf<String, Int>() }
    var draggedKey by remember { mutableStateOf<String?>(null) }
    var dragOffsetX by remember { mutableFloatStateOf(0f) }
    var pointerWindowX by remember { mutableFloatStateOf(0f) }
    var rowLeftInWindow by remember { mutableFloatStateOf(0f) }
    var rowWidthPx by remember { mutableFloatStateOf(0f) }
    var centerRequestKey by remember { mutableStateOf<String?>(null) }
    val sourceIndex = folders.indexOfFirst { it.key == draggedKey }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val spacingPx = with(density) { 8.dp.toPx() }
    val edgeThresholdPx = with(density) { 56.dp.toPx() }
    val targetIndex = folderDropIndex(folders, widths, spacingPx, draggedKey, dragOffsetX)
    val shiftDistance = (draggedKey?.let(widths::get) ?: 0) + spacingPx
    val currentFolders by rememberUpdatedState(folders)
    val currentOrderChanged by rememberUpdatedState(onOrderChanged)

    LaunchedEffect(centerRequestKey, folders, widths.size) {
        val key = centerRequestKey ?: return@LaunchedEffect
        val index = folders.indexOfFirst { it.key == key }
        val itemWidth = widths[key] ?: return@LaunchedEffect
        val viewportWidth = listState.layoutInfo.viewportSize.width
        if (index >= 0 && viewportWidth > 0) {
            listState.animateScrollToItem(index, -((viewportWidth - itemWidth) / 2).coerceAtLeast(0))
            centerRequestKey = null
        }
    }

    LaunchedEffect(draggedKey, pointerWindowX, rowLeftInWindow, rowWidthPx) {
        while (draggedKey != null) {
            val scrollDelta = when {
                pointerWindowX < rowLeftInWindow + edgeThresholdPx -> -20f
                pointerWindowX > rowLeftInWindow + rowWidthPx - edgeThresholdPx -> 20f
                else -> break
            }
            withFrameNanos { }
            val consumed = listState.scrollBy(scrollDelta)
            if (consumed == 0f) break
            dragOffsetX += consumed
        }
    }

    fun finishDrag(commit: Boolean) {
        val key = draggedKey
        val from = currentFolders.indexOfFirst { it.key == key }
        val to = folderDropIndex(currentFolders, widths, spacingPx, key, dragOffsetX)
        if (commit && from >= 0 && to >= 0 && from != to) {
            currentOrderChanged(
                currentFolders.toMutableList().apply { add(to, removeAt(from)) }.map(ManagerFolderItem::key),
            )
        }
        draggedKey = null
        dragOffsetX = 0f
    }

    LazyRow(
        modifier = Modifier.fillMaxWidth().onGloballyPositioned {
            rowLeftInWindow = it.positionInWindow().x
            rowWidthPx = it.size.width.toFloat()
        },
        state = listState,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(folders, key = ManagerFolderItem::key) { folder ->
            val index = folders.indexOfFirst { it.key == folder.key }
            val neighborShift = when {
                sourceIndex < targetIndex && index in (sourceIndex + 1)..targetIndex -> -shiftDistance
                sourceIndex > targetIndex && index in targetIndex until sourceIndex -> shiftDistance
                else -> 0f
            }
            val animatedShift by animateFloatAsState(neighborShift, tween(100), label = "folder insertion")
            ManagerFolderChip(
                folder = folder,
                selected = selectedKey == folder.key,
                translationX = when {
                    folder.key == draggedKey -> dragOffsetX
                    draggedKey != null -> animatedShift
                    else -> 0f
                },
                dragging = folder.key == draggedKey,
                onMeasured = { widths[folder.key] = it },
                onSelect = {
                    onSelect(folder.key)
                    centerRequestKey = folder.key
                },
                onManage = folder.managedFolder?.let { managed -> { onManage(managed) } },
                onDragStart = { pointerX ->
                    onSelect(folder.key)
                    draggedKey = folder.key
                    dragOffsetX = 0f
                    pointerWindowX = pointerX
                },
                onDrag = { deltaX, pointerX ->
                    dragOffsetX += deltaX
                    pointerWindowX = pointerX
                },
                onDragFinished = ::finishDrag,
            )
        }
        item {
            TextButton(onClick = onCreate, modifier = Modifier.height(40.dp)) {
                Icon(
                    Icons.Filled.Add,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    "New folder",
                    modifier = Modifier.padding(start = 6.dp),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun ManagerFolderChip(
    folder: ManagerFolderItem,
    selected: Boolean,
    translationX: Float,
    dragging: Boolean,
    onMeasured: (Int) -> Unit,
    onSelect: () -> Unit,
    onManage: (() -> Unit)?,
    onDragStart: (Float) -> Unit,
    onDrag: (Float, Float) -> Unit,
    onDragFinished: (Boolean) -> Unit,
) {
    var leftInWindow by remember(folder.key) { mutableFloatStateOf(0f) }
    val scale by animateFloatAsState(if (dragging) 1.06f else 1f, tween(100), label = "folder lift")
    val currentDragStart by rememberUpdatedState(onDragStart)
    val currentDrag by rememberUpdatedState(onDrag)
    val currentDragFinished by rememberUpdatedState(onDragFinished)
    val currentManage by rememberUpdatedState(onManage)
    val identityColor = folder.colorArgb?.let(::Color)
    val containerColor = when {
        identityColor != null && selected -> identityColor.copy(alpha = 0.34f)
        identityColor != null -> identityColor.copy(alpha = 0.18f)
        selected -> MaterialTheme.colorScheme.secondaryContainer
        else -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f)
    }
    Surface(
        modifier = Modifier
            .height(40.dp)
            .onGloballyPositioned {
                leftInWindow = it.positionInWindow().x
                onMeasured(it.size.width)
            }
            .zIndex(if (dragging) 2f else 0f)
            .graphicsLayer {
                this.translationX = translationX
                shadowElevation = if (dragging) 14.dp.toPx() else 0f
            }
            .scale(scale)
            .pointerInput(folder.key) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val longPress = awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
                    longPress.consume()
                    var movement = Offset.Zero
                    var dragging = false
                    val completed = drag(longPress.id) { change ->
                        val delta = change.positionChange()
                        movement += delta
                        val startsDrag = !dragging && movement.getDistance() > viewConfiguration.touchSlop
                        if (startsDrag) {
                            dragging = true
                            currentDragStart(leftInWindow + change.position.x)
                        }
                        if (dragging) {
                            change.consume()
                            currentDrag(if (startsDrag) movement.x else delta.x, leftInWindow + change.position.x)
                        }
                    }
                    if (dragging) {
                        currentDragFinished(completed)
                    } else {
                        // The sibling click detector may consume the final UP after the long press.
                        // Reaching here already proves a stationary long hold completed.
                        currentManage?.invoke()
                    }
                }
            }
            .semantics {
                if (onManage != null) {
                    customActions = listOf(
                        CustomAccessibilityAction("Manage folder") {
                            currentManage?.invoke()
                            true
                        },
                    )
                }
            }
            .combinedClickable(onClick = onSelect),
        shape = MaterialTheme.shapes.small,
        color = containerColor,
        tonalElevation = if (selected) 2.dp else 0.dp,
    ) {
        Box(Modifier.padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
            Text(folder.label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
    }
}

@Composable
internal fun FolderManagementDialog(
    folder: FolderSummary,
    usedColors: Set<Int>,
    onDismiss: () -> Unit,
    onSave: (String, Int?) -> Unit,
    onDelete: () -> Unit,
) {
    var name by remember(folder.folderId) { mutableStateOf(folder.name) }
    var colorArgb by remember(folder.folderId) { mutableStateOf(folder.colorArgb) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BasicTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    textStyle = MaterialTheme.typography.headlineSmall.merge(
                        TextStyle(color = MaterialTheme.colorScheme.onSurface),
                    ),
                )
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = "Delete ${folder.name}")
                }
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Folder color", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Surface(
                        modifier = Modifier.size(40.dp).combinedClickable(onClick = { colorArgb = null }),
                        shape = MaterialTheme.shapes.small,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    ) {
                        if (colorArgb == null) Icon(Icons.Filled.Check, "No folder color", Modifier.padding(8.dp))
                    }
                    FOLDER_COLORS.take(3).forEach { candidate ->
                        val enabled = candidate !in usedColors
                        Surface(
                            modifier = Modifier.size(40.dp).combinedClickable(enabled = enabled, onClick = { colorArgb = candidate }),
                            shape = MaterialTheme.shapes.small,
                            color = Color(candidate).copy(alpha = if (enabled) 0.5f else 0.12f),
                        ) {
                            if (colorArgb == candidate) Icon(Icons.Filled.Check, "Selected folder color", Modifier.padding(8.dp))
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FOLDER_COLORS.drop(3).forEach { candidate ->
                        val enabled = candidate !in usedColors
                        Surface(
                            modifier = Modifier.size(40.dp).combinedClickable(enabled = enabled, onClick = { colorArgb = candidate }),
                            shape = MaterialTheme.shapes.small,
                            color = Color(candidate).copy(alpha = if (enabled) 0.5f else 0.12f),
                        ) {
                            if (colorArgb == candidate) Icon(Icons.Filled.Check, "Selected folder color", Modifier.padding(8.dp))
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(name, colorArgb) }, enabled = name.isNotBlank()) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private val FOLDER_COLORS = listOf(
    0xFFEF5350.toInt(),
    0xFFFFA726.toInt(),
    0xFFFFCA28.toInt(),
    0xFF66BB6A.toInt(),
    0xFF26A69A.toInt(),
    0xFF42A5F5.toInt(),
    0xFF7E57C2.toInt(),
    0xFFAB47BC.toInt(),
)

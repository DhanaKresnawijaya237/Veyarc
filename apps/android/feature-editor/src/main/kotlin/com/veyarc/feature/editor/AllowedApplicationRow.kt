package com.veyarc.feature.editor

import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import kotlin.math.roundToInt

@Composable
internal fun AllowedApplicationRow(packages: List<String>, onRemove: (String) -> Unit, onReorder: (List<String>) -> Unit) {
    val context = LocalContext.current
    val list = rememberLazyListState()
    val density = LocalDensity.current
    val slot = with(density) { (72.dp.roundToPx() + 18.dp.roundToPx()).toFloat() }
    val edge = with(density) { 96.dp.toPx() }
    val deleteRadius = with(density) { 20.dp.toPx() }
    val deleteInset = with(density) { 8.dp.toPx() }
    val verticalPadding = with(density) { 8.dp.toPx() }
    val scrollSpeed = with(density) { 600.dp.toPx() }
    var rowSize by remember { mutableStateOf(IntSize.Zero) }
    var dragged by remember { mutableStateOf<String?>(null) }
    var pointer by remember { mutableStateOf(Offset.Zero) }
    var startPointer by remember { mutableStateOf(Offset.Zero) }
    var grabOffset by remember { mutableStateOf(Offset.Zero) }
    var scrolled by remember { mutableFloatStateOf(0f) }
    val currentPackages by rememberUpdatedState(packages)
    val remove by rememberUpdatedState(onRemove)
    val reorder by rememberUpdatedState(onReorder)
    val source = packages.indexOf(dragged)
    val target by remember(packages, slot) {
        derivedStateOf { (packages.indexOf(dragged) + ((pointer.x - startPointer.x + scrolled) / slot).roundToInt()).coerceIn(0, packages.lastIndex) }
    }
    fun isOverDelete(): Boolean {
        val center = Offset(rowSize.width - deleteInset - deleteRadius, rowSize.height / 2f)
        return (pointer - center).getDistance() <= deleteRadius
    }
    val overDelete by remember(deleteRadius, deleteInset) { derivedStateOf { dragged != null && isOverDelete() } }

    fun move(from: Int, to: Int) {
        if (from != to) reorder(currentPackages.toMutableList().apply { add(to, removeAt(from)) })
    }
    fun finish(commit: Boolean) {
        val key = dragged
        val from = currentPackages.indexOf(key)
        if (commit && from >= 0) {
            if (isOverDelete()) remove(requireNotNull(key)) else move(from, (from + ((pointer.x - startPointer.x + scrolled) / slot).roundToInt()).coerceIn(0, currentPackages.lastIndex))
        }
        dragged = null
        scrolled = 0f
    }
    val finishDrag by rememberUpdatedState<(Boolean) -> Unit>(::finish)

    LaunchedEffect(dragged) {
        var previousFrame = withFrameNanos { it }
        while (dragged != null) {
            val frame = withFrameNanos { it }
            val seconds = ((frame - previousFrame) / 1_000_000_000f).coerceAtMost(0.032f)
            previousFrame = frame
            // Keep a route beside the delete circle for scrolling to later apps.
            val speed = when {
                overDelete || pointer.y !in -edge..(rowSize.height + edge) -> 0f
                pointer.x < edge -> -scrollSpeed * ((edge - pointer.x) / edge).coerceIn(0f, 1f)
                pointer.x > rowSize.width - edge -> scrollSpeed * ((pointer.x - rowSize.width + edge) / edge).coerceIn(0f, 1f)
                else -> 0f
            }
            if (speed != 0f) scrolled += list.scrollBy(speed * seconds)
        }
    }
    Box(
        Modifier.fillMaxWidth().onSizeChanged { rowSize = it }.testTag("allowed-app-row").pointerInput(slot) {
            // The stationary viewport owns the gesture, even after the source scrolls off-screen.
            detectDragGesturesAfterLongPress(
                onDragStart = { position ->
                    val padding = list.layoutInfo.beforeContentPadding
                    val item = list.layoutInfo.visibleItemsInfo.firstOrNull { position.x in (it.offset + padding).toFloat()..(it.offset + padding + it.size).toFloat() }
                    if (item != null && position.y in verticalPadding..(rowSize.height - verticalPadding)) {
                        dragged = item.key as String
                        pointer = position
                        startPointer = position
                        grabOffset = position - Offset((item.offset + padding).toFloat(), verticalPadding)
                        scrolled = 0f
                    }
                },
                onDrag = { change, _ ->
                    if (dragged != null) {
                        change.consume()
                        pointer = change.position
                    }
                },
                onDragEnd = { finishDrag(true) },
                onDragCancel = { finishDrag(false) },
            )
        },
    ) {
        LazyRow(state = list, userScrollEnabled = dragged == null, contentPadding = PaddingValues(start = 8.dp, end = 64.dp, top = 8.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
            itemsIndexed(packages, key = { _, name -> name }) { index, packageName ->
                val shift = when {
                    source < 0 -> 0f
                    source < target && index in (source + 1)..target -> -slot
                    source > target && index in target until source -> slot
                    else -> 0f
                }
                val animatedShift by animateFloatAsState(shift, tween(100), label = "app insertion")
                AllowedApplicationIcon(
                    packageName,
                    Modifier.graphicsLayer {
                        alpha = if (packageName == dragged) 0f else 1f
                        translationX = if (dragged != null) animatedShift else 0f
                    }.semantics {
                        customActions = buildList {
                            if (index > 0) {
                                add(
                                    CustomAccessibilityAction("Move left") {
                                        move(index, index - 1)
                                        true
                                    },
                                )
                            }
                            if (index < packages.lastIndex) {
                                add(
                                    CustomAccessibilityAction("Move right") {
                                        move(index, index + 1)
                                        true
                                    },
                                )
                            }
                            add(
                                CustomAccessibilityAction("Remove allowed app") {
                                    remove(packageName)
                                    true
                                },
                            )
                        }
                    }.testTag("allowed-app-$packageName").clickable {
                        val launched = runCatching {
                            val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: error("No launcher")
                            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }.isSuccess
                        if (!launched) Toast.makeText(context, "This app cannot be opened.", Toast.LENGTH_SHORT).show()
                    },
                )
            }
        }
        dragged?.let { packageName ->
            AllowedApplicationIcon(
                packageName,
                Modifier.graphicsLayer {
                    translationX = pointer.x - grabOffset.x
                    translationY = pointer.y - grabOffset.y - 4.dp.toPx()
                    scaleX = 1.06f
                    scaleY = 1.06f
                }.testTag("allowed-app-dragging"),
            )
        }
        AnimatedVisibility(dragged != null, Modifier.align(Alignment.CenterEnd).padding(end = 8.dp), enter = fadeIn(tween(90)), exit = fadeOut(tween(70))) {
            val hoverRed = Color(0xFFEF4444)
            val background by animateColorAsState(if (overDelete) hoverRed.copy(alpha = 0.28f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f), tween(90), label = "delete hover background")
            val tint by animateColorAsState(if (overDelete) hoverRed else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f), tween(90), label = "delete hover icon")
            Box(Modifier.size(40.dp).background(background, CircleShape).testTag("allowed-app-delete"), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Delete, "Drop to remove app", tint = tint)
            }
        }
    }
}

@Composable
private fun AllowedApplicationIcon(packageName: String, modifier: Modifier) {
    val context = LocalContext.current
    val iconSize = with(LocalDensity.current) { 44.dp.roundToPx() }
    val info = remember(packageName) { runCatching { context.packageManager.getApplicationInfo(packageName, 0) }.getOrNull() }
    val label = remember(info, packageName) { info?.let { context.packageManager.getApplicationLabel(it).toString() } ?: packageName.substringAfterLast('.') }
    val icon = remember(info, iconSize) { info?.let { context.packageManager.getApplicationIcon(it).toBitmap(iconSize, iconSize).asImageBitmap() } }
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier.width(72.dp).semantics { contentDescription = "Open $label" }) {
        if (icon != null) Image(icon, null, Modifier.size(44.dp)) else Icon(Icons.Outlined.Apps, null, Modifier.size(44.dp))
        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall)
    }
}

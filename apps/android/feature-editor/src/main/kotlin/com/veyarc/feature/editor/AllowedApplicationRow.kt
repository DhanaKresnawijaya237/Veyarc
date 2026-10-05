package com.veyarc.feature.editor

import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.lazy.items
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.core.graphics.drawable.toBitmap
import kotlin.math.roundToInt

@Composable
internal fun AllowedApplicationRow(packages: List<String>, onRemove: (String) -> Unit, onReorder: (List<String>) -> Unit) {
    val context = LocalContext.current
    val list = rememberLazyListState()
    val density = LocalDensity.current
    val slot = with(density) { 90.dp.toPx() }
    val deleteWidth = with(density) { 56.dp.toPx() }
    val iconSize = with(density) { 44.dp.roundToPx() }
    var rowCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var dragged by remember { mutableStateOf<String?>(null) }
    var offset by remember { mutableFloatStateOf(0f) }
    var pointerX by remember { mutableFloatStateOf(0f) }
    var pointerY by remember { mutableFloatStateOf(0f) }
    var startX by remember { mutableFloatStateOf(0f) }
    var scrolled by remember { mutableFloatStateOf(0f) }
    val currentPackages by rememberUpdatedState(packages)
    val remove by rememberUpdatedState(onRemove)
    val reorder by rememberUpdatedState(onReorder)
    val source = packages.indexOf(dragged)
    val target = (source + (offset / slot).roundToInt()).coerceIn(0, packages.lastIndex)
    val rowRight = rowCoordinates?.localToWindow(androidx.compose.ui.geometry.Offset.Zero)?.x?.plus(rowCoordinates!!.size.width) ?: Float.MAX_VALUE
    fun isOverDelete(): Boolean {
        val coordinates = rowCoordinates ?: return false
        val position = coordinates.localToWindow(androidx.compose.ui.geometry.Offset.Zero)
        val right = position.x + coordinates.size.width
        return pointerX >= right - deleteWidth && pointerX <= right + deleteWidth / 2 && pointerY in position.y..(position.y + coordinates.size.height)
    }
    val overDelete = dragged != null && isOverDelete()

    fun move(from: Int, to: Int) {
        if (from != to) reorder(currentPackages.toMutableList().apply { add(to, removeAt(from)) })
    }
    fun finish(commit: Boolean) {
        val key = dragged
        val from = currentPackages.indexOf(key)
        if (commit && from >= 0) {
            if (isOverDelete()) remove(requireNotNull(key)) else move(from, (from + (offset / slot).roundToInt()).coerceIn(0, currentPackages.lastIndex))
        }
        dragged = null
        offset = 0f
    }
    val finishDrag by rememberUpdatedState<(Boolean) -> Unit>(::finish)

    LaunchedEffect(dragged, pointerX, overDelete) {
        while (dragged != null && !overDelete) {
            val left = rowCoordinates?.localToWindow(androidx.compose.ui.geometry.Offset.Zero)?.x ?: break
            val delta = when {
                pointerX < left + deleteWidth -> -20f
                pointerX > rowRight - deleteWidth * 1.6f -> 20f
                else -> break
            }
            withFrameNanos { }
            val consumed = list.scrollBy(delta)
            if (consumed == 0f) break
            scrolled += consumed
            offset += consumed
        }
    }
    Box(Modifier.fillMaxWidth().onGloballyPositioned { rowCoordinates = it }.testTag("allowed-app-row")) {
        LazyRow(state = list, contentPadding = PaddingValues(start = 8.dp, end = 64.dp, top = 8.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
            items(packages, key = { it }) { packageName ->
                val info = remember(packageName) { runCatching { context.packageManager.getApplicationInfo(packageName, 0) }.getOrNull() }
                val label = remember(info, packageName) { info?.let { context.packageManager.getApplicationLabel(it).toString() } ?: packageName.substringAfterLast('.') }
                val icon = remember(info, iconSize) { info?.let { context.packageManager.getApplicationIcon(it).toBitmap(iconSize, iconSize).asImageBitmap() } }
                var coordinates by remember(packageName) { mutableStateOf<LayoutCoordinates?>(null) }
                val index = packages.indexOf(packageName)
                val shift = when {
                    source < target && index in (source + 1)..target -> -slot
                    source > target && index in target until source -> slot
                    else -> 0f
                }
                val animatedShift by animateFloatAsState(shift, tween(100), label = "app insertion")
                val lifted = packageName == dragged
                val scale by animateFloatAsState(if (lifted) 1.08f else 1f, tween(100), label = "app lift")
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.width(72.dp).zIndex(if (lifted) 1f else 0f).onGloballyPositioned { coordinates = it }
                        .graphicsLayer {
                            translationX = if (lifted) {
                                offset
                            } else if (dragged != null) {
                                animatedShift
                            } else {
                                0f
                            }
                            translationY = if (lifted) -4.dp.toPx() else 0f
                            scaleX = scale
                            scaleY = scale
                        }.pointerInput(packageName) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = {
                                    dragged = packageName
                                    offset = 0f
                                    scrolled = 0f
                                    startX = coordinates!!.localToWindow(it).x
                                    pointerX = startX
                                    pointerY = coordinates!!.localToWindow(it).y
                                },
                                onDrag = { change, _ ->
                                    change.consume()
                                    pointerX = coordinates!!.localToWindow(change.position).x
                                    pointerY = coordinates!!.localToWindow(change.position).y
                                    offset = pointerX - startX + scrolled
                                },
                                onDragEnd = { finishDrag(true) },
                                onDragCancel = { finishDrag(false) },
                            )
                        }.semantics {
                            contentDescription = "Open $label"
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
                ) {
                    if (icon != null) {
                        Image(icon, null, Modifier.size(44.dp))
                    } else {
                        Icon(Icons.Outlined.Apps, null, Modifier.size(44.dp))
                    }
                    Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        AnimatedVisibility(dragged != null, Modifier.align(Alignment.CenterEnd), enter = fadeIn(tween(90)), exit = fadeOut(tween(70))) {
            Box(Modifier.padding(4.dp).size(48.dp).background(if (overDelete) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape).testTag("allowed-app-delete"), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Delete, "Drop to remove app", tint = if (overDelete) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

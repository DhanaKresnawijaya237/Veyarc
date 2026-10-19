package com.veyarc.feature.editor

import android.content.res.Configuration
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EditorSheetDialog(
    compact: Boolean,
    onDismiss: () -> Unit,
    compactMaxHeightFraction: Float = 0.82f,
    compactFixedHeightFraction: Float? = null,
    largeHeightFraction: Float = 0.8f,
    contentScrollable: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    val contentScrollState = rememberScrollState()
    val contentStates = rememberSaveableStateHolder()
    val currentContent by rememberUpdatedState(content)
    val retainedBody = remember {
        movableContentOf {
            contentStates.SaveableStateProvider("editor-content") {
                Box(modifier = Modifier.fillMaxWidth(), content = currentContent)
            }
        }
    }
    val retainedContent: @Composable BoxScope.() -> Unit = { retainedBody() }
    val configuration = LocalConfiguration.current
    val sheetColor = MaterialTheme.colorScheme.surfaceContainerLow
    val landscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val useCompactPage = compact && !landscape
    if (!useCompactPage) {
        val density = LocalDensity.current
        var contentHeightPx by remember { mutableIntStateOf(0) }
        val availableHeightPx = with(density) { configuration.screenHeightDp.dp.roundToPx() }
        val largeMode = largeEditorSheetMode(
            contentHeightPx = contentHeightPx,
            availableHeightPx = availableHeightPx,
            intermediateHeightFraction = largeHeightFraction,
        )
        val wrappedContent = contentScrollable && largeMode == LargeEditorSheetMode.WRAPPED
        val sheetGesturesEnabled = rememberEditorSheetGesturesEnabled(contentScrollState)
        ModalBottomSheet(
            modifier = Modifier.wrapContentSheetDragToDismiss(wrappedContent, onDismiss),
            onDismissRequest = onDismiss,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            sheetGesturesEnabled = !wrappedContent && sheetGesturesEnabled,
            dragHandle = { CompactEditorSheetDragHandle(height = if (landscape) 20.dp else 32.dp) },
            contentWindowInsets = {
                WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)
            },
            shape = RoundedCornerShape(24.dp),
            containerColor = sheetColor,
            tonalElevation = 0.dp,
        ) {
            if (contentScrollable) {
                AdaptiveLargeEditorSheetContent(
                    scrollState = contentScrollState,
                    intermediateHeightFraction = largeHeightFraction,
                    mode = largeMode,
                    onContentHeightChanged = { contentHeightPx = it },
                    content = retainedContent,
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = (configuration.screenHeightDp * largeHeightFraction).dp),
                    content = retainedContent,
                )
            }
        }
        return
    }

    var closing by remember { mutableStateOf(false) }
    val alpha by animateFloatAsState(
        targetValue = if (closing) 0f else 1f,
        animationSpec = tween(120),
        label = "compact editor fade",
        finishedListener = { if (closing) onDismiss() },
    )
    val requestDismiss = { closing = true }

    Dialog(
        onDismissRequest = requestDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) { detectTapGestures { requestDismiss() } },
            )
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        compactFixedHeightFraction?.let { Modifier.fillMaxHeight(it) }
                            ?: Modifier.heightIn(max = (configuration.screenHeightDp * compactMaxHeightFraction).dp),
                    )
                    .graphicsLayer { this.alpha = alpha }
                    .pointerInput(Unit) { detectTapGestures { } },
                shape = RoundedCornerShape(24.dp),
                color = sheetColor,
                tonalElevation = 0.dp,
                shadowElevation = 8.dp,
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .then(
                            if (contentScrollable) {
                                Modifier.verticalScroll(contentScrollState, overscrollEffect = null)
                            } else {
                                Modifier
                            },
                        ),
                    content = retainedContent,
                )
            }
        }
    }
}

@Composable
private fun AdaptiveLargeEditorSheetContent(
    scrollState: ScrollState,
    intermediateHeightFraction: Float,
    mode: LargeEditorSheetMode,
    onContentHeightChanged: (Int) -> Unit,
    content: @Composable BoxScope.() -> Unit,
) {
    val configuration = LocalConfiguration.current
    LaunchedEffect(mode) {
        if (mode == LargeEditorSheetMode.WRAPPED) scrollState.scrollTo(0)
    }
    val measuredContent = Modifier
        .fillMaxWidth()
        .onSizeChanged { onContentHeightChanged(it.height) }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(animationSpec = tween(180)),
    ) {
        if (mode == LargeEditorSheetMode.WRAPPED) {
            Box(modifier = measuredContent, content = content)
        } else {
            val heightFraction = if (mode == LargeEditorSheetMode.FULL_SCROLLABLE) {
                1f
            } else {
                intermediateHeightFraction
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = (configuration.screenHeightDp * heightFraction).dp)
                    .nestedScroll(PreventUpwardSheetOverscrollConnection)
                    .verticalScroll(scrollState, overscrollEffect = null),
            ) {
                Box(
                    modifier = measuredContent,
                    content = content,
                )
            }
        }
    }
}

internal enum class LargeEditorSheetMode {
    WRAPPED,
    CAPPED_SCROLLABLE,
    FULL_SCROLLABLE,
}

internal fun largeEditorSheetMode(
    contentHeightPx: Int,
    availableHeightPx: Int,
    intermediateHeightFraction: Float = 0.8f,
): LargeEditorSheetMode = when {
    contentHeightPx <= 0 || availableHeightPx <= 0 -> LargeEditorSheetMode.CAPPED_SCROLLABLE
    contentHeightPx >= availableHeightPx -> LargeEditorSheetMode.FULL_SCROLLABLE
    contentHeightPx >= availableHeightPx * intermediateHeightFraction -> LargeEditorSheetMode.CAPPED_SCROLLABLE
    else -> LargeEditorSheetMode.WRAPPED
}

@Composable
private fun Modifier.wrapContentSheetDragToDismiss(
    enabled: Boolean,
    onDismiss: () -> Unit,
): Modifier {
    if (!enabled) return this
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    var offsetY by remember { mutableFloatStateOf(0f) }
    val thresholdPx = with(density) { 72.dp.toPx() }
    val dismissDistancePx = with(density) { configuration.screenHeightDp.dp.toPx() }
    return graphicsLayer { translationY = offsetY }
        .draggable(
            state = rememberDraggableState { delta -> offsetY = (offsetY + delta).coerceAtLeast(0f) },
            orientation = Orientation.Vertical,
            onDragStopped = { velocity ->
                val dismiss = offsetY >= thresholdPx || velocity >= 1_250f
                animate(
                    initialValue = offsetY,
                    targetValue = if (dismiss) dismissDistancePx else 0f,
                    animationSpec = tween(if (dismiss) 180 else 160),
                ) { value, _ -> offsetY = value }
                if (dismiss) currentOnDismiss()
            },
        )
}

private object PreventUpwardSheetOverscrollConnection : NestedScrollConnection {
    override fun onPostScroll(
        consumed: Offset,
        available: Offset,
        source: NestedScrollSource,
    ): Offset = if (source == NestedScrollSource.UserInput) {
        Offset(x = 0f, y = upwardOverscrollConsumption(available.y))
    } else {
        Offset.Zero
    }
}

/** A gesture that starts inside the list stops there; a fresh outward drag at an edge can leave it. */
internal class TemplateListScroll(private val list: LazyListState) : NestedScrollConnection {
    private var keepInside: Boolean? = null

    fun startGesture() {
        keepInside = null
    }

    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        if (source == NestedScrollSource.UserInput && keepInside == null && available.y != 0f) {
            keepInside = if (available.y > 0f) list.canScrollBackward else list.canScrollForward
        }
        return Offset.Zero
    }

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource) = if (keepInside != false) Offset(0f, available.y) else Offset.Zero
    override suspend fun onPostFling(consumed: Velocity, available: Velocity) = if (keepInside != false) Velocity(0f, available.y) else Velocity.Zero
}

internal fun upwardOverscrollConsumption(availableY: Float): Float = if (availableY < 0f) availableY else 0f

@Composable
internal fun CompactEditorSheetDragHandle(height: Dp = 20.dp) {
    Box(
        modifier = Modifier.fillMaxWidth().height(height),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .width(32.dp)
                .height(4.dp)
                .background(
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                    RoundedCornerShape(2.dp),
                ),
        )
    }
}

@Composable
internal fun rememberEditorSheetGesturesEnabled(scrollState: ScrollableState): Boolean {
    var enabled by remember(scrollState) { mutableStateOf(!scrollState.canScrollBackward) }
    LaunchedEffect(scrollState) {
        snapshotFlow { scrollState.canScrollBackward to scrollState.isScrollInProgress }
            .collect { (canScrollBackward, scrolling) ->
                enabled = nextEditorSheetGesturesEnabled(enabled, canScrollBackward, scrolling)
            }
    }
    return enabled
}

internal fun nextEditorSheetGesturesEnabled(
    currentlyEnabled: Boolean,
    canScrollBackward: Boolean,
    scrolling: Boolean,
): Boolean = when {
    canScrollBackward -> false
    !scrolling -> true
    else -> currentlyEnabled
}

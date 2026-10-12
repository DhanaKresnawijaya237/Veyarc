package com.veyarc.feature.editor

import android.content.res.Configuration
import android.graphics.Rect
import android.view.ViewTreeObserver
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

data class VeyarcChoice(
    val id: String,
    val label: String,
    val detail: String? = null,
    val destructive: Boolean = false,
    val group: Int = 0,
)

/** A bounded, searchable picker that stays usable in portrait, landscape, and long lists. */
@Composable
fun VeyarcChoicePicker(
    title: String,
    options: List<VeyarcChoice>,
    selectedId: String? = null,
    onDismiss: () -> Unit,
    onSelect: (VeyarcChoice) -> Unit,
) {
    val configuration = LocalConfiguration.current
    val maxHeight = configuration.screenHeightDp.dp *
        if (configuration.orientation == Configuration.ORIENTATION_PORTRAIT) 0.7f else 0.9f
    val searchable = options.size > 7
    var searchExpanded by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val currentQuery by rememberUpdatedState(query)
    val searchFocusRequester = remember { FocusRequester() }
    var keyboardController by remember { mutableStateOf<SoftwareKeyboardController?>(null) }
    var keyboardWasVisible by remember { mutableStateOf(false) }
    val filtered = remember(options, query) {
        options.filter { option ->
            option.label.contains(query, ignoreCase = true) || option.detail?.contains(query, ignoreCase = true) == true
        }
    }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = options.indexOfFirst { it.id == selectedId }.coerceAtLeast(0))
    val onOutsidePress = {
        if (searchExpanded) {
            keyboardController?.hide()
            if (query.isEmpty()) searchExpanded = false
        } else {
            onDismiss()
        }
    }

    Dialog(
        onDismissRequest = onOutsidePress,
        properties = DialogProperties(dismissOnClickOutside = false, usePlatformDefaultWidth = false),
    ) {
        val dialogController = LocalSoftwareKeyboardController.current
        val view = LocalView.current
        SideEffect { keyboardController = dialogController }
        LaunchedEffect(searchExpanded) {
            if (searchExpanded) {
                searchFocusRequester.requestFocus()
                dialogController?.show()
            } else {
                keyboardWasVisible = false
            }
        }
        DisposableEffect(view, searchExpanded) {
            if (!searchExpanded) {
                onDispose { }
            } else {
                val root = view.rootView
                val visibleFrame = Rect()
                val keyboardThreshold = (120 * view.resources.displayMetrics.density).toInt()
                val listener = ViewTreeObserver.OnGlobalLayoutListener {
                    root.getWindowVisibleDisplayFrame(visibleFrame)
                    val keyboardVisible = view.resources.displayMetrics.heightPixels - visibleFrame.bottom > keyboardThreshold
                    if (keyboardVisible) keyboardWasVisible = true
                    if (!keyboardVisible && keyboardWasVisible) {
                        keyboardWasVisible = false
                        if (currentQuery.isEmpty()) searchExpanded = false
                    }
                }
                root.viewTreeObserver.addOnGlobalLayoutListener(listener)
                onDispose { root.viewTreeObserver.removeOnGlobalLayoutListener(listener) }
            }
        }
        Box(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier.matchParentSize().clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onOutsidePress,
                ),
            )
            Box(modifier = Modifier.align(Alignment.Center).fillMaxWidth().padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
                Surface(
                    modifier = Modifier
                        .widthIn(max = 480.dp)
                        .fillMaxWidth()
                        .heightIn(max = maxHeight)
                        .animateContentSize(tween(160)),
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    tonalElevation = 0.dp,
                    shadowElevation = 12.dp,
                ) {
                    com.veyarc.ui.VeyarcParticles(interactive = false, modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            BoxWithConstraints {
                                val availableWidth = maxWidth
                                val searchWidth by animateDpAsState(
                                    targetValue = if (searchExpanded) (availableWidth - 64.dp).coerceAtLeast(48.dp) else 48.dp,
                                    animationSpec = tween(180),
                                    label = "Search width",
                                )
                                val titleAlpha by animateFloatAsState(if (searchExpanded) 0f else 1f, label = "Picker title")
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        title,
                                        modifier = Modifier.weight(1f).graphicsLayer { alpha = titleAlpha },
                                        style = MaterialTheme.typography.titleLarge,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    if (searchable) {
                                        Surface(
                                            modifier = Modifier.width(searchWidth).height(48.dp),
                                            shape = RoundedCornerShape(12.dp),
                                            color = if (searchExpanded) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent,
                                        ) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                IconButton(onClick = {
                                                    searchExpanded = !searchExpanded
                                                    if (!searchExpanded) {
                                                        query = ""
                                                        keyboardController?.hide()
                                                    }
                                                }) {
                                                    Icon(Icons.Outlined.Search, contentDescription = "Search $title")
                                                }
                                                AnimatedVisibility(
                                                    visible = searchExpanded,
                                                    enter = fadeIn(tween(90, delayMillis = 90)),
                                                    exit = fadeOut(tween(90)),
                                                    modifier = Modifier.weight(1f),
                                                ) {
                                                    BasicTextField(
                                                        value = query,
                                                        onValueChange = { query = it },
                                                        modifier = Modifier.fillMaxWidth().padding(end = 10.dp).focusRequester(searchFocusRequester)
                                                            .semantics { contentDescription = "Search $title" },
                                                        singleLine = true,
                                                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                                                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                                        decorationBox = { innerTextField ->
                                                            Box {
                                                                if (query.isEmpty()) Text("Search", maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                                innerTextField()
                                                            }
                                                        },
                                                    )
                                                }
                                            }
                                        }
                                    }
                                    IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, contentDescription = "Close $title") }
                                }
                            }
                            LazyColumn(
                                modifier = Modifier.weight(1f, fill = false),
                                state = listState,
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                itemsIndexed(filtered, key = { _, option -> option.id }) { index, option ->
                                    Column {
                                        if (index > 0 && option.group != filtered[index - 1].group) {
                                            HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp), color = MaterialTheme.colorScheme.outlineVariant)
                                        }
                                        val selected = option.id == selectedId
                                        Surface(
                                            onClick = { onSelect(option) },
                                            modifier = Modifier.fillMaxWidth(),
                                            shape = RoundedCornerShape(14.dp),
                                            color = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                                        ) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 14.dp, vertical = 8.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(
                                                        option.label,
                                                        color = if (option.destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                                                        maxLines = 2,
                                                        overflow = TextOverflow.Ellipsis,
                                                    )
                                                    option.detail?.let { detail ->
                                                        Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                    }
                                                }
                                                if (selected) Icon(Icons.Outlined.Check, contentDescription = "Selected", tint = MaterialTheme.colorScheme.primary)
                                            }
                                        }
                                    }
                                }
                                if (filtered.isEmpty()) {
                                    item { Text("No matching options", modifier = Modifier.padding(14.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

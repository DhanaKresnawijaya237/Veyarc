package com.veyarc.feature.assistant

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date

@Composable
internal fun AssistantHistoryPanel(state: AssistantPreviewState, onDismiss: () -> Unit) {
    val list = rememberLazyListState()
    var query by remember { mutableStateOf("") }
    var pinnedOnly by remember { mutableStateOf(false) }
    var filtering by remember { mutableStateOf(false) }
    var selecting by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(emptySet<String>()) }
    var renameId by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf(emptySet<String>()) }
    val history = state.history
    val selected = selectedIds.intersect(history.map { it.id }.toSet())
    val visible = history.filter { (!pinnedOnly || it.pinned) && it.title.contains(query.trim(), ignoreCase = true) }
    fun cancelSelection() {
        selecting = false
        selectedIds = emptySet()
    }
    AssistantPanel(
        "Chats",
        onDismiss,
        scrollBody = false,
        bodyState = list,
        onBack = if (selecting) ::cancelSelection else null,
        backDescription = "Cancel selection",
        subtitle = if (selected.isNotEmpty()) "${selected.size} selected" else null,
        headerActions = {
            val focus = LocalFocusManager.current
            val keyboard = LocalSoftwareKeyboardController.current
            AnimatedContent(selecting, label = "history actions") { selectionMode ->
                Row(Modifier.width(96.dp), horizontalArrangement = Arrangement.End) {
                    if (selectionMode) {
                        IconButton(onClick = { deleting = selected }, enabled = state.canChange && selected.isNotEmpty()) {
                            Icon(Icons.Outlined.DeleteOutline, "Delete selected chats")
                        }
                    } else {
                        Box {
                            IconButton(onClick = { filtering = true }) { Icon(Icons.Outlined.Tune, "Filter chats") }
                            DropdownMenu(expanded = filtering, onDismissRequest = { filtering = false }, containerColor = chatPanelColor(raised = true), tonalElevation = 0.dp, shape = RoundedCornerShape(16.dp)) {
                                listOf(false to "All", true to "Pinned").forEach { (onlyPinned, label) ->
                                    val tint = if (pinnedOnly == onlyPinned) Color(0xFF5C9CE0) else MaterialTheme.colorScheme.onSurfaceVariant
                                    DropdownMenuItem(
                                        text = { Text(label, color = if (pinnedOnly == onlyPinned) tint else MaterialTheme.colorScheme.onSurface) },
                                        leadingIcon = { Icon(if (onlyPinned) Icons.Outlined.PushPin else Icons.Outlined.ChatBubbleOutline, null, tint = tint) },
                                        trailingIcon = { if (pinnedOnly == onlyPinned) Icon(Icons.Outlined.Check, null, tint = tint) },
                                        modifier = Modifier.semantics { this.selected = pinnedOnly == onlyPinned },
                                        onClick = {
                                            pinnedOnly = onlyPinned
                                            filtering = false
                                        },
                                    )
                                }
                            }
                        }
                        IconButton(onClick = {
                            focus.clearFocus()
                            keyboard?.hide()
                            selecting = true
                        }, enabled = state.canChange && history.isNotEmpty()) { Icon(Icons.Outlined.Checklist, "Select chats") }
                    }
                }
            }
        },
    ) {
        BackHandler(selecting) { cancelSelection() }
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search") },
                singleLine = true,
                leadingIcon = { Icon(Icons.Outlined.Search, null, Modifier.size(22.dp)) },
                modifier = Modifier.fillMaxWidth().testTag("assistant-history-search"),
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(unfocusedContainerColor = chatPanelColor(raised = true)),
            )
            LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false).testTag("assistant-history-list"), state = list, overscrollEffect = null) {
                if (visible.isEmpty()) item { Text(if (pinnedOnly) "No pinned chats" else "No chats found", Modifier.padding(vertical = 20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                items(visible, key = { it.id }) { entry ->
                    var menuVisible by remember(entry.id) { mutableStateOf(false) }
                    val current = entry.id == state.selectedId
                    Box(Modifier.animateItem().fillMaxWidth()) {
                        Surface(
                            color = if (current || menuVisible || entry.id in selected) chatPanelColor(raised = true) else Color.Transparent,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth().testTag("assistant-history-${entry.id}")
                                .semantics { this.selected = if (selecting) entry.id in selected else current }
                                .combinedClickable(
                                    enabled = state.canChange,
                                    role = if (selecting) Role.Checkbox else Role.Button,
                                    onLongClick = { if (!selecting) menuVisible = true },
                                    onClick = {
                                        if (selecting) {
                                            selectedIds = if (entry.id in selected) selectedIds - entry.id else selectedIds + entry.id
                                        } else {
                                            state.select(entry.id)
                                            onDismiss()
                                        }
                                    },
                                ),
                        ) {
                            Row(Modifier.heightIn(min = 64.dp).padding(horizontal = 10.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                AnimatedVisibility(selecting, enter = expandHorizontally(tween(220), expandFrom = Alignment.Start) + fadeIn(tween(150)), exit = shrinkHorizontally(tween(220), shrinkTowards = Alignment.Start) + fadeOut(tween(100))) {
                                    Checkbox(
                                        entry.id in selected,
                                        onCheckedChange = null,
                                        modifier = Modifier.padding(end = 12.dp).testTag("assistant-history-check-${entry.id}"),
                                        colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.onSurface, checkmarkColor = MaterialTheme.colorScheme.surface, uncheckedColor = MaterialTheme.colorScheme.onSurfaceVariant),
                                    )
                                }
                                Icon(if (entry.pinned) Icons.Outlined.PushPin else Icons.Outlined.RadioButtonUnchecked, null, Modifier.padding(end = 12.dp).size(if (entry.pinned) 16.dp else 8.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(entry.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    val timestamp = remember(entry.updatedAtMs) { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(entry.updatedAtMs)) }
                                    Text(timestamp, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                        Box(Modifier.align(Alignment.TopEnd)) {
                            DropdownMenu(expanded = menuVisible, onDismissRequest = { menuVisible = false }, containerColor = chatPanelColor(raised = true), tonalElevation = 0.dp, shape = RoundedCornerShape(16.dp)) {
                                ChatMenuItem("Rename", Icons.Outlined.Edit, enabled = state.canChange) {
                                    menuVisible = false
                                    renameId = entry.id
                                }
                                ChatMenuItem(if (entry.pinned) "Unpin" else "Pin", Icons.Outlined.PushPin, enabled = state.canChange) {
                                    menuVisible = false
                                    state.togglePin(entry.id)
                                }
                                ChatMenuItem("Delete", Icons.Outlined.DeleteOutline, enabled = state.canChange, destructive = true) {
                                    menuVisible = false
                                    deleting = setOf(entry.id)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    history.firstOrNull { it.id == renameId }?.let { entry ->
        var title by remember(entry.id) { mutableStateOf(entry.title) }
        AlertDialog(
            onDismissRequest = { renameId = null },
            title = { Text("Rename chat") },
            text = { OutlinedTextField(title, onValueChange = { title = it }, label = { Text("Chat title") }, isError = title.length > 80, singleLine = true) },
            confirmButton = {
                TextButton(onClick = {
                    state.rename(title, entry.id)
                    renameId = null
                }, enabled = state.canChange && title.isNotBlank() && title.length <= 80) { Text("Rename") }
            },
            dismissButton = { TextButton(onClick = { renameId = null }) { Text("Cancel") } },
        )
    }
    if (deleting.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { deleting = emptySet() },
            title = { Text(if (deleting.size == 1) "Delete this chat?" else "Delete ${deleting.size} chats?") },
            text = { Text("Their messages will be removed. Your workflow is unchanged.") },
            confirmButton = {
                TextButton(onClick = {
                    state.deleteChats(deleting)
                    deleting = emptySet()
                    cancelSelection()
                }, enabled = state.canChange) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleting = emptySet() }) { Text("Cancel") } },
        )
    }
}

package com.veyarc.feature.assistant

import android.app.Activity
import android.content.ClipData
import android.content.ContextWrapper
import android.content.Intent
import android.text.format.DateUtils
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.CallSplit
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Debug entry is supplied by the app. Network dispatch requires explicit consent; this module has no automation access. */
@Composable
fun AssistantPreviewHost(
    enabled: Boolean,
    state: AssistantPreviewState,
    context: AssistantPreviewContext,
    onOpenSettings: (() -> Unit)? = null,
    editorContent: @Composable (onAskAi: (() -> Unit)?) -> Unit,
) {
    val openChat: (() -> Unit)? = if (enabled) ({ state.open(context) }) else null
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 1000.dp && LocalDensity.current.fontScale <= 1.5f
        if (!enabled || !state.isOpen) {
            editorContent(openChat)
        } else if (wide) {
            Row(Modifier.fillMaxSize().testTag("assistant-split")) {
                Box(Modifier.weight(1f)) { editorContent(openChat) }
                VerticalDivider()
                Box(Modifier.weight(1f)) { AssistantPreviewScreen(state, onOpenSettings) }
            }
        } else {
            AssistantPreviewScreen(state, onOpenSettings)
        }
    }
}

@Composable
fun AssistantPreviewScreen(state: AssistantPreviewState, onOpenSettings: (() -> Unit)? = null) {
    var storageVisible by remember { mutableStateOf(false) }
    var clearVisible by remember { mutableStateOf(false) }
    if (storageVisible) {
        PreviewDialog("Chat storage", onDismiss = { storageVisible = false }) {
            Text(if (state.persistent) "Encrypted on this device. Deleting chats here does not delete DeepSeek records." else "Temporary sample history.")
            Text("20 chats · 40 messages per chat · 4,000 characters per message\n256 KiB per chat · 4 MiB total")
            state.storageError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (state.storageError != null && !state.loadFailed) {
                TextButton(onClick = state::saveAgain, enabled = state.pendingWrites == 0) { Text("Retry saving") }
            }
            if (state.persistent) {
                Text("Reload discards unsaved chat edits. It does not resend messages.")
                TextButton(onClick = {
                    state.reload()
                    storageVisible = false
                }, enabled = !state.isLoading && state.pendingWrites == 0) { Text("Reload saved history") }
            }
            TextButton(onClick = {
                storageVisible = false
                clearVisible = true
            }, enabled = !state.isLoading && state.pendingWrites == 0) { Text("Clear all chat history") }
        }
    }
    if (clearVisible) {
        AlertDialog(
            onDismissRequest = { clearVisible = false },
            title = { Text("Clear all chat history?") },
            text = { Text("Deletes all chats and unsent messages. Keeps your workflows.") },
            confirmButton = {
                TextButton(onClick = {
                    state.clearHistory()
                    clearVisible = false
                }) { Text("Clear history") }
            },
            dismissButton = { TextButton(onClick = { clearVisible = false }) { Text("Cancel") } },
        )
    }
    val chat = state.conversation
    if (chat == null) {
        BackHandler(onBack = state::close)
        Surface(Modifier.fillMaxSize().testTag("assistant-preview")) {
            Column(Modifier.safeDrawingPadding().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                IconButton(onClick = state::close) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back to editor") }
                Text(if (state.isLoading) "Loading encrypted history…" else "No chat selected", style = MaterialTheme.typography.headlineSmall)
                Text(state.storageError ?: if (state.usesProvider) "Start a chat for this workflow." else "Start a sample chat.")
                Button(onClick = state::newChat, enabled = state.canChange && state.conversations.size < AssistantPreviewState.MAX_CHATS) { Text("New chat") }
                TextButton(onClick = { storageVisible = true }) { Text("Chat storage") }
                if (state.conversations.size >= AssistantPreviewState.MAX_CHATS) Text("Chat limit reached. Delete a chat to continue.")
            }
        }
        return
    }
    var historyVisible by remember { mutableStateOf(false) }
    var contextVisible by remember { mutableStateOf(false) }
    var contextCapture by remember { mutableStateOf(AssistantContextCapture()) }
    var capturedTurn by remember { mutableStateOf(false) }
    fun showContext() {
        contextCapture = state.previewContext()
        capturedTurn = false
        contextVisible = true
    }
    var menuVisible by remember { mutableStateOf(false) }
    var moreMenuVisible by remember { mutableStateOf(false) }
    var renameVisible by remember { mutableStateOf(false) }
    var deleteVisible by remember { mutableStateOf(false) }
    var composerSheet by remember { mutableStateOf<String?>(null) }
    val androidContext = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val keyboardVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    LaunchedEffect(keyboardVisible) {
        // Android can consume Back to dismiss the IME before our BackHandler runs.
        // Release the old input connection so the next tap can open it normally.
        if (!keyboardVisible) focus.clearFocus()
    }
    val activity = generateSequence(LocalContext.current) { (it as? ContextWrapper)?.baseContext }
        .filterIsInstance<Activity>().firstOrNull()

    DisposableEffect(state, activity) {
        val previousSoftInputMode = activity?.window?.attributes?.softInputMode
        // Resize delivers IME insets; system bars and keyboard padding are consumed separately below.
        // Never let Android pan the entire chat to keep the composer in view.
        activity?.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        onDispose {
            previousSoftInputMode?.let { activity?.window?.setSoftInputMode(it) }
            if (activity?.isChangingConfigurations != true) state.stop(PreviewReplyStatus.INTERRUPTED)
        }
    }

    fun leave() {
        keyboard?.hide()
        focus.clearFocus()
        state.close()
    }

    BackHandler {
        if (keyboardVisible) {
            keyboard?.hide()
            focus.clearFocus()
        } else {
            leave()
        }
    }
    LaunchedEffect(state.requestId) {
        if (state.usesProvider) return@LaunchedEffect
        val token = state.requestId ?: return@LaunchedEffect
        while (state.requestId == token) {
            delay(180)
            state.advance(token)
        }
    }

    Surface(Modifier.fillMaxSize().testTag("assistant-preview")) {
        BoxWithConstraints(Modifier.windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout)).imePadding()) {
            val shortWindow = maxHeight < 420.dp
            val tightWindow = maxHeight < 240.dp
            Column(Modifier.fillMaxSize()) {
                if (!tightWindow) {
                    Row(Modifier.fillMaxWidth().testTag("assistant-header"), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = ::leave) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back to editor") }
                        Column(Modifier.weight(1f)) {
                            Text(chat.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(if (state.usesProvider) "DeepSeek" else "Sample chat", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = state::newChat, enabled = state.canChange && state.conversations.size < AssistantPreviewState.MAX_CHATS) { Icon(Icons.Outlined.Add, "New chat") }
                        Box {
                            IconButton(onClick = { menuVisible = true }) { Icon(Icons.Outlined.MoreVert, "Chat options") }
                            DropdownMenu(
                                expanded = menuVisible,
                                onDismissRequest = {
                                    menuVisible = false
                                    moreMenuVisible = false
                                },
                                modifier = Modifier.width(272.dp).testTag("assistant-chat-menu"),
                                shape = RoundedCornerShape(24.dp),
                                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                                tonalElevation = 0.dp,
                                shadowElevation = 10.dp,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                            ) {
                                Text(
                                    if (moreMenuVisible) "More options" else chat.title,
                                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (moreMenuVisible) {
                                    ChatMenuItem("Back", Icons.AutoMirrored.Outlined.ArrowBack) { moreMenuVisible = false }
                                    ChatMenuItem("Workflow context", Icons.Outlined.ContentCopy) {
                                        menuVisible = false
                                        moreMenuVisible = false
                                        showContext()
                                    }
                                    ChatMenuItem("Chat storage", Icons.Outlined.History) {
                                        menuVisible = false
                                        moreMenuVisible = false
                                        storageVisible = true
                                    }
                                    if (!state.usesProvider) {
                                        HorizontalDivider(Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                                        PreviewScenario.entries.forEach { scenario ->
                                            DropdownMenuItem(
                                                text = { Text("Sample: ${scenario.label}${if (state.scenario == scenario) " (selected)" else ""}") },
                                                onClick = {
                                                    state.scenario = scenario
                                                    menuVisible = false
                                                    moreMenuVisible = false
                                                },
                                            )
                                        }
                                    }
                                } else {
                                    ChatMenuItem("Share", Icons.Outlined.Share, enabled = chat.turns.isNotEmpty()) {
                                        menuVisible = false
                                        val text = chat.turns.joinToString("\n\n") { "You: ${it.prompt}\n\nAI: ${readableReply(it)}" }
                                        androidContext.startActivity(
                                            Intent.createChooser(
                                                Intent(Intent.ACTION_SEND).apply {
                                                    type = "text/plain"
                                                    putExtra(Intent.EXTRA_TEXT, text)
                                                },
                                                "Share chat",
                                            ),
                                        )
                                    }
                                    ChatMenuItem("Rename chat", Icons.Outlined.Edit, enabled = state.canChange) {
                                        menuVisible = false
                                        renameVisible = true
                                    }
                                    ChatMenuItem(if (chat.pinned) "Unpin" else "Pin", Icons.Outlined.PushPin, enabled = state.canChange) {
                                        menuVisible = false
                                        state.togglePin()
                                    }
                                    ChatMenuItem("Chat history", Icons.Outlined.History) {
                                        menuVisible = false
                                        historyVisible = true
                                    }
                                    HorizontalDivider(Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                                    ChatMenuItem("Delete chat", Icons.Outlined.Delete, enabled = state.canChange, destructive = true) {
                                        menuVisible = false
                                        deleteVisible = true
                                    }
                                    ChatMenuItem("More", Icons.Outlined.ChevronRight) { moreMenuVisible = true }
                                }
                            }
                        }
                    }
                }
                key(chat.id) {
                    ConversationBody(state, onContext = {
                        contextCapture = AssistantContextCapture(it.baseSnapshot)
                        capturedTurn = true
                        contextVisible = true
                    }, modifier = Modifier.weight(1f))
                }
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Surface(shape = RoundedCornerShape(28.dp), color = chatPanelColor(raised = true)) {
                        Column(Modifier.padding(4.dp).testTag("assistant-composer-panel")) {
                            Row(verticalAlignment = Alignment.Bottom) {
                                if (tightWindow) {
                                    IconButton(onClick = ::leave) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back to editor") }
                                }
                                TextField(
                                    value = chat.composer,
                                    onValueChange = state::compose,
                                    enabled = !state.isLoading,
                                    isError = state.inputError != null,
                                    supportingText = if (tightWindow && state.inputError != null) ({ Text(state.inputError!!) }) else null,
                                    placeholder = { Text("Message") },
                                    modifier = Modifier.weight(1f).testTag("assistant-composer"),
                                    maxLines = if (shortWindow) 2 else 4,
                                    colors = TextFieldDefaults.colors(
                                        focusedContainerColor = Color.Transparent,
                                        unfocusedContainerColor = Color.Transparent,
                                        disabledContainerColor = Color.Transparent,
                                        focusedIndicatorColor = Color.Transparent,
                                        unfocusedIndicatorColor = Color.Transparent,
                                        disabledIndicatorColor = Color.Transparent,
                                    ),
                                )
                                FilledIconButton(
                                    modifier = Modifier.padding(bottom = 4.dp),
                                    onClick = {
                                        if (state.isResponding) state.stop() else state.send()
                                    },
                                    enabled = state.isResponding || state.canSend,
                                ) {
                                    if (state.isResponding) Icon(Icons.Outlined.StopCircle, "Stop response") else Icon(Icons.AutoMirrored.Outlined.Send, "Send message")
                                }
                            }
                            if (!tightWindow) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                                        Surface(onClick = { composerSheet = "attachments" }, shape = CircleShape, color = chatControlColor(), modifier = Modifier.size(40.dp)) {
                                            Box(contentAlignment = Alignment.Center) { Icon(Icons.Outlined.Add, "Add attachment", Modifier.size(22.dp)) }
                                        }
                                    }
                                    Surface(onClick = { composerSheet = "model" }, shape = CircleShape, color = chatControlColor(), modifier = Modifier.weight(1f, fill = false)) {
                                        Row(Modifier.heightIn(min = 40.dp).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            Text(if (state.usesProvider) "DeepSeek Flash" else "Sample", modifier = Modifier.weight(1f, fill = false), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            Text("Standard", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (state.usesProvider && !state.providerEnabled && onOpenSettings != null) {
                        TextButton(onClick = {
                            keyboard?.hide()
                            focus.clearFocus()
                            state.close()
                            onOpenSettings()
                        }) { Text("Enable AI in Settings") }
                    }
                    val notice = when {
                        state.storageError != null -> "Chat not saved. Open Chat storage."
                        state.inputError != null -> state.inputError
                        chat.turns.size >= AssistantPreviewState.MAX_TURNS -> "Chat is full. Start a new chat."
                        chat.composer.length == AssistantPreviewState.MAX_PROMPT_LENGTH -> "4,000 character limit reached."
                        state.usesProvider && !state.providerEnabled && onOpenSettings == null -> "Enable AI in Settings to send."
                        else -> null
                    }
                    if (!tightWindow && notice != null) Text(notice, Modifier.padding(start = 12.dp, top = 4.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }

    if (contextVisible) {
        PreviewDialog(if (capturedTurn) "Captured workflow context" else "Workflow context", onDismiss = { contextVisible = false }) {
            Text(if (state.usesProvider) "DeepSeek receives your message, completed chat exchanges, and workflow text." else "Sample only. Nothing is sent.")
            Text(if (capturedTurn) "Snapshot used for this message and retries." else "Send uses your current draft, including unsaved edits.")
            contextCapture.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            val snapshot = contextCapture.snapshot
            if (snapshot == null && contextCapture.error == null) Text("No workflow attached.")
            if (snapshot != null) {
                Text("No image files, recordings, or run history are attached.")
                var expanded by remember(snapshot.snapshotId) { mutableStateOf(false) }
                TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Hide shared data" else "Show shared data") }
                if (expanded) SelectionContainer { Text(snapshot.json, modifier = Modifier.testTag("assistant-context-json"), style = MaterialTheme.typography.bodySmall) }
            }
        }
    }

    if (historyVisible) {
        PreviewDialog("Chats", onDismiss = { historyVisible = false }) {
            Text(state.context.name, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (state.conversations.size >= AssistantPreviewState.MAX_CHATS) Text("Chat limit reached. Delete a chat to continue.")
            val history = state.history
            listOf(true, false).forEach { pinned ->
                val entries = history.filter { it.pinned == pinned }
                if (entries.isNotEmpty()) {
                    Text(if (pinned) "Pinned" else "Recent", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                    Column {
                        entries.forEach { entry ->
                            val current = entry.id == state.selectedId
                            Surface(
                                onClick = {
                                    state.select(entry.id)
                                    historyVisible = false
                                },
                                enabled = state.canChange,
                                color = if (current) chatPanelColor(raised = true) else Color.Transparent,
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier.fillMaxWidth().semantics { selected = current }.testTag("assistant-history-${entry.id}"),
                            ) {
                                Row(Modifier.heightIn(min = 76.dp).padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Icon(if (entry.pinned) Icons.Outlined.PushPin else Icons.Outlined.ChatBubbleOutline, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(entry.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        Text(DateUtils.getRelativeTimeSpanString(entry.updatedAtMs, System.currentTimeMillis(), DateUtils.DAY_IN_MILLIS).toString(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    if (current) Icon(Icons.Outlined.Check, "Current chat", Modifier.size(20.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (renameVisible) {
        var title by remember(chat.id) { mutableStateOf(chat.title) }
        AlertDialog(
            onDismissRequest = { renameVisible = false },
            title = { Text("Rename chat") },
            text = { OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Chat title") }, isError = title.length > 80) },
            confirmButton = {
                TextButton(onClick = {
                    state.rename(title)
                    renameVisible = false
                }, enabled = title.isNotBlank() && title.length <= 80) { Text("Rename") }
            },
            dismissButton = { TextButton(onClick = { renameVisible = false }) { Text("Cancel") } },
        )
    }
    if (deleteVisible) {
        AlertDialog(
            onDismissRequest = { deleteVisible = false },
            title = { Text("Delete this chat?") },
            text = { Text("Its messages will be removed. Your workflow is unchanged.") },
            confirmButton = {
                TextButton(onClick = {
                    state.deleteCurrent()
                    deleteVisible = false
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleteVisible = false }) { Text("Cancel") } },
        )
    }
    composerSheet?.let { sheet -> ComposerOptions(sheet, state.usesProvider, onSelect = { composerSheet = it }, onDismiss = { composerSheet = null }) }
}

@Composable
private fun ChatMenuItem(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    enabled: Boolean = true,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    val color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    DropdownMenuItem(
        text = { Text(label, style = MaterialTheme.typography.bodyLarge, color = if (enabled) color else color.copy(alpha = 0.38f)) },
        leadingIcon = { Icon(icon, null, tint = if (enabled) color.copy(alpha = 0.8f) else color.copy(alpha = 0.38f)) },
        modifier = Modifier.heightIn(min = 56.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp),
        enabled = enabled,
        onClick = onClick,
    )
}

@Composable
private fun ConversationBody(state: AssistantPreviewState, onContext: (PreviewTurn) -> Unit, modifier: Modifier = Modifier) {
    val chat = state.conversation ?: return
    val list = rememberLazyListState()
    var followLatest by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(list) {
        snapshotFlow { list.isScrollInProgress to list.canScrollForward }.collect { (scrolling, below) ->
            if (scrolling) followLatest = !below
        }
    }
    LaunchedEffect(chat.turns.lastOrNull(), chat.turns.size) {
        if (followLatest && chat.turns.isNotEmpty()) list.scrollToItem((list.layoutInfo.totalItemsCount - 1).coerceAtLeast(0))
    }
    Box(modifier.fillMaxWidth()) {
        if (chat.turns.isEmpty()) {
            Column(Modifier.align(Alignment.Center).padding(24.dp).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("How can I help?", style = MaterialTheme.typography.headlineSmall)
                Text("Explain, create or edit a workflow.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (state.context.needsSetup) Text("Choose an app and orientation in the editor first.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val suggestions = if (state.context.needsSetup || state.context.workflowId == null) listOf("Help me create a workflow", "What details do you need?") else listOf("Explain this workflow", "Suggest an improvement", "Describe a change")
                suggestions.forEach { suggestion ->
                    OutlinedButton(onClick = { state.compose(suggestion) }, shape = RoundedCornerShape(24.dp)) { Text(suggestion) }
                }
            }
        }
        if (chat.turns.isNotEmpty()) {
            LazyColumn(state = list, modifier = Modifier.fillMaxSize().testTag("assistant-messages"), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                item { Box(Modifier.heightIn(min = 12.dp)) }
                items(chat.turns, key = { it.id }) { turn ->
                    PreviewTurnContent(state, turn, isLast = turn == chat.turns.last(), onContext = { onContext(turn) })
                }
                item { Box(Modifier.heightIn(min = 32.dp)) }
            }
        }
        if (!followLatest && list.canScrollForward) {
            Button(
                onClick = {
                    followLatest = true
                    scope.launch { list.scrollToItem((list.layoutInfo.totalItemsCount - 1).coerceAtLeast(0)) }
                },
                modifier = Modifier.align(Alignment.BottomCenter).padding(8.dp),
            ) { Text("Jump to latest") }
        }
    }
}

@Composable
private fun PreviewTurnContent(state: AssistantPreviewState, turn: PreviewTurn, isLast: Boolean, onContext: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Surface(Modifier.align(Alignment.End).widthIn(max = 560.dp), shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            MessageText(turn.prompt, "Your message", Modifier.padding(horizontal = 16.dp, vertical = 12.dp), onContext = if (turn.baseSnapshot != null) onContext else null)
        }
        val reply = remember(turn.reply, turn.baseSnapshot) { readableReply(turn) }
        if (turn.reply.isNotEmpty()) MessageText(reply, if (turn.provider) "DeepSeek reply" else "Sample reply", Modifier.fillMaxWidth().testTag("assistant-reply-${turn.id}"), markdown = true, onContext = if (turn.baseSnapshot != null) onContext else null)
        if (turn.status == PreviewReplyStatus.COMPLETE && reply.isNotBlank()) {
            val clipboard = LocalClipboard.current
            val scope = rememberCoroutineScope()
            Row {
                IconButton(onClick = { scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("AI reply", reply))) } }) { Icon(Icons.Outlined.ContentCopy, "Copy reply") }
                IconButton(onClick = { state.branch(turn.id) }, enabled = state.canBranch(turn)) { Icon(Icons.AutoMirrored.Outlined.CallSplit, "Branch to new chat") }
                IconButton(onClick = { state.regenerate(turn.id) }, enabled = state.canRegenerate(turn)) { Icon(Icons.Outlined.Refresh, "Resend message") }
            }
        }
        turn.responseError?.let { Text(it, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = MaterialTheme.colorScheme.error) }
        val status = when (turn.status) {
            PreviewReplyStatus.RESPONDING -> "Responding…"
            PreviewReplyStatus.COMPLETE -> null
            PreviewReplyStatus.STOPPED -> "Stopped"
            PreviewReplyStatus.INTERRUPTED -> "Interrupted"
            PreviewReplyStatus.ERROR -> if (turn.responseError != null) null else "Couldn’t get a reply."
        }
        if (status != null) Text(status, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (turn.proposal != null || (!turn.provider && turn.status == PreviewReplyStatus.COMPLETE && turn.scenario in listOf(PreviewScenario.PROPOSAL, PreviewScenario.STALE))) {
            InlineProposal(state, turn)
        }
        if (isLast && turn.status !in listOf(PreviewReplyStatus.COMPLETE, PreviewReplyStatus.RESPONDING)) {
            TextButton(onClick = state::retry, enabled = state.canRetry) { Text(if (turn.provider) "Retry request" else "Retry sample") }
        }
    }
}

@Composable
private fun MessageText(text: String, speaker: String, modifier: Modifier = Modifier, markdown: Boolean = false, onContext: (() -> Unit)? = null) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var actionsVisible by remember { mutableStateOf(false) }
    var selectionVisible by remember { mutableStateOf(false) }
    Box {
        val messageModifier = modifier.combinedClickable(onClick = {}, onLongClickLabel = "Message actions", onLongClick = { actionsVisible = true }).semantics {
            if (!markdown) contentDescription = "$speaker: $text"
            if (speaker != "Your message") liveRegion = LiveRegionMode.Polite
        }
        if (markdown) AssistantMarkdown(text, messageModifier) else Text(text, messageModifier, style = MaterialTheme.typography.bodyLarge)
        DropdownMenu(expanded = actionsVisible, onDismissRequest = { actionsVisible = false }) {
            DropdownMenuItem(text = { Text("Copy") }, onClick = {
                actionsVisible = false
                scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(speaker, text))) }
            })
            DropdownMenuItem(text = { Text("Select text") }, onClick = {
                actionsVisible = false
                selectionVisible = true
            })
            if (onContext != null) {
                DropdownMenuItem(text = { Text("View captured context") }, onClick = {
                    actionsVisible = false
                    onContext()
                })
            }
        }
    }
    if (selectionVisible) PreviewDialog("Select text", onDismiss = { selectionVisible = false }) { SelectionContainer { Text(text) } }
}

@Composable
private fun PreviewDialog(title: String, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    ChatPanel(title, onDismiss, content = content)
}

@Composable
private fun chatPanelColor(raised: Boolean = false): Color {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    return if (dark) Color(if (raised) 0xFF222222 else 0xFF141414) else Color(if (raised) 0xFFF0EFEB else 0xFFFAF9F6)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatPanel(title: String, onDismiss: () -> Unit, onBack: (() -> Unit)? = null, content: @Composable () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val dark = colors.surface.luminance() < 0.5f
    val density = LocalDensity.current
    MaterialTheme(
        colorScheme = colors.copy(
            surface = chatPanelColor(),
            onSurface = if (dark) Color(0xFFF2F1EE) else Color(0xFF20201E),
            onSurfaceVariant = if (dark) Color(0xFFA3A39B) else Color(0xFF6D6D66),
            primary = if (dark) Color(0xFFF2F1EE) else Color(0xFF20201E),
        ),
    ) {
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            dragHandle = null,
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            containerColor = MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp,
        ) {
            CompositionLocalProvider(LocalDensity provides density) {
                Column(Modifier.fillMaxWidth()) {
                    Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).heightIn(min = 56.dp)) {
                        Text(title, Modifier.align(Alignment.Center).padding(horizontal = 52.dp, vertical = 12.dp), style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        IconButton(onClick = onBack ?: onDismiss, modifier = Modifier.align(Alignment.CenterStart)) {
                            Icon(if (onBack == null) Icons.Outlined.Close else Icons.AutoMirrored.Outlined.ArrowBack, if (onBack == null) "Close $title" else "Back to models")
                        }
                        if (onBack != null) IconButton(onClick = onDismiss, modifier = Modifier.align(Alignment.CenterEnd)) { Icon(Icons.Outlined.Close, "Close $title") }
                    }
                    Column(Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { content() }
                }
            }
        }
    }
}

@Composable
private fun InlineProposal(state: AssistantPreviewState, turn: PreviewTurn) {
    var expanded by remember(turn.id) { mutableStateOf(false) }
    var diagnostics by remember(turn.id) { mutableStateOf(false) }
    val proposal = turn.proposal
    val current = state.previewContext()
    val changes = remember(turn.baseSnapshot, proposal) {
        runCatching { readableChanges(requireNotNull(turn.baseSnapshot), requireNotNull(proposal)) }
    }
    val status = when {
        proposal == null -> if (turn.scenario == PreviewScenario.STALE) AssistantReviewStatus.STALE else AssistantReviewStatus.READY
        proposal.isValid && changes.isFailure -> AssistantReviewStatus.INVALID
        turn.localBindingId != null && turn.localBindingId != current.localBindingId && !turn.proposalDismissed -> AssistantReviewStatus.STALE
        else -> AssistantProposalReview.status(turn.baseSnapshot, current, proposal, turn.proposalDismissed)
    }
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("assistant-review-toggle").clickable { expanded = !expanded }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        when {
                            proposal == null -> "Example change"
                            changes.isSuccess -> "Changes · ${changes.getOrNull()?.size ?: 0}"
                            else -> "Proposed changes"
                        },
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        when (turn.applyState) {
                            ProposalApplyState.NONE -> status.label
                            ProposalApplyState.RESERVED -> "Apply attempt recorded"
                            ProposalApplyState.APPLIED -> "Applied to draft"
                            ProposalApplyState.REJECTED -> "Apply was blocked"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(if (expanded) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown, if (expanded) "Collapse changes" else "Expand changes")
            }
            if (expanded) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    when (turn.applyState) {
                        ProposalApplyState.RESERVED -> Text("Check your draft in the editor before continuing.")

                        ProposalApplyState.APPLIED -> Text("Undo in the editor. Save when ready.")

                        ProposalApplyState.REJECTED -> Text("Nothing applied. Send a new request.")

                        ProposalApplyState.NONE -> when (status) {
                            AssistantReviewStatus.STALE -> Text("Draft changed. Send a new request.", color = MaterialTheme.colorScheme.error)
                            AssistantReviewStatus.INVALID -> Text("These changes couldn't be validated. Ask for a new reply.", color = MaterialTheme.colorScheme.error)
                            AssistantReviewStatus.DISMISSED -> Text("Dismissed. Send a new request for changes.")
                            AssistantReviewStatus.READY -> Unit
                        }
                    }
                    if (proposal != null && proposal.issues.isNotEmpty()) {
                        if (proposal.issues.any { it.startsWith("Candidate changed locked fields:") }) {
                            Text(if (proposal.issues.any { "image catalog" in it }) "AI changed your image list or names. Ask it to keep your images unchanged." else "Change apps or device setup in the editor first.")
                        }
                        TextButton(onClick = { diagnostics = !diagnostics }) { Text(if (diagnostics) "Hide technical details" else "Technical details") }
                        if (diagnostics) proposal.issues.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                    if (state.supportsApply && turn.localBindingId == null && proposal != null) Text("Copied or reopened changes. Send a new message to apply.")
                    SelectionContainer {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (proposal == null) {
                                Text("Wait", style = MaterialTheme.typography.titleSmall)
                                DiffValue("Before", "1 second", false)
                                DiffValue("After", "2 seconds", true)
                            } else {
                                changes.getOrNull()?.forEach { change ->
                                    HorizontalDivider()
                                    Text(change.label, style = MaterialTheme.typography.titleSmall)
                                    change.before?.let { DiffValue("Before", it, false) }
                                    change.after?.let { DiffValue("After", it, true) }
                                }
                            }
                        }
                    }
                    Text(if (state.supportsApply && proposal != null) "Draft only. Save and Run are separate." else "Sample only. Apply is unavailable.", style = MaterialTheme.typography.bodySmall)
                    Text("Device capabilities have not been checked.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { state.applyProposal(turn.id) }, enabled = changes.isSuccess && state.canApply(turn)) { Text("Apply to draft") }
                        if (proposal != null && !turn.proposalDismissed && turn.applyState == ProposalApplyState.NONE) TextButton(onClick = { state.dismissProposal(turn.id) }, enabled = state.canChange) { Text("Dismiss proposal") }
                    }
                }
            }
        }
    }
}

@Composable
private fun DiffValue(label: String, value: String, added: Boolean) {
    val colors = MaterialTheme.colorScheme
    Surface(color = if (added) colors.secondaryContainer else colors.errorContainer, shape = RoundedCornerShape(8.dp)) {
        Text("$label: $value", Modifier.fillMaxWidth().padding(10.dp), style = MaterialTheme.typography.bodyMedium, color = if (added) colors.onSecondaryContainer else colors.onErrorContainer)
    }
}

@Composable
private fun ComposerOptions(sheet: String, provider: Boolean, onSelect: (String) -> Unit, onDismiss: () -> Unit) {
    val title = when (sheet) {
        "attachments" -> "Add to chat"
        "effort" -> "Effort"
        else -> "Select model"
    }
    ChatPanel(title, onDismiss, onBack = if (sheet == "effort") ({ onSelect("model") }) else null) {
        when (sheet) {
            "attachments" -> {
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    if (maxWidth < 300.dp || LocalDensity.current.fontScale > 1.5f) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            PanelOption("Camera", icon = Icons.Outlined.CameraAlt, enabled = false)
                            PanelOption("Photos", icon = Icons.Outlined.Image, enabled = false)
                            PanelOption("Files", icon = Icons.Outlined.UploadFile, enabled = false)
                        }
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AttachmentTile("Camera", Icons.Outlined.CameraAlt, Modifier.weight(1f))
                            AttachmentTile("Photos", Icons.Outlined.Image, Modifier.weight(1f))
                            AttachmentTile("Files", Icons.Outlined.UploadFile, Modifier.weight(1f))
                        }
                    }
                }
                Text("Attachments coming soon", Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            "effort" -> {
                Surface(shape = RoundedCornerShape(18.dp), color = chatPanelColor(raised = true)) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        PanelOption("Standard", subtitle = "Current setting", selected = true)
                        listOf("Low", "Medium", "High", "Extra", "Max").forEach { PanelOption(it, enabled = false) }
                    }
                }
            }

            else -> {
                PanelOption(if (provider) "DeepSeek Flash" else "Sample chat", subtitle = if (provider) "Fast responses" else "Offline preview", selected = true)
                PanelOption("Effort", value = "Standard", onClick = { onSelect("effort") })
                PanelOption("More models", enabled = false)
            }
        }
    }
}

@Composable
private fun PanelOption(
    label: String,
    subtitle: String? = null,
    value: String? = null,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    selected: Boolean = false,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    val modifier = Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(enabled = enabled, onClick = onClick) else Modifier)
        .semantics(mergeDescendants = true) {
            if (!enabled) disabled()
            this.selected = selected
        }
    Surface(shape = RoundedCornerShape(18.dp), color = chatPanelColor(raised = true), modifier = modifier) {
        Row(Modifier.heightIn(min = 56.dp).padding(horizontal = 14.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (icon != null) Icon(icon, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(label, style = MaterialTheme.typography.bodyLarge)
                if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!enabled) Text("Soon", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (value != null) Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (selected) Icon(Icons.Outlined.Check, "Selected", Modifier.size(24.dp), tint = Color(0xFF66A8EF))
            if (onClick != null) Icon(Icons.Outlined.ChevronRight, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun AttachmentTile(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier) {
    Surface(shape = RoundedCornerShape(18.dp), color = chatPanelColor(raised = true), modifier = modifier.semantics(mergeDescendants = true) { disabled() }) {
        Column(Modifier.heightIn(min = 108.dp).padding(vertical = 16.dp, horizontal = 8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Surface(shape = CircleShape, color = chatControlColor()) {
                Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) { Icon(icon, null, Modifier.size(24.dp)) }
            }
            Text(label, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun chatControlColor(): Color = if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) Color(0xFF333333) else Color(0xFFE4E3DF)

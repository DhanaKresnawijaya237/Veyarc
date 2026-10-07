package com.veyarc.feature.assistant

import android.app.Activity
import android.content.ClipData
import android.content.ContextWrapper
import android.content.Intent
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.CallSplit
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowUpward
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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
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
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
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
    var clearWorkflowOnly by remember(state.context.workflowId) { mutableStateOf(state.context.workflowId != null) }
    if (storageVisible) {
        PreviewDialog("Chat storage", onDismiss = { storageVisible = false }) {
            Text(if (state.persistent) "Encrypted on this device. Deleting chats here does not delete provider records." else "Temporary sample history.")
            state.storageError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (state.storageError != null && !state.loadFailed) {
                TextButton(onClick = state::saveAgain, enabled = state.pendingWrites == 0, modifier = Modifier.fillMaxWidth()) { Text("Retry saving") }
            }
            if (state.persistent) {
                Text("Reload discards unsaved chat edits. It does not resend messages.")
                TextButton(onClick = {
                    state.reload()
                    storageVisible = false
                }, enabled = !state.isLoading && state.pendingWrites == 0, modifier = Modifier.fillMaxWidth()) { Text("Reload saved history") }
            }
            TextButton(onClick = {
                storageVisible = false
                clearWorkflowOnly = state.context.workflowId != null && !state.loadFailed
                clearVisible = true
            }, enabled = !state.isLoading && state.pendingWrites == 0, modifier = Modifier.fillMaxWidth()) { Text("Clear chat history") }
        }
    }
    if (clearVisible) {
        AlertDialog(
            onDismissRequest = { clearVisible = false },
            title = { Text("Clear chat history") },
            shape = RoundedCornerShape(24.dp),
            containerColor = chatPanelColor(raised = true),
            text = {
                Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(true to "This workflow", false to "All chats").forEach { (workflowOnly, label) ->
                        val enabled = !workflowOnly || (state.context.workflowId != null && !state.loadFailed)
                        SettingsChoiceTile(label, if (workflowOnly) Icons.Outlined.Edit else Icons.Outlined.History, clearWorkflowOnly == workflowOnly, { clearWorkflowOnly = workflowOnly }, Modifier.weight(1f), enabled)
                    }
                }
            },
            confirmButton = {
                FilledTonalButton(colors = ButtonDefaults.filledTonalButtonColors(containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer), onClick = {
                    state.clearHistory(workflowOnly = clearWorkflowOnly)
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
                Button(onClick = state::newChat, enabled = state.canChange) { Text("New chat") }
                TextButton(onClick = { storageVisible = true }) { Text("Chat storage") }
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
    val pickAttachment = rememberAttachmentPicker(state)
    var attachmentPreviewId by rememberSaveable { mutableStateOf<String?>(null) }
    val attachmentPreview = state.conversation?.composerAttachments?.firstOrNull { it.id == attachmentPreviewId }
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
                        Text(chat.title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        IconButton(onClick = state::newChat, enabled = state.canChange) { Icon(Icons.Outlined.Add, "New chat") }
                        Box {
                            IconButton(onClick = {
                                moreMenuVisible = false
                                menuVisible = true
                            }) { Icon(Icons.Outlined.MoreVert, "Chat options") }
                            DropdownMenu(
                                expanded = menuVisible,
                                onDismissRequest = {
                                    menuVisible = false
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
                                        showContext()
                                    }
                                    ChatMenuItem("Chat storage", Icons.Outlined.History) {
                                        menuVisible = false
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
                Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                    Surface(shape = RoundedCornerShape(28.dp), color = chatPanelColor(raised = true)) {
                        Column(Modifier.padding(4.dp).testTag("assistant-composer-panel")) {
                            AttachmentStrip(chat.composerAttachments, state.attachments, {
                                attachmentPreviewId = it.id
                                keyboard?.hide()
                                focus.clearFocus()
                            }, if (state.canChange && !state.importingAttachments) state::removeAttachment else null, compact = shortWindow)
                            if (state.importingAttachments) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 12.dp).testTag("attachment-import-progress"))
                            Row(verticalAlignment = Alignment.Bottom) {
                                TextField(
                                    value = chat.composer,
                                    onValueChange = state::compose,
                                    enabled = !state.isLoading,
                                    isError = state.inputError != null,
                                    supportingText = if (tightWindow && state.inputError != null) ({ Text(state.inputError!!) }) else null,
                                    placeholder = { Text("Message") },
                                    modifier = Modifier.weight(1f).testTag("assistant-composer"),
                                    maxLines = if (tightWindow) {
                                        1
                                    } else if (shortWindow) {
                                        2
                                    } else {
                                        4
                                    },
                                    colors = TextFieldDefaults.colors(
                                        focusedContainerColor = Color.Transparent,
                                        unfocusedContainerColor = Color.Transparent,
                                        disabledContainerColor = Color.Transparent,
                                        focusedIndicatorColor = Color.Transparent,
                                        unfocusedIndicatorColor = Color.Transparent,
                                        disabledIndicatorColor = Color.Transparent,
                                    ),
                                )
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (tightWindow) {
                                    IconButton(onClick = ::leave) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back to editor") }
                                }
                                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                                    Surface(onClick = { composerSheet = "attachments" }, shape = CircleShape, color = chatControlColor(), modifier = Modifier.size(40.dp)) {
                                        Box(contentAlignment = Alignment.Center) { Icon(Icons.Outlined.Add, "Add attachment", Modifier.size(22.dp)) }
                                    }
                                }
                                Box(Modifier.weight(1f)) {
                                    Surface(onClick = { composerSheet = "model" }, shape = CircleShape, color = chatControlColor(), modifier = Modifier.testTag("assistant-model-picker")) {
                                        Row(Modifier.heightIn(min = 40.dp).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            Text(if (state.usesProvider) state.activeModelLabel else "Sample", modifier = Modifier.weight(1f, fill = false), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            Text(chat.effort.label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                                        }
                                    }
                                }
                                ComposerSendButton(state)
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
                        state.usesProvider && state.activeModel == null -> "Choose another model to continue."
                        state.usesProvider && chat.effort !in state.availableEfforts -> "Choose an effort supported by this model."
                        state.usesProvider && state.activeModel?.vision != true && chat.composerAttachments.any { it.needsVision } -> "Choose a model with image support to send images or PDFs."
                        state.storageError != null -> "Chat not saved. Open Chat storage."
                        state.inputError != null -> state.inputError
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
            Text(if (state.usesProvider) "Your chosen provider receives your message, recent completed exchanges, and workflow text." else "Sample only. Nothing is sent.")
            if (state.usesProvider) Text("Older messages stay saved when a long chat exceeds the request budget.")
            Text(if (capturedTurn) "Snapshot used for this message and retries." else "Send uses your current draft, including unsaved edits.")
            contextCapture.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            val snapshot = contextCapture.snapshot
            if (snapshot == null && contextCapture.error == null) Text("No workflow attached.")
            if (snapshot != null) {
                Text("Only files you add to the message are sent. Recordings and run history stay on this device.")
                var expanded by remember(snapshot.snapshotId) { mutableStateOf(false) }
                TextButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth()) { Text(if (expanded) "Hide shared data" else "Show shared data") }
                if (expanded) SelectionContainer { Text(snapshot.json, modifier = Modifier.testTag("assistant-context-json"), style = MaterialTheme.typography.bodySmall) }
            }
        }
    }

    if (historyVisible) {
        AssistantHistoryPanel(state, onDismiss = { historyVisible = false })
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
    attachmentPreview?.let { AttachmentPreview(it, state, editable = true, onDismiss = { attachmentPreviewId = null }) }
    composerSheet?.let { sheet ->
        ComposerOptions(sheet, state, onSelect = { composerSheet = it }, onDismiss = { composerSheet = null }, onAttachment = {
            keyboard?.hide()
            focus.clearFocus()
            composerSheet = null
            pickAttachment(it)
        })
    }
}

@Composable
internal fun ChatMenuItem(
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
    // The body is keyed by chat ID. Start at its footer so a fork never flashes the
    // first exchange while waiting for the follow-latest effect's next frame.
    val list = rememberLazyListState(initialFirstVisibleItemIndex = chat.turns.size)
    var followLatest by rememberFollowing(list)
    val scope = rememberCoroutineScope()
    val last = chat.turns.lastOrNull()
    LaunchedEffect(chat.turns.size, last?.reply, last?.status, last?.thinking != null) {
        withFrameNanos { }
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
            LazyColumn(state = list, modifier = Modifier.fillMaxSize().testTag("assistant-messages"), verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.Bottom)) {
                items(chat.turns, key = { it.id }) { turn ->
                    PreviewTurnContent(state, turn, isLast = turn == chat.turns.last(), followLatest = turn == chat.turns.last() && followLatest, onContext = { onContext(turn) })
                }
                item { Box(Modifier.heightIn(min = 2.dp)) }
            }
        }
        androidx.compose.animation.AnimatedVisibility(
            visible = !followLatest && list.canScrollForward,
            modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
            enter = androidx.compose.animation.fadeIn(tween(100)) + androidx.compose.animation.slideInVertically(androidx.compose.animation.core.spring(dampingRatio = 0.65f, stiffness = 400f)) { it / 4 },
            exit = androidx.compose.animation.fadeOut(tween(90)),
        ) {
            FilledIconButton(
                onClick = {
                    scope.launch {
                        list.animateScrollToItem((list.layoutInfo.totalItemsCount - 1).coerceAtLeast(0))
                        followLatest = true
                    }
                },
                modifier = Modifier.size(44.dp),
                colors = androidx.compose.material3.IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer),
            ) { Icon(Icons.Outlined.KeyboardArrowDown, "Jump to latest", Modifier.size(24.dp)) }
        }
    }
}

/** Only an actual drag changes follow mode; programmatic scrolling must not fight itself. */
@Composable
private fun rememberFollowing(list: LazyListState): MutableState<Boolean> {
    val following = remember(list) { mutableStateOf(true) }
    var hasDragged by remember(list) { mutableStateOf(false) }
    val dragging by list.interactionSource.collectIsDraggedAsState()
    LaunchedEffect(dragging) {
        if (dragging) {
            hasDragged = true
            following.value = false
        } else if (hasDragged) {
            snapshotFlow { list.isScrollInProgress }.first { !it }
            following.value = !list.canScrollForward
        }
    }
    return following
}

/** Consume the child's unused vertical drag/fling, instead of moving its enclosing chat or sheet. */
private object IsolatedScroll : NestedScrollConnection {
    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource) = Offset(0f, available.y)
    override suspend fun onPostFling(consumed: Velocity, available: Velocity) = Velocity(0f, available.y)
}

@Composable
private fun PreviewTurnContent(state: AssistantPreviewState, turn: PreviewTurn, isLast: Boolean, followLatest: Boolean, onContext: () -> Unit) {
    var attachmentPreviewId by rememberSaveable(turn.id) { mutableStateOf<String?>(null) }
    val attachmentPreview = turn.attachments.firstOrNull { it.id == attachmentPreviewId }
    attachmentPreview?.let { AttachmentPreview(it, state, editable = false, onDismiss = { attachmentPreviewId = null }) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (turn.attachments.isNotEmpty()) AttachmentStrip(turn.attachments, state.attachments, { attachmentPreviewId = it.id }, alignEnd = true)
        Surface(Modifier.align(Alignment.End).widthIn(max = 560.dp), shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            MessageText(turn.prompt, "Your message", Modifier.padding(horizontal = 16.dp, vertical = 12.dp), onContext = if (turn.baseSnapshot != null) onContext else null)
        }
        val reply = remember(turn.reply, turn.baseSnapshot) { readableReply(turn) }
        ThinkingDisclosure(turn, followLatest)
        if (turn.reply.isNotEmpty()) MessageText(reply, if (turn.provider) "DeepSeek reply" else "Sample reply", Modifier.fillMaxWidth().testTag("assistant-reply-${turn.id}"), markdown = true, onContext = if (turn.baseSnapshot != null) onContext else null)
        if (turn.status == PreviewReplyStatus.COMPLETE && reply.isNotBlank()) {
            val clipboard = LocalClipboard.current
            val scope = rememberCoroutineScope()
            Row(Modifier.testTag("assistant-reply-actions-${turn.id}")) {
                IconButton(onClick = { scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("AI reply", reply))) } }) { Icon(Icons.Outlined.ContentCopy, "Copy reply", Modifier.size(18.dp)) }
                IconButton(onClick = { state.branch(turn.id) }, enabled = state.canBranch(turn)) { Icon(Icons.AutoMirrored.Outlined.CallSplit, "Branch to new chat", Modifier.size(18.dp)) }
                IconButton(onClick = { state.regenerate(turn.id) }, enabled = state.canRegenerate(turn)) { Icon(Icons.Outlined.Refresh, "Resend message", Modifier.size(18.dp)) }
            }
        }
        turn.responseError?.let { Text(it, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = MaterialTheme.colorScheme.error) }
        val status = when (turn.status) {
            PreviewReplyStatus.RESPONDING -> if (turn.provider && turn.effort != DeepSeekEffort.OFF && turn.thinking?.finished != true) {
                if (turn.thinking == null) "Thinking…" else null
            } else {
                "Responding…"
            }

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
private fun ThinkingDisclosure(turn: PreviewTurn, followChat: Boolean) {
    val thinking = turn.thinking ?: return
    var expanded by remember(turn.id, turn.requestId) { mutableStateOf(false) }
    val list = rememberLazyListState()
    var following by rememberFollowing(list)
    val scope = rememberCoroutineScope()
    val tail = remember { BringIntoViewRequester() }
    val lines = remember(thinking.text) { thinking.text.lines() }
    LaunchedEffect(thinking.text, expanded, following, followChat) {
        if (expanded && following && turn.status == PreviewReplyStatus.RESPONDING) {
            withFrameNanos { }
            list.scrollToItem(lines.size)
            // Opening or growing the box can put its tail below the outer chat viewport.
            // Follow that line only while both views are following; user gestures stay isolated.
            withFrameNanos { }
            if (followChat) tail.bringIntoView()
        }
    }
    val inProgress = turn.status == PreviewReplyStatus.RESPONDING && !thinking.finished
    val label = when {
        thinking.summary && turn.status in listOf(PreviewReplyStatus.STOPPED, PreviewReplyStatus.INTERRUPTED, PreviewReplyStatus.ERROR) -> "Partial reasoning summary"
        thinking.summary -> if (inProgress) "Reasoning summary…" else "Reasoning summary"
        inProgress -> "Thinking…"
        turn.status in listOf(PreviewReplyStatus.STOPPED, PreviewReplyStatus.INTERRUPTED, PreviewReplyStatus.ERROR) -> "Partial thoughts"
        else -> "Thoughts"
    }
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    val jumpLabel = if (thinking.summary) "Jump to latest summary" else "Jump to latest thoughts"
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .testTag("assistant-thinking-toggle-${turn.id}")
                .clickable(onClickLabel = if (expanded) "Hide $label" else "Show $label") { expanded = !expanded }
                .semantics { liveRegion = LiveRegionMode.Polite },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (inProgress) CircularProgressIndicator(Modifier.size(14.dp), color = color, strokeWidth = 1.5.dp)
            Text(label, style = MaterialTheme.typography.labelLarge, color = color)
            Icon(if (expanded) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown, if (expanded) "Hide $label" else "Show $label", Modifier.size(18.dp), tint = color)
        }
        if (expanded) {
            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                Box(Modifier.fillMaxWidth().heightIn(max = 180.dp).nestedScroll(IsolatedScroll)) {
                    SelectionContainer {
                        LazyColumn(state = list, modifier = Modifier.fillMaxWidth().testTag("assistant-thinking-text-${turn.id}"), contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp)) {
                            items(lines.size) { index -> Text(lines[index], style = MaterialTheme.typography.bodyMedium, color = color) }
                            item {
                                Box(Modifier.fillMaxWidth().heightIn(min = 2.dp).bringIntoViewRequester(tail)) {
                                    if (thinking.truncated) Text("This older entry was shortened.", style = MaterialTheme.typography.labelSmall, color = color)
                                }
                            }
                        }
                    }
                    if (!following && turn.status == PreviewReplyStatus.RESPONDING) {
                        IconButton(onClick = {
                            following = true
                            scope.launch { list.scrollToItem(lines.size) }
                        }, modifier = Modifier.align(Alignment.BottomEnd)) { Icon(Icons.Outlined.KeyboardArrowDown, jumpLabel) }
                    }
                }
            }
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
    AssistantPanel(title, onDismiss, content = content)
}

@Composable
internal fun chatPanelColor(raised: Boolean = false): Color {
    val colors = MaterialTheme.colorScheme
    return if (raised) colors.surfaceContainerHigh else colors.surfaceContainerLow
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssistantPanel(title: String, onDismiss: () -> Unit, onBack: (() -> Unit)? = null, scrollBody: Boolean = true, bodyState: ScrollableState? = null, backDescription: String = "Back to models", subtitle: String? = null, headerActions: (@Composable RowScope.() -> Unit)? = null, content: @Composable () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val dark = colors.surface.luminance() < 0.5f
    val density = LocalDensity.current
    val scroll = rememberScrollState()
    val scrolling = bodyState ?: scroll
    val routing = remember(scrolling) { PanelScrollConnection() }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val dismiss by rememberUpdatedState(onDismiss)
    var headerHeight by remember { mutableIntStateOf(0) }
    var closing by remember { mutableStateOf(false) }
    MaterialTheme(
        colorScheme = colors.copy(
            surface = colors.surfaceContainerLow,
            onSurface = if (dark) Color(0xFFF2F1EE) else Color(0xFF20201E),
            onSurfaceVariant = if (dark) Color(0xFFA3A39B) else Color(0xFF6D6D66),
        ),
    ) {
        ModalBottomSheet(
            modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top)),
            onDismissRequest = onDismiss,
            sheetState = sheetState,
            sheetGesturesEnabled = !closing,
            // Keep the sheet's measured height independent of its changing drag offset.
            contentWindowInsets = { WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom) },
            dragHandle = null,
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            containerColor = colors.surfaceContainerLow,
            tonalElevation = 0.dp,
            properties = ModalBottomSheetProperties(isAppearanceLightStatusBars = !dark, isAppearanceLightNavigationBars = !dark),
        ) {
            CompositionLocalProvider(LocalDensity provides density) {
                Column(
                    Modifier.fillMaxWidth().testTag("assistant-panel").pointerInput(sheetState, routing) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                            routing.pullSheet = !scrolling.canScrollBackward
                            val fromHeader = down.position.y <= headerHeight
                            val tracker = VelocityTracker()

                            // Sheet-local pointer positions move with the sheet; measure velocity in the window.
                            fun windowPosition(position: Offset) = position + Offset(0f, sheetState.requireOffset())
                            val start = windowPosition(down.position)
                            tracker.addPosition(down.uptimeMillis, start)
                            var last = start
                            var released = false
                            do {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                last = windowPosition(change.position)
                                tracker.addPosition(change.uptimeMillis, last)
                                released = !change.pressed
                            } while (!released)
                            val distance = last - start
                            val clearPull = distance.y > viewConfiguration.touchSlop && distance.y > kotlin.math.abs(distance.x)
                            val dismissPull = distance.y >= 56.dp.toPx() || tracker.calculateVelocity().y >= 125.dp.toPx()
                            if (released && !closing && (fromHeader || routing.pullSheet) && clearPull && dismissPull) {
                                closing = true
                                scope.launch {
                                    // Finish after the native drag/fling callbacks, so they cannot cancel closing.
                                    withFrameNanos { }
                                    sheetState.hide()
                                    if (!sheetState.isVisible) dismiss()
                                }
                            }
                        }
                    },
                ) {
                    Box(Modifier.fillMaxWidth().onSizeChanged { headerHeight = it.height }.testTag("assistant-panel-header").padding(horizontal = 12.dp, vertical = 4.dp).heightIn(min = 56.dp)) {
                        Column(Modifier.align(Alignment.Center).padding(horizontal = if (headerActions == null) 52.dp else 96.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = onBack ?: onDismiss, modifier = Modifier.align(Alignment.CenterStart)) {
                            Icon(if (onBack == null) Icons.Outlined.Close else Icons.AutoMirrored.Outlined.ArrowBack, if (onBack == null) "Close $title" else backDescription)
                        }
                        if (headerActions != null) {
                            Row(Modifier.align(Alignment.CenterEnd), content = headerActions)
                        } else if (onBack != null) {
                            IconButton(onClick = onDismiss, modifier = Modifier.align(Alignment.CenterEnd)) { Icon(Icons.Outlined.Close, "Close $title") }
                        }
                    }
                    Column(
                        Modifier.fillMaxWidth().weight(1f, fill = false).testTag("assistant-panel-scroll")
                            .nestedScroll(routing).then(if (scrollBody) Modifier.verticalScroll(scroll, overscrollEffect = null) else Modifier).padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) { content() }
                }
            }
        }
    }
}

/** A drag that scrolls content keeps ownership until release, including any leftover fling. */
private class PanelScrollConnection : NestedScrollConnection {
    var pullSheet = false

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
        if (consumed.y != 0f) pullSheet = false
        return if (pullSheet && available.y > 0f && source == NestedScrollSource.UserInput) Offset.Zero else Offset(0f, available.y)
    }

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity = if (pullSheet && available.y > 0f) Velocity.Zero else Velocity(0f, available.y)
}

@Composable
private fun InlineProposal(state: AssistantPreviewState, turn: PreviewTurn) {
    var expanded by remember(turn.id) { mutableStateOf(false) }
    val tail = remember { BringIntoViewRequester() }
    var height by remember { mutableIntStateOf(0) }
    LaunchedEffect(expanded, height) {
        if (expanded) {
            withFrameNanos { }
            tail.bringIntoView()
        }
    }
    var diagnostics by remember(turn.id) { mutableStateOf(false) }
    var allChangesVisible by remember(turn.id) { mutableStateOf(false) }
    val proposal = turn.proposal
    val current = state.previewContext()
    val changes = remember(turn.baseSnapshot, proposal) {
        runCatching { readableChanges(requireNotNull(turn.baseSnapshot), requireNotNull(proposal)) }
    }
    val values = changes.getOrNull().orEmpty()
    val compact = values.size > 3 || values.any { change ->
        listOfNotNull(change.before, change.after).any { it.length > 160 || it.lines().size > 3 }
    }
    val previewCount = if (LocalDensity.current.fontScale > 1.5f) 1 else 2
    val status = when {
        proposal == null -> if (turn.scenario == PreviewScenario.STALE) AssistantReviewStatus.STALE else AssistantReviewStatus.READY
        proposal.isValid && changes.isFailure -> AssistantReviewStatus.INVALID
        turn.localBindingId != null && turn.localBindingId != current.localBindingId && !turn.proposalDismissed -> AssistantReviewStatus.STALE
        else -> AssistantProposalReview.status(turn.baseSnapshot, current, proposal, turn.proposalDismissed)
    }
    val reviewLabel = when (turn.applyState) {
        ProposalApplyState.NONE -> status.label
        ProposalApplyState.RESERVED -> if (state.isApplyingImages) "Adding images…" else "Apply attempt recorded"
        ProposalApplyState.APPLIED -> "Applied to draft"
        ProposalApplyState.REJECTED -> "Apply was blocked"
    }
    val colors = MaterialTheme.colorScheme
    val dark = colors.surface.luminance() < 0.5f
    val highlight = when {
        turn.applyState == ProposalApplyState.APPLIED -> if (dark) Color(0xFF82CEAC) else Color(0xFF276949)
        turn.applyState == ProposalApplyState.REJECTED || status == AssistantReviewStatus.INVALID -> colors.error
        turn.applyState == ProposalApplyState.RESERVED -> colors.secondary
        status == AssistantReviewStatus.DISMISSED -> colors.onSurfaceVariant
        status == AssistantReviewStatus.STALE -> if (dark) Color(0xFFE9BB75) else Color(0xFF805900)
        else -> colors.primary
    }
    if (allChangesVisible) {
        AssistantPanel("All changes", onDismiss = { allChangesVisible = false }) {
            Text("${values.size} changes · $reviewLabel", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ProposalImageStrip(state, turn)
            SelectionContainer { ProposalChanges(values, modifier = Modifier.testTag("assistant-all-changes")) }
        }
    }
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.onSizeChanged { height = it.height }) {
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("assistant-review-toggle").semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" }.clickable { expanded = !expanded }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    when {
                        proposal == null -> "Example change"
                        changes.isSuccess -> "Changes · ${changes.getOrNull()?.size ?: 0}"
                        else -> "Proposed changes"
                    },
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                )
                Surface(shape = RoundedCornerShape(8.dp), color = highlight.copy(alpha = 0.14f)) {
                    Text(reviewLabel, Modifier.padding(horizontal = 8.dp, vertical = 3.dp), style = MaterialTheme.typography.labelSmall, color = highlight)
                }
            }
            AnimatedVisibility(expanded, enter = expandVertically(tween(140), expandFrom = Alignment.Top) + fadeIn(tween(90)), exit = shrinkVertically(tween(120), shrinkTowards = Alignment.Top) + fadeOut(tween(70))) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    when (turn.applyState) {
                        ProposalApplyState.RESERVED -> if (state.isApplyingImages) LinearProgressIndicator(Modifier.fillMaxWidth()) else Text("Check your draft in the editor before continuing.")

                        ProposalApplyState.APPLIED -> Unit

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
                    if (proposal == null) {
                        SelectionContainer {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Wait", style = MaterialTheme.typography.titleSmall)
                                DiffValue("Before", "1 second", false)
                                DiffValue("After", "2 seconds", true)
                            }
                        }
                    } else {
                        ProposalImageStrip(state, turn)
                        Box(Modifier.fillMaxWidth().then(if (compact) Modifier.heightIn(max = 220.dp).clipToBounds() else Modifier).testTag("assistant-changes-preview")) {
                            SelectionContainer { ProposalChanges(if (compact) values.take(previewCount) else values, compact) }
                        }
                        if (compact) OutlinedButton(onClick = { allChangesVisible = true }, modifier = Modifier.fillMaxWidth()) { Text("View all changes") }
                    }
                    Text(if (state.supportsApply && proposal != null) "Draft only. Save and Run are separate." else "Sample only. Apply is unavailable.", style = MaterialTheme.typography.bodySmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { state.applyProposal(turn.id) }, enabled = changes.isSuccess && state.canApply(turn)) { Text(if (compact) "Apply all changes" else "Apply to draft") }
                        if (proposal != null && !turn.proposalDismissed && turn.applyState == ProposalApplyState.NONE) TextButton(onClick = { state.dismissProposal(turn.id) }, enabled = state.canChange) { Text("Dismiss proposal") }
                    }
                }
            }
            Box(Modifier.size(1.dp).bringIntoViewRequester(tail))
        }
    }
}

@Composable
private fun ProposalChanges(changes: List<AssistantValueChange>, compact: Boolean = false, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        changes.forEach { change ->
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                HorizontalDivider()
                Text(change.label, style = MaterialTheme.typography.titleSmall, maxLines = if (compact) 1 else Int.MAX_VALUE, overflow = TextOverflow.Ellipsis)
                change.before?.let { DiffValue("Before", it, false, compact) }
                change.after?.let { DiffValue("After", it, true, compact) }
            }
        }
    }
}

@Composable
private fun DiffValue(label: String, value: String, added: Boolean, compact: Boolean = false) {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val background = if (added) Color(if (dark) 0xFF173326 else 0xFFE5F2E9) else Color(if (dark) 0xFF381E22 else 0xFFFBE9E8)
    val foreground = if (added) Color(if (dark) 0xFFD4EDDD else 0xFF173F25) else Color(if (dark) 0xFFF4D4D4 else 0xFF603232)
    Surface(color = background, shape = RoundedCornerShape(8.dp)) {
        Text("$label: $value", Modifier.fillMaxWidth().padding(if (compact) 8.dp else 10.dp), style = if (compact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium, color = foreground, maxLines = if (compact) 2 else Int.MAX_VALUE, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ComposerOptions(sheet: String, state: AssistantPreviewState, onSelect: (String) -> Unit, onDismiss: () -> Unit, onAttachment: (String) -> Unit) {
    var modelSearch by remember { mutableStateOf("") }
    val modelList = rememberLazyListState()
    val smallModelList = !state.usesProvider || state.connections.connections.sumOf { it.models.size } <= 2
    val title = when (sheet) {
        "attachments" -> "Add to chat"
        "effort" -> "Effort"
        else -> "Select model"
    }
    AssistantPanel(title, onDismiss, onBack = if (sheet == "effort") ({ onSelect("model") }) else null, scrollBody = sheet != "model" || smallModelList, bodyState = if (sheet == "model" && !smallModelList) modelList else null) {
        when (sheet) {
            "attachments" -> {
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    if (maxWidth < 300.dp || LocalDensity.current.fontScale > 1.5f) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            PanelOption("Camera", icon = Icons.Outlined.CameraAlt, enabled = (!state.usesProvider || state.activeModel?.vision == true) && state.attachments != null && state.canChange && !state.importingAttachments, disabledLabel = null, onClick = { onAttachment("Camera") })
                            PanelOption("Photos", icon = Icons.Outlined.Image, enabled = (!state.usesProvider || state.activeModel?.vision == true) && state.attachments != null && state.canChange && !state.importingAttachments, disabledLabel = null, onClick = { onAttachment("Photos") })
                            PanelOption("Files", icon = Icons.Outlined.UploadFile, enabled = state.attachments != null && state.canChange && !state.importingAttachments, disabledLabel = null, onClick = { onAttachment("Files") })
                        }
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AttachmentTile("Camera", Icons.Outlined.CameraAlt, Modifier.weight(1f), (!state.usesProvider || state.activeModel?.vision == true) && state.attachments != null && state.canChange && !state.importingAttachments) { onAttachment("Camera") }
                            AttachmentTile("Photos", Icons.Outlined.Image, Modifier.weight(1f), (!state.usesProvider || state.activeModel?.vision == true) && state.attachments != null && state.canChange && !state.importingAttachments) { onAttachment("Photos") }
                            AttachmentTile("Files", Icons.Outlined.UploadFile, Modifier.weight(1f), state.attachments != null && state.canChange && !state.importingAttachments) { onAttachment("Files") }
                        }
                    }
                }
                Text(if (state.usesProvider && state.activeModel?.vision != true) "This model supports text files. Choose an image model for photos or PDFs." else "Images, PDF, DOCX and text", Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            "effort" -> {
                Surface(shape = RoundedCornerShape(18.dp), color = chatPanelColor(raised = true)) {
                    Column(Modifier.testTag("assistant-effort-options"), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        state.availableEfforts.forEach { effort ->
                            PanelOption(effort.label, selected = state.conversation?.effort == effort, enabled = state.usesProvider && state.canChange && !state.isResponding, disabledLabel = null, opensPanel = false, onClick = {
                                state.setEffort(effort)
                                onDismiss()
                            })
                        }
                    }
                }
                Text(if (state.usesProvider) "More thinking takes longer and uses more tokens." else "Available with an AI connection.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            else -> {
                Column(Modifier.fillMaxWidth().then(if (smallModelList) Modifier else Modifier.heightIn(max = 520.dp)), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.usesProvider && state.connections.connections.sumOf { it.models.size } > 12) OutlinedTextField(modelSearch, { modelSearch = it }, singleLine = true, label = { Text("Search models") }, modifier = Modifier.fillMaxWidth())
                    if (smallModelList) {
                        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (state.usesProvider) {
                                state.connections.connections.forEach { profile ->
                                    Text(profile.name, Modifier.fillMaxWidth().padding(start = 6.dp, top = 12.dp, bottom = 4.dp).testTag("chat-model-provider-${profile.id}"), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                                    profile.models.forEach { model -> ChatModelOption(state, profile, model, onDismiss) }
                                }
                            } else {
                                PanelOption("Sample chat", subtitle = "Offline preview", selected = true)
                            }
                        }
                    } else {
                        LazyColumn(state = modelList, modifier = Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (state.usesProvider) {
                                val groups = state.connections.connections.map { profile -> profile to profile.models.filter { "${profile.name} ${it.name} ${it.id}".contains(modelSearch, true) } }.filter { it.second.isNotEmpty() }
                                groups.forEach { (profile, models) ->
                                    item(key = "provider-${profile.id}") { Text(profile.name, Modifier.fillMaxWidth().padding(start = 6.dp, top = 12.dp, bottom = 4.dp).testTag("chat-model-provider-${profile.id}"), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface) }
                                    items(models, key = { "model-${profile.id}-${it.id}" }) { model ->
                                        ChatModelOption(state, profile, model, onDismiss)
                                    }
                                }
                                if (groups.isEmpty()) item { Text("No models found", Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            } else {
                                item { PanelOption("Sample chat", subtitle = "Offline preview", selected = true) }
                            }
                        }
                    }
                    if (state.availableEfforts.size > 1) {
                        HorizontalDivider(Modifier.padding(vertical = 4.dp))
                        Box(Modifier.testTag("chat-model-effort")) { PanelOption("Effort", value = state.conversation?.effort?.label ?: "Off", opensPanel = true, onClick = { onSelect("effort") }) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatModelOption(state: AssistantPreviewState, profile: com.veyarc.data.storage.AssistantConnection, model: com.veyarc.data.storage.AssistantModelConfig, onDismiss: () -> Unit) {
    PanelOption(model.name, selected = state.selectedConnection?.id == profile.id && state.activeModel?.id == model.id, enabled = state.canChange && !state.isResponding && state.connectionsReady, disabledLabel = null, onClick = {
        state.selectConnectionModel(profile.id, model.id)
        onDismiss()
    })
}

@Composable
private fun PanelOption(
    label: String,
    subtitle: String? = null,
    value: String? = null,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    selected: Boolean = false,
    enabled: Boolean = true,
    disabledLabel: String? = "Soon",
    opensPanel: Boolean = false,
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
            if (!enabled && disabledLabel != null) Text(disabledLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (value != null) Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (selected) Icon(Icons.Outlined.Check, "Selected", Modifier.size(24.dp), tint = Color(0xFF66A8EF))
            if (opensPanel) Icon(Icons.Outlined.ChevronRight, "Open $label", Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun AttachmentTile(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier, enabled: Boolean, onClick: () -> Unit) {
    Surface(onClick = onClick, enabled = enabled, shape = RoundedCornerShape(18.dp), color = chatPanelColor(raised = true), modifier = modifier) {
        Column(Modifier.heightIn(min = 108.dp).padding(vertical = 16.dp, horizontal = 8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Surface(shape = CircleShape, color = chatControlColor()) {
                Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) { Icon(icon, null, Modifier.size(24.dp)) }
            }
            Text(label, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun ComposerSendButton(state: AssistantPreviewState) {
    FilledIconButton(
        onClick = { if (state.isResponding) state.stop() else state.send() },
        enabled = state.isResponding || state.canSend,
        modifier = Modifier.testTag("assistant-send"),
    ) {
        if (state.isResponding) Icon(Icons.Outlined.StopCircle, "Stop response") else Icon(Icons.Outlined.ArrowUpward, "Send message")
    }
}

@Composable
private fun chatControlColor(): Color = MaterialTheme.colorScheme.surfaceContainerHighest

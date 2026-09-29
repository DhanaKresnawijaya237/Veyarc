package com.veyarc.feature.assistant

import android.app.Activity
import android.content.ClipData
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
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
                Box(Modifier.weight(1f)) { AssistantPreviewScreen(state) }
            }
        } else {
            AssistantPreviewScreen(state)
        }
    }
}

@Composable
fun AssistantPreviewScreen(state: AssistantPreviewState) {
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
    var renameVisible by remember { mutableStateOf(false) }
    var deleteVisible by remember { mutableStateOf(false) }
    var review by remember(chat.id) { mutableStateOf<PreviewTurn?>(null) }
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val keyboardVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val activity = generateSequence(LocalContext.current) { (it as? ContextWrapper)?.baseContext }
        .filterIsInstance<Activity>().firstOrNull()

    DisposableEffect(state, activity) {
        val previousSoftInputMode = activity?.window?.attributes?.softInputMode
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
        BoxWithConstraints(Modifier.safeDrawingPadding().imePadding()) {
            val shortWindow = maxHeight < 420.dp
            val tightWindow = maxHeight < 240.dp
            Column(Modifier.fillMaxSize()) {
                if (!tightWindow) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = ::leave) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back to editor") }
                        Column(Modifier.weight(1f)) {
                            Text(chat.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(if (state.usesProvider) "DeepSeek" else "Sample chat", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = state::newChat, enabled = state.canChange && state.conversations.size < AssistantPreviewState.MAX_CHATS) { Icon(Icons.Outlined.Add, "New chat") }
                        Box {
                            IconButton(onClick = { menuVisible = true }) { Icon(Icons.Outlined.MoreVert, "Chat options") }
                            DropdownMenu(expanded = menuVisible, onDismissRequest = { menuVisible = false }) {
                                DropdownMenuItem(text = { Text("Chat history") }, onClick = {
                                    menuVisible = false
                                    historyVisible = true
                                })
                                DropdownMenuItem(text = { Text("Chat storage") }, onClick = {
                                    menuVisible = false
                                    storageVisible = true
                                })
                                DropdownMenuItem(text = { Text("Workflow context") }, onClick = {
                                    menuVisible = false
                                    showContext()
                                })
                                DropdownMenuItem(text = { Text("Rename chat") }, enabled = state.canChange, onClick = {
                                    menuVisible = false
                                    renameVisible = true
                                })
                                DropdownMenuItem(text = { Text("Delete chat") }, enabled = state.canChange, onClick = {
                                    menuVisible = false
                                    deleteVisible = true
                                })
                                if (!state.usesProvider) {
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
                            }
                        }
                    }
                }
                key(chat.id) {
                    ConversationBody(state, onReview = { review = it }, onContext = {
                        contextCapture = AssistantContextCapture(it.baseSnapshot)
                        capturedTurn = true
                        contextVisible = true
                    }, modifier = Modifier.weight(1f))
                }
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                        Row(Modifier.padding(4.dp), verticalAlignment = Alignment.Bottom) {
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
                    }
                    val notice = when {
                        state.storageError != null -> "Chat not saved. Open Chat storage."
                        state.inputError != null -> state.inputError
                        chat.turns.size >= AssistantPreviewState.MAX_TURNS -> "Chat is full. Start a new chat."
                        chat.composer.length == AssistantPreviewState.MAX_PROMPT_LENGTH -> "4,000 character limit reached."
                        state.usesProvider && !state.providerEnabled -> "Enable AI in Settings to send."
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
        PreviewDialog("Chats for ${state.context.name}", onDismiss = { historyVisible = false }) {
            if (state.conversations.size >= AssistantPreviewState.MAX_CHATS) Text("Chat limit reached. Delete a chat to continue.")
            state.history.forEach { entry ->
                TextButton(onClick = {
                    state.select(entry.id)
                    historyVisible = false
                }, enabled = state.canChange, modifier = Modifier.fillMaxWidth()) {
                    Text(entry.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
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
    review?.let { selectedTurn ->
        val turn = chat.turns.firstOrNull { it.id == selectedTurn.id } ?: selectedTurn
        PreviewDialog("Review changes", onDismiss = { review = null }) {
            val proposal = turn.proposal
            if (proposal != null) {
                val current = state.previewContext()
                val result = remember(turn.baseSnapshot, proposal) {
                    runCatching { AssistantProposalReview.changes(requireNotNull(turn.baseSnapshot), proposal) }
                }
                val status = if (proposal.isValid && result.isFailure) {
                    AssistantReviewStatus.INVALID
                } else if (turn.localBindingId != null && turn.localBindingId != current.localBindingId && !turn.proposalDismissed) {
                    AssistantReviewStatus.STALE
                } else {
                    AssistantProposalReview.status(turn.baseSnapshot, current, proposal, turn.proposalDismissed)
                }
                Text(
                    when (turn.applyState) {
                        ProposalApplyState.NONE -> status.label
                        ProposalApplyState.RESERVED -> "Apply attempt recorded"
                        ProposalApplyState.APPLIED -> "Applied to draft"
                        ProposalApplyState.REJECTED -> "Apply was blocked"
                    },
                    style = MaterialTheme.typography.titleMedium,
                )
                when (turn.applyState) {
                    ProposalApplyState.RESERVED -> Text("Check the editor after an interrupted Apply. Unsaved edits may be lost on restart.")
                    ProposalApplyState.APPLIED -> Text("Use Undo in the editor to revert. Save when ready.")
                    ProposalApplyState.REJECTED -> Text("Nothing applied. Send a new request.")
                    ProposalApplyState.NONE -> Unit
                }
                if (status == AssistantReviewStatus.STALE && turn.applyState == ProposalApplyState.NONE) Text("Draft changed. Send a new request.", color = MaterialTheme.colorScheme.error)
                if (status == AssistantReviewStatus.DISMISSED) Text("Dismissed. Send a new request for changes.")
                proposal.issues.forEach { Text(it, color = MaterialTheme.colorScheme.error) }
                if (proposal.isValid && result.isFailure) Text("Cannot review this proposal. Send a new request.", color = MaterialTheme.colorScheme.error)
                if (state.supportsApply && turn.localBindingId == null) Text("Reopened proposal. Send a new request to apply changes.")
                Text("Device capabilities have not been checked.", style = MaterialTheme.typography.bodySmall)
                Text(if (state.supportsApply) "Apply changes the draft. Save and Run stay separate." else "Sample only. Apply is unavailable.")
                Button(onClick = { state.applyProposal(turn.id) }, enabled = result.isSuccess && state.canApply(turn)) { Text("Apply to draft") }
                if (!turn.proposalDismissed && turn.applyState == ProposalApplyState.NONE) TextButton(onClick = { state.dismissProposal(turn.id) }, enabled = state.canChange) { Text("Dismiss proposal") }
                result.getOrNull()?.let { changes ->
                    if (changes.isEmpty()) Text("No workflow changes")
                    SelectionContainer {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            changes.forEach { change ->
                                HorizontalDivider()
                                Text(change.label, style = MaterialTheme.typography.titleSmall)
                                Text("Before: ${change.before ?: "Absent"}")
                                Text("After: ${change.after ?: "Absent"}")
                            }
                        }
                    }
                }
            } else {
                Text(if (turn.scenario == PreviewScenario.STALE) "Stale proposal" else "Sample proposal", style = MaterialTheme.typography.titleMedium)
                Text("Example only · Wait block")
                Text("Before: 1 second\nAfter: 2 seconds")
                if (turn.scenario == PreviewScenario.STALE) Text("The example draft has changed. A new proposal would be required.")
                Text("This example is not based on your workflow. Applying changes is unavailable in the preview.")
            }
            if (proposal == null) Button(onClick = {}, enabled = false) { Text("Apply to draft") }
        }
    }
}

@Composable
private fun ConversationBody(state: AssistantPreviewState, onReview: (PreviewTurn) -> Unit, onContext: (PreviewTurn) -> Unit, modifier: Modifier = Modifier) {
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
                    PreviewTurnContent(turn, isLast = turn == chat.turns.last(), canRetry = state.canRetry, onRetry = state::retry, onReview = { onReview(turn) }, onContext = { onContext(turn) })
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
private fun PreviewTurnContent(turn: PreviewTurn, isLast: Boolean, canRetry: Boolean, onRetry: () -> Unit, onReview: () -> Unit, onContext: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Surface(Modifier.align(Alignment.End).widthIn(max = 560.dp), shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            MessageText(turn.prompt, "Your message", Modifier.padding(horizontal = 16.dp, vertical = 12.dp), onContext = if (turn.baseSnapshot != null) onContext else null)
        }
        if (turn.reply.isNotEmpty()) MessageText(turn.reply, if (turn.provider) "DeepSeek reply" else "Sample reply", Modifier.fillMaxWidth(), onContext = if (turn.baseSnapshot != null) onContext else null)
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
            OutlinedButton(onClick = onReview, shape = RoundedCornerShape(24.dp)) { Text("Review changes") }
        }
        if (isLast && turn.status !in listOf(PreviewReplyStatus.COMPLETE, PreviewReplyStatus.RESPONDING)) {
            TextButton(onClick = onRetry, enabled = canRetry) { Text(if (turn.provider) "Retry request" else "Retry sample") }
        }
    }
}

@Composable
private fun MessageText(text: String, speaker: String, modifier: Modifier = Modifier, onContext: (() -> Unit)? = null) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var actionsVisible by remember { mutableStateOf(false) }
    var selectionVisible by remember { mutableStateOf(false) }
    Box {
        Text(
            text,
            modifier.combinedClickable(onClick = {}, onLongClickLabel = "Message actions", onLongClick = { actionsVisible = true }).semantics {
                contentDescription = "$speaker: $text"
                if (speaker != "Your message") liveRegion = LiveRegionMode.Polite
            },
            style = MaterialTheme.typography.bodyLarge,
        )
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
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) { content() } },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

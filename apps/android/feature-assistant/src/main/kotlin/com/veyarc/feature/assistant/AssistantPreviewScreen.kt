package com.veyarc.feature.assistant

import android.app.Activity
import android.content.ClipData
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.activity.compose.BackHandler
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
import androidx.compose.material.icons.outlined.History
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
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
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
            Text(if (state.persistent) "Chat text is encrypted on this device. Enabled DeepSeek requests send your message, completed provider exchanges from this chat, and captured workflow text. Local deletion does not delete provider records." else "This test fixture keeps history in memory.")
            Text("Limits: 20 chats, 40 turns per chat, 4,000 characters per message, 256 KiB per chat, 4 MiB total. Composer changes save after a short pause.")
            state.storageError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (state.storageError != null && !state.loadFailed) {
                TextButton(onClick = state::saveAgain, enabled = state.pendingWrites == 0) { Text("Retry saving") }
            }
            if (state.persistent) {
                Text("Reloading saved history discards any changes that have not reached storage. It never resends a message.")
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
            text = { Text("This removes every chat and unsent message on this device. Workflows and revisions are unchanged.") },
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
                Text(state.storageError ?: if (state.usesProvider) "Create a chat for this workflow. Enable AI in Settings before sending." else "Create a chat for this workflow. Replies are still local samples; no AI is connected.")
                Button(onClick = state::newChat, enabled = state.canChange && state.conversations.size < AssistantPreviewState.MAX_CHATS) { Text("New chat") }
                TextButton(onClick = { storageVisible = true }) { Text("Chat storage") }
                if (state.conversations.size >= AssistantPreviewState.MAX_CHATS) Text("History is full. Open another workflow to delete one of its chats, or clear all history.")
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
                        Text(chat.title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        IconButton(onClick = state::newChat, enabled = state.canChange && state.conversations.size < AssistantPreviewState.MAX_CHATS) { Icon(Icons.Outlined.Add, "New chat") }
                        IconButton(onClick = { historyVisible = true }) { Icon(Icons.Outlined.History, "Chat history") }
                        Box {
                            IconButton(onClick = { menuVisible = true }) { Icon(Icons.Outlined.MoreVert, "Chat options") }
                            DropdownMenu(expanded = menuVisible, onDismissRequest = { menuVisible = false }) {
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
                if (!tightWindow) {
                    TextButton(onClick = ::showContext, modifier = Modifier.fillMaxWidth()) {
                        Text("${state.context.name} · ${state.context.label}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                HorizontalDivider()
                key(chat.id) {
                    ConversationBody(state, onReview = { review = it }, onContext = {
                        contextCapture = AssistantContextCapture(it.baseSnapshot)
                        capturedTurn = true
                        contextVisible = true
                    }, modifier = Modifier.weight(1f))
                }
                HorizontalDivider()
                Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (tightWindow) {
                            IconButton(onClick = ::leave) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back to editor") }
                        }
                        OutlinedTextField(
                            value = chat.composer,
                            onValueChange = state::compose,
                            enabled = !state.isLoading,
                            isError = state.inputError != null,
                            supportingText = if (tightWindow && state.inputError != null) ({ Text(state.inputError!!) }) else null,
                            label = { Text(if (tightWindow && !state.usesProvider) "Message · Local preview" else "Message") },
                            modifier = Modifier.weight(1f).testTag("assistant-composer"),
                            maxLines = if (shortWindow) 2 else 4,
                            shape = RoundedCornerShape(20.dp),
                        )
                        FilledIconButton(
                            onClick = {
                                if (state.isResponding) state.stop() else state.send()
                            },
                            enabled = state.isResponding || state.canSend,
                        ) {
                            if (state.isResponding) Icon(Icons.Outlined.StopCircle, "Stop response") else Icon(Icons.AutoMirrored.Outlined.Send, "Send message")
                        }
                    }
                    if (!tightWindow) {
                        Text(
                            when {
                                state.storageError != null -> "Not saved · Open Chat storage in options"
                                state.inputError != null -> state.inputError!!
                                state.pendingWrites > 0 -> "Saving chat…"
                                chat.turns.size >= AssistantPreviewState.MAX_TURNS -> "Message limit reached. Start a new chat."
                                chat.composer.length == AssistantPreviewState.MAX_PROMPT_LENGTH -> "Message limit: ${AssistantPreviewState.MAX_PROMPT_LENGTH} characters."
                                state.usesProvider -> if (state.providerEnabled) "DeepSeek · API charges apply · Review before Apply" else "AI disabled · Enable in Settings"
                                else -> "Local preview · No AI connected"
                            },
                            modifier = Modifier.padding(top = 4.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }

    if (contextVisible) {
        PreviewDialog(if (capturedTurn) "Captured workflow context" else "Workflow context", onDismiss = { contextVisible = false }) {
            Text(if (state.usesProvider) "Send shares your message, the captured workflow text below, and completed DeepSeek prompt/reply text from this chat. Samples, errors, partial replies, earlier snapshots, and earlier candidate JSON are excluded." else "Nothing is sent in this preview. The text below is the workflow context prepared for a future provider request, alongside your message and chat history.")
            Text(if (capturedTurn) "This is the unchanged snapshot saved with that message. Retry uses the same snapshot." else "Send captures the current editor draft again, including unsaved edits. Invalid draft fields remain as entered; this is not a validated workflow.")
            contextCapture.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            val snapshot = contextCapture.snapshot
            if (snapshot == null && contextCapture.error == null) {
                Text("No workflow is attached. Package, orientation, and geometry remain unspecified.")
            }
            if (snapshot != null) {
                Text("Includes blocks, variables, package text, viewport, limits, and image reference names. Image bytes, recordings, run history, credentials, and other workflows are excluded.")
                Text("${snapshot.json.toByteArray(Charsets.UTF_8).size} / ${AssistantContextSnapshot.MAX_BYTES} bytes · Snapshot ${snapshot.snapshotId.take(12)}")
                SelectionContainer { Text(snapshot.json, modifier = Modifier.testTag("assistant-context-json"), style = MaterialTheme.typography.bodySmall) }
            }
        }
    }

    if (historyVisible) {
        PreviewDialog("Chats for ${state.context.name}", onDismiss = { historyVisible = false }) {
            Text(if (state.persistent) "Chat history is encrypted on this device." else "Preview history stays in memory until the app closes.")
            if (state.conversations.size >= AssistantPreviewState.MAX_CHATS) Text("Preview limit reached. Delete a chat before creating another.")
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
            text = { OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Chat title (80 characters max)") }, isError = title.length > 80) },
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
        PreviewDialog(if (turn.proposal != null) "Proposal validation" else "Review sample changes", onDismiss = { review = null }) {
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
                    ProposalApplyState.RESERVED -> Text("This attempt cannot be repeated. Check the editor if it was interrupted; your unsaved draft may not survive restarting the app.")
                    ProposalApplyState.APPLIED -> Text("Applied once to the editable draft. Use the editor's Undo to revert. Save remains a separate action.")
                    ProposalApplyState.REJECTED -> Text("The draft changed or you left chat before Apply completed. Nothing was applied by this attempt. Send a new request.")
                    ProposalApplyState.NONE -> Unit
                }
                if (status == AssistantReviewStatus.STALE && turn.applyState == ProposalApplyState.NONE) Text("The draft or its local editor binding changed or is unavailable. Send a new request before applying changes.", color = MaterialTheme.colorScheme.error)
                if (status == AssistantReviewStatus.DISMISSED) Text("This proposal was dismissed. Send a new request for another proposal.")
                proposal.issues.forEach { Text(it, color = MaterialTheme.colorScheme.error) }
                if (proposal.isValid && result.isFailure) Text("The saved proposal could not be reviewed. Send a new request.", color = MaterialTheme.colorScheme.error)
                if (state.supportsApply && turn.localBindingId == null) Text("This older proposal has no editor binding. Send a new request to apply changes.")
                Text(AssistantCandidateValidator.CAPABILITY_NOTICE)
                Text(if (state.supportsApply) "Changes below compare the captured base with the candidate. Apply updates only the draft; it does not save or run the workflow." else "Changes below compare the captured base with the candidate. Applying is unavailable in this fixture.")
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
    Box(modifier) {
        LazyColumn(state = list, modifier = Modifier.fillMaxSize().testTag("assistant-messages"), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (state.usesProvider) "AI chat" else "AI chat preview", style = MaterialTheme.typography.headlineSmall)
                    Text(if (state.usesProvider) "Ask DeepSeek to explain or propose changes to this workflow. Enable AI in Settings, then press Send. API charges apply. Review changes before Apply; Save and Run remain separate actions. Chat history is encrypted locally." else "Try sample replies and change reviews. Nothing is sent to an AI provider. Review changes before Apply, then Save in the editor. Chat history is ${if (state.persistent) "encrypted on this device" else "temporary in this test fixture"}.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (state.context.needsSetup) Text("Setup needed: return to the editor, choose the allowed app and orientation, and add the actions with their geometry or timing. Then ask for a proposal. No workflow has been saved.")
                    if (chat.turns.isEmpty()) {
                        val suggestions = if (state.context.needsSetup || state.context.workflowId == null) {
                            listOf("Help me create a workflow", "What details do you need?")
                        } else {
                            listOf("Explain this workflow", "Suggest an improvement", "Describe a change")
                        }
                        suggestions.forEach { suggestion ->
                            OutlinedButton(onClick = { state.compose(suggestion) }) { Text(suggestion) }
                        }
                        if (!state.usesProvider) Text("Choose a sample response in Chat options.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            items(chat.turns, key = { it.id }) { turn ->
                PreviewTurnContent(turn, isLast = turn == chat.turns.last(), canRetry = state.canRetry, onRetry = state::retry, onReview = { onReview(turn) }, onContext = { onContext(turn) })
            }
            item { Box(Modifier.heightIn(min = 32.dp)) }
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
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(Modifier.align(Alignment.End).widthIn(max = 560.dp), shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
            SelectionContainer {
                Column(Modifier.padding(12.dp)) {
                    Text("You", style = MaterialTheme.typography.labelSmall)
                    Text(turn.prompt)
                }
            }
        }
        if (turn.baseSnapshot != null) TextButton(onClick = onContext) { Text("View captured context") }
        Text(if (turn.provider) "DeepSeek" else "Veyarc · Sample reply", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        SelectionContainer {
            Text(
                turn.reply.ifEmpty {
                    if (turn.status == PreviewReplyStatus.RESPONDING) {
                        if (turn.provider) "Waiting for a complete response…" else "Preparing sample response…"
                    } else {
                        "No reply text was saved."
                    }
                },
            )
        }
        turn.responseError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (turn.outcome == AssistantOutcome.CLARIFICATION) Text("Clarification needed", style = MaterialTheme.typography.labelMedium)
        Text(
            when (turn.status) {
                PreviewReplyStatus.RESPONDING -> if (turn.provider) "Request in progress" else "Playing sample response"

                PreviewReplyStatus.COMPLETE -> if (turn.provider) "Response complete" else "Sample complete"

                PreviewReplyStatus.STOPPED -> "Stopped"

                PreviewReplyStatus.INTERRUPTED -> "Interrupted"

                PreviewReplyStatus.ERROR -> if (turn.provider) {
                    "Request failed · Retry is explicit"
                } else if (turn.responseError != null) {
                    "Invalid sample response"
                } else {
                    "Sample connection error"
                }
            },
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            style = MaterialTheme.typography.labelSmall,
            color = if (turn.status == PreviewReplyStatus.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (turn.proposal != null) {
            OutlinedButton(onClick = onReview) { Text("View proposal validation") }
        }
        if (!turn.provider && turn.status == PreviewReplyStatus.COMPLETE && turn.scenario in listOf(PreviewScenario.PROPOSAL, PreviewScenario.STALE)) {
            OutlinedButton(onClick = onReview) { Text("Review sample changes") }
        }
        Row {
            if (turn.reply.isNotEmpty()) {
                TextButton(onClick = { scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("Assistant reply", turn.reply))) } }) { Text("Copy reply") }
            }
            if (isLast && turn.status !in listOf(PreviewReplyStatus.COMPLETE, PreviewReplyStatus.RESPONDING)) {
                TextButton(onClick = onRetry, enabled = canRetry) { Text(if (turn.provider) "Retry request" else "Retry sample") }
            }
        }
    }
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

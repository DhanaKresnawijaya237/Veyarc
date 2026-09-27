package com.veyarc.feature.assistant

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.veyarc.data.storage.ChatRepository
import com.veyarc.data.storage.StoredChat
import com.veyarc.model.PackageName
import com.veyarc.model.WorkflowDocument
import com.veyarc.model.WorkflowJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.util.UUID

@Serializable
data class AssistantPreviewContext(
    val workflowId: String? = null,
    val name: String = "New workflow",
    val revision: Int? = null,
    val isDirty: Boolean = false,
    val needsSetup: Boolean = false,
) {
    val label: String
        get() = if (isDirty || revision == null) "Unsaved draft" else "Revision $revision"
}

@Serializable
enum class PreviewScenario(val label: String) {
    EXPLANATION("Explanation"),
    CLARIFICATION("Clarification"),
    INVALID_OUTPUT("Invalid response"),
    VALIDATED_PROPOSAL("Candidate validation"),
    INVALID_CANDIDATE("Invalid candidate"),
    PROPOSAL("Change proposal"),
    LONG_ANSWER("Long answer"),
    ERROR("Connection error"),
    STALE("Stale proposal"),
}

@Serializable
enum class PreviewReplyStatus { RESPONDING, COMPLETE, STOPPED, INTERRUPTED, ERROR }

@Serializable
enum class ProposalApplyState { NONE, RESERVED, APPLIED, REJECTED }

@Serializable
data class PreviewTurn(
    val id: String,
    val prompt: String,
    val scenario: PreviewScenario,
    val reply: String = "",
    val status: PreviewReplyStatus = PreviewReplyStatus.RESPONDING,
    val requestId: String = UUID.randomUUID().toString(),
    val attempt: Int = 1,
    val baseSnapshot: AssistantContextSnapshot? = null,
    val outcome: AssistantOutcome? = null,
    val responseError: String? = null,
    val proposal: AssistantProposalCheck? = null,
    val proposalDismissed: Boolean = false,
    val localBindingId: String? = null,
    val applyState: ProposalApplyState = ProposalApplyState.NONE,
    val createdAtMs: Long = System.currentTimeMillis(),
)

@Serializable
data class PreviewConversation(
    val id: String,
    val workflowId: String?,
    val title: String = "New chat",
    val composer: String = "",
    val turns: List<PreviewTurn> = emptyList(),
    val draftId: String = workflowId ?: UUID.randomUUID().toString(),
    val context: AssistantPreviewContext = AssistantPreviewContext(),
    val createdAtMs: Long = System.currentTimeMillis(),
    val updatedAtMs: Long = createdAtMs,
    val formatVersion: Int = 1,
)

/** Null storage is reserved for deterministic UI/unit fixtures. The app supplies encrypted storage. */
class AssistantPreviewState(
    private val repository: ChatRepository? = null,
    private val scope: CoroutineScope? = null,
    private val compileCandidate: ((WorkflowDocument) -> List<String>)? = null,
    private val applyCandidate: ((String, AssistantContextSnapshot, String) -> Boolean)? = null,
    private var contextSource: (() -> AssistantContextCapture)? = null,
) {
    var isOpen by mutableStateOf(false)
        private set
    var context by mutableStateOf(AssistantPreviewContext())
        private set
    var conversations by mutableStateOf<List<PreviewConversation>>(emptyList())
        private set
    var selectedId by mutableStateOf<String?>(null)
        private set
    var scenario by mutableStateOf(PreviewScenario.EXPLANATION)
    var requestId by mutableStateOf<String?>(null)
        private set
    var isLoading by mutableStateOf(repository != null)
        private set
    var storageError by mutableStateOf<String?>(null)
        private set
    var pendingWrites by mutableStateOf(0)
        private set
    var inputError by mutableStateOf<String?>(null)
        private set
    var loadFailed by mutableStateOf(false)
        private set
    private var pendingRequestId: String? = null
    private var applyGeneration = 0
    private var draftSave: Job? = null
    private val savedIds = mutableSetOf<String>()

    // One ordered writer prevents an older composer save from replacing a newer sent turn.
    private val writes = Channel<suspend () -> Unit>(64)
    val persistent: Boolean get() = repository != null
    val canChange: Boolean get() = !isLoading && pendingWrites == 0 && storageError == null
    val conversation: PreviewConversation? get() = conversations.firstOrNull { it.id == selectedId }
    val history: List<PreviewConversation> get() = conversations.filter { it.workflowId == context.workflowId }.asReversed()
    val canSend: Boolean get() = canChange && requestId == null && pendingRequestId == null && conversation?.let {
        it.composer.isNotBlank() && it.composer.length <= MAX_PROMPT_LENGTH && it.turns.size < MAX_TURNS
    } == true

    init {
        require(repository == null || scope != null)
        if (repository != null) {
            scope!!.launch { for (write in writes) write() }
            reload()
        }
    }

    fun reload() {
        if (repository == null || pendingWrites != 0) return
        draftSave?.cancel()
        requestId = null
        pendingRequestId = null
        isLoading = true
        loadFailed = true
        enqueue { loadHistory() }
    }

    private suspend fun loadHistory() {
        val store = requireNotNull(repository)
        loadFailed = true
        val loaded = withContext(Dispatchers.IO) {
            store.load().map { stored ->
                val chat = decode(stored)
                val recovered = chat.copy(
                    turns = chat.turns.map {
                        when {
                            it.status == PreviewReplyStatus.RESPONDING -> it.copy(status = PreviewReplyStatus.INTERRUPTED)
                            it.proposal?.candidateJson != null -> it.copy(proposal = AssistantCandidateValidator.validate(it.baseSnapshot, it.proposal.candidateJson, compileCandidate))
                            else -> it
                        }
                    },
                )
                if (recovered != chat) store.save(encode(recovered), create = false)
                recovered
            }
        }
        conversations = loaded
        savedIds.clear()
        savedIds.addAll(loaded.map { it.id })
        selectedId = history.firstOrNull()?.id
        storageError = null
        isLoading = false
        loadFailed = false
    }

    fun open(currentContext: AssistantPreviewContext, captureContext: (() -> AssistantContextCapture)? = null) {
        if (captureContext != null) contextSource = captureContext
        if (isOpen && context.workflowId == currentContext.workflowId) {
            context = currentContext
            return
        }
        if (isOpen) stop(PreviewReplyStatus.INTERRUPTED)
        selectedId = conversations.lastOrNull { it.workflowId == currentContext.workflowId }?.id
        context = currentContext
        isOpen = true
        if (repository != null && storageError == null && !isLoading) {
            isLoading = true
            enqueue {
                // Read after queued close writes; a failed write must retain unsaved text.
                if (storageError == null) {
                    loadHistory()
                    if (conversation == null && isOpen) createChat()
                } else {
                    isLoading = false
                }
            }
        } else if (conversation == null && canChange) {
            newChat()
        }
    }

    fun previewContext(): AssistantContextCapture {
        val capture = contextSource?.invoke() ?: return AssistantContextCapture()
        return if (capture.error == null && capture.snapshot?.workflowId != context.workflowId) {
            AssistantContextCapture(error = "The editor is showing another workflow. Reopen chat for that workflow before sending.")
        } else {
            capture
        }
    }

    fun close() {
        stop(PreviewReplyStatus.INTERRUPTED)
        isOpen = false
    }

    fun compose(text: String) {
        if (isLoading) return
        if (text.length > MAX_PROMPT_LENGTH) {
            inputError = "Message limit: $MAX_PROMPT_LENGTH characters. Shorten the text before pasting."
            return
        }
        inputError = null
        update { it.copy(composer = text) }
        draftSave?.cancel()
        if (repository != null && storageError == null) {
            draftSave = scope!!.launch {
                delay(500)
                conversation?.let { persist(it) }
            }
        }
    }

    fun newChat() {
        if (!canChange || conversations.size >= MAX_CHATS) return
        stop(PreviewReplyStatus.INTERRUPTED)
        createChat()
    }

    private fun createChat() {
        if (conversations.size >= MAX_CHATS) return
        val chat = PreviewConversation(UUID.randomUUID().toString(), context.workflowId, context = context)
        conversations = conversations + chat
        selectedId = chat.id
        persist(chat)
    }

    fun select(id: String) {
        if (!canChange || history.none { it.id == id }) return
        stop(PreviewReplyStatus.INTERRUPTED)
        selectedId = id
    }

    fun rename(title: String) {
        if (!canChange || title.isBlank() || title.length > 80) return
        update { it.copy(title = title.trim()) }
        flush()
    }

    fun deleteCurrent() {
        if (!canChange) return
        stop(PreviewReplyStatus.INTERRUPTED)
        val id = selectedId ?: return
        fun removed() {
            conversations = conversations.filterNot { it.id == id }
            savedIds.remove(id)
            selectedId = history.firstOrNull()?.id
        }
        if (repository == null) {
            removed()
        } else {
            enqueue {
                withContext(Dispatchers.IO) { repository.delete(id) }
                removed()
            }
        }
    }

    fun clearHistory() {
        if (isLoading || pendingWrites != 0) return
        draftSave?.cancel()
        requestId = null
        pendingRequestId = null
        fun cleared() {
            conversations = emptyList()
            selectedId = null
            savedIds.clear()
            storageError = null
            loadFailed = false
        }
        if (repository == null) {
            cleared()
        } else {
            enqueue {
                withContext(Dispatchers.IO) { repository.clear() }
                cleared()
            }
        }
    }

    val supportsApply: Boolean get() = applyCandidate != null

    fun canApply(turn: PreviewTurn): Boolean {
        if (!canChange || !isOpen || applyCandidate == null || requestId != null || pendingRequestId != null) return false
        if (turn.status != PreviewReplyStatus.COMPLETE || turn.proposalDismissed || turn.applyState != ProposalApplyState.NONE || turn.proposal?.isValid != true || turn.localBindingId == null) return false
        if (runCatching { AssistantProposalReview.changes(requireNotNull(turn.baseSnapshot), requireNotNull(turn.proposal)).isEmpty() }.getOrDefault(true)) return false
        val current = previewContext()
        return current.error == null && current.snapshot == turn.baseSnapshot && current.localBindingId == turn.localBindingId
    }

    fun applyProposal(turnId: String) {
        val chat = conversation ?: return
        val turn = chat.turns.firstOrNull { it.id == turnId } ?: return
        if (!canApply(turn)) return
        val generation = applyGeneration
        draftSave?.cancel()
        update { it.copy(turns = it.turns.map { item -> if (item.id == turnId) item.copy(applyState = ProposalApplyState.RESERVED) else item }) }
        // Persist consumption before touching the draft. A restored RESERVED turn never retries.
        persist(requireNotNull(conversation)) {
            val applied = generation == applyGeneration && isOpen && selectedId == chat.id &&
                runCatching { applyCandidate!!.invoke(turn.localBindingId!!, turn.baseSnapshot!!, turn.proposal!!.candidateJson!!) }.getOrDefault(false)
            conversations = conversations.map { item ->
                if (item.id != chat.id) {
                    item
                } else {
                    item.copy(
                        turns = item.turns.map { saved ->
                            if (saved.id == turnId) saved.copy(applyState = if (applied) ProposalApplyState.APPLIED else ProposalApplyState.REJECTED) else saved
                        },
                        updatedAtMs = maxOf(item.updatedAtMs, System.currentTimeMillis()),
                    )
                }
            }
            conversations.firstOrNull { it.id == chat.id }?.let { persist(it) }
        }
    }

    fun dismissProposal(turnId: String) {
        if (!canChange) return
        update { chat -> chat.copy(turns = chat.turns.map { if (it.id == turnId && it.proposal != null && it.applyState == ProposalApplyState.NONE) it.copy(proposalDismissed = true) else it }) }
        flush()
    }

    fun saveAgain() {
        if (isLoading || loadFailed || pendingWrites != 0) return
        storageError = null
        conversations.forEach { persist(it) }
    }

    fun send() {
        if (!canSend) return
        val capture = previewContext()
        if (capture.error != null) {
            inputError = capture.error
            return
        }
        val current = requireNotNull(conversation)
        val prompt = current.composer.trim()
        val turn = PreviewTurn(UUID.randomUUID().toString(), prompt, scenario, baseSnapshot = capture.snapshot, localBindingId = capture.localBindingId)
        val updated = current.copy(
            title = if (current.turns.isEmpty() && current.title == "New chat") prompt.take(48) else current.title,
            composer = "",
            turns = current.turns + turn,
            context = context,
        )
        if (runCatching { encode(updated) }.isFailure) {
            inputError = "This chat cannot fit another message and its context. Start a new chat; your unsent text is unchanged."
            return
        }
        inputError = null
        draftSave?.cancel()
        update { updated }
        begin(turn.requestId)
    }

    private fun begin(token: String) {
        pendingRequestId = token
        val chat = requireNotNull(conversation)
        persist(chat) {
            if (pendingRequestId == token && selectedId == chat.id && isOpen) {
                pendingRequestId = null
                requestId = token
            }
        }
    }

    fun advance(token: String) {
        if (requestId != token) return
        val chat = conversation ?: return
        val turn = chat.turns.lastOrNull() ?: return
        if (turn.scenario == PreviewScenario.INVALID_OUTPUT) {
            receive(token, "{invalid sample response}".toByteArray())
            return
        }
        if (turn.scenario in listOf(PreviewScenario.VALIDATED_PROPOSAL, PreviewScenario.INVALID_CANDIDATE)) {
            val base = runCatching { turn.baseSnapshot?.let(AssistantCandidateValidator::baseDocument) }.getOrNull()
            val candidate = base?.copy(
                name = "Sample workflow name",
                allowedPackages = if (turn.scenario == PreviewScenario.INVALID_CANDIDATE) listOf(PackageName("com.example.changed")) else base.allowedPackages,
            )
            receive(
                token,
                AssistantResponseCodec.encode(
                    AssistantResponse(
                        1,
                        token,
                        chat.id,
                        turn.responseBaseId(chat),
                        if (candidate == null) AssistantOutcome.CLARIFICATION else AssistantOutcome.PROPOSAL,
                        if (candidate == null) "Sample clarification: open a valid workflow draft first. Missing package, orientation, or geometry must be supplied in the editor." else "Local sample candidate for validation only. Your workflow is unchanged.",
                        candidate?.let { WorkflowJson.format.encodeToJsonElement(WorkflowDocument.serializer(), it).jsonObject },
                    ),
                ),
            )
            return
        }
        val sample = sampleReply(turn.scenario)
        val reply = sample.take((turn.reply.length + 64).coerceAtMost(sample.length))
        if (reply.length < sample.length) {
            updateLast { it.copy(reply = reply) }
        } else if (turn.scenario == PreviewScenario.ERROR) {
            updateLast { it.copy(reply = reply, status = PreviewReplyStatus.ERROR) }
            requestId = null
            flush()
        } else {
            receive(
                token,
                AssistantResponseCodec.encode(
                    AssistantResponse(1, token, chat.id, turn.responseBaseId(chat), if (turn.scenario == PreviewScenario.CLARIFICATION) AssistantOutcome.CLARIFICATION else AssistantOutcome.EXPLANATION, sample),
                ),
            )
        }
    }

    /** Called only with a complete response. A stale/cancelled attempt can never change a turn. */
    internal fun receive(token: String, bytes: ByteArray) {
        if (requestId != token) return
        val chat = conversation ?: return
        val turn = chat.turns.lastOrNull() ?: return
        if (turn.requestId != token || turn.status != PreviewReplyStatus.RESPONDING) return
        val response = runCatching { AssistantResponseCodec.decode(bytes, token, chat.id, turn.responseBaseId(chat)) }.getOrNull()
        if (response == null) {
            updateLast { it.copy(status = PreviewReplyStatus.ERROR, outcome = null, responseError = "Response rejected: invalid format, size, or request context. Your prompt is kept; retry is explicit.") }
        } else {
            val proposal = response.candidate?.let { AssistantCandidateValidator.validate(turn.baseSnapshot, it.toString(), compileCandidate) }
            updateLast { it.copy(reply = response.text, status = PreviewReplyStatus.COMPLETE, outcome = response.outcome, responseError = null, proposal = proposal) }
        }
        requestId = null
        flush()
    }

    fun stop(status: PreviewReplyStatus = PreviewReplyStatus.STOPPED) {
        applyGeneration++
        if (requestId != null || pendingRequestId != null) {
            requestId = null
            pendingRequestId = null
            updateLast { it.copy(status = status) }
        }
        flush()
    }

    fun retry() {
        val turn = conversation?.turns?.lastOrNull() ?: return
        if (!canChange || requestId != null || pendingRequestId != null || turn.status == PreviewReplyStatus.COMPLETE) return
        val token = UUID.randomUUID().toString()
        updateLast { it.copy(reply = "", status = PreviewReplyStatus.RESPONDING, requestId = token, attempt = it.attempt + 1, outcome = null, responseError = null, proposal = null) }
        begin(token)
    }

    private fun flush() {
        draftSave?.cancel()
        if (!isLoading && storageError == null) conversation?.let { persist(it) }
    }

    private fun persist(chat: PreviewConversation, onSaved: () -> Unit = {}) {
        if (repository == null) {
            onSaved()
            return
        }
        enqueue {
            val create = chat.id !in savedIds
            withContext(Dispatchers.IO) { repository.save(encode(chat), create) }
            savedIds.add(chat.id)
            onSaved()
        }
    }

    private fun enqueue(operation: suspend () -> Unit) {
        pendingWrites++
        check(
            writes.trySend {
                try {
                    operation()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    requestId = null
                    pendingRequestId = null
                    conversations = conversations.map { chat ->
                        chat.copy(turns = chat.turns.map { if (it.status == PreviewReplyStatus.RESPONDING) it.copy(status = PreviewReplyStatus.INTERRUPTED) else it })
                    }
                    storageError = "Chat storage failed. Your visible text is kept in memory. Retry saving or reload saved history; no message will be resent."
                    isLoading = false
                } finally {
                    pendingWrites--
                }
            }.isSuccess,
        ) { "Chat write queue is full" }
    }

    private fun updateLast(transform: (PreviewTurn) -> PreviewTurn) = update { chat ->
        val last = chat.turns.lastOrNull() ?: return@update chat
        chat.copy(turns = chat.turns.dropLast(1) + transform(last))
    }

    private fun update(transform: (PreviewConversation) -> PreviewConversation) {
        conversations = conversations.map { if (it.id == selectedId) transform(it).copy(updatedAtMs = maxOf(it.updatedAtMs, System.currentTimeMillis())) else it }
    }

    companion object {
        const val MAX_PROMPT_LENGTH = 4000
        const val MAX_REPLY_LENGTH = 16000
        const val MAX_TURNS = 40
        const val MAX_CHATS = ChatRepository.MAX_CHATS
        private val json = Json { encodeDefaults = true }

        internal fun encode(chat: PreviewConversation): StoredChat {
            validate(chat)
            val last = chat.turns.lastOrNull()
            val payload = json.encodeToString(chat).toByteArray(Charsets.UTF_8)
            require(payload.size <= ChatRepository.MAX_PAYLOAD_BYTES)
            return StoredChat(chat.id, chat.workflowId, chat.createdAtMs, chat.updatedAtMs, last?.requestId, last?.status?.name ?: "EMPTY", payload)
        }

        internal fun decode(stored: StoredChat): PreviewConversation {
            require(stored.payload.size <= ChatRepository.MAX_PAYLOAD_BYTES)
            val chat = json.decodeFromString<PreviewConversation>(stored.payload.toString(Charsets.UTF_8))
            validate(chat)
            require(chat.id == stored.id && chat.workflowId == stored.workflowId && chat.createdAtMs == stored.createdAtMs && chat.updatedAtMs == stored.updatedAtMs)
            require(chat.turns.lastOrNull()?.requestId == stored.requestId)
            require((chat.turns.lastOrNull()?.status?.name ?: "EMPTY") == stored.status)
            return chat
        }

        private fun validate(chat: PreviewConversation) {
            require(chat.formatVersion == 1 && UUID.fromString(chat.id).toString() == chat.id)
            require(chat.title.length in 1..80 && chat.composer.length <= MAX_PROMPT_LENGTH && chat.turns.size <= MAX_TURNS)
            require(chat.context.name.length <= 1000 && chat.createdAtMs >= 0 && chat.updatedAtMs >= chat.createdAtMs)
            require(chat.draftId.length in 1..128 && chat.context.workflowId == chat.workflowId)
            require(chat.turns.map { it.id }.distinct().size == chat.turns.size)
            chat.turns.forEachIndexed { index, turn ->
                turn.baseSnapshot?.let { snapshot ->
                    require(snapshot.workflowId == chat.workflowId)
                    snapshot.validate()
                }
                require(UUID.fromString(turn.id).toString() == turn.id && UUID.fromString(turn.requestId).toString() == turn.requestId)
                require(turn.prompt.isNotBlank() && turn.prompt.length <= MAX_PROMPT_LENGTH && turn.reply.length <= MAX_REPLY_LENGTH && turn.attempt > 0 && turn.createdAtMs >= 0)
                require((turn.outcome == AssistantOutcome.PROPOSAL) == (turn.proposal != null))
                require(turn.localBindingId == null || turn.localBindingId.length in 1..128)
                require(turn.applyState == ProposalApplyState.NONE || (turn.proposal != null && turn.baseSnapshot != null && turn.localBindingId != null && !turn.proposalDismissed))
                turn.proposal?.let { proposal ->
                    require(proposal.isValid || (proposal.candidateJson == null && proposal.issues.isNotEmpty()))
                    require(proposal.issues.size <= 20 && proposal.issues.all { it.length <= 300 })
                    require((proposal.candidateJson?.toByteArray(Charsets.UTF_8)?.size ?: 0) <= AssistantCandidateValidator.MAX_CANDIDATE_BYTES)
                }
                require(turn.outcome == null || turn.status == PreviewReplyStatus.COMPLETE)
                require(turn.responseError == null || (turn.status == PreviewReplyStatus.ERROR && turn.responseError.length <= 300))
                require(turn.status != PreviewReplyStatus.RESPONDING || index == chat.turns.lastIndex)
            }
        }
    }
}

private fun sampleReply(scenario: PreviewScenario): String = when (scenario) {
    PreviewScenario.EXPLANATION ->
        "This is a sample reply, not an analysis of your workflow.\n\n" +
            "A workflow runs its blocks in order. Wait blocks pause before the next action. " +
            "Conditions decide which branch to follow, and bounded loops repeat a group of blocks.\n\n" +
            "In the connected version, you will be able to ask about your own blocks or describe a change. " +
            "You will review proposed changes before applying them to the editor."

    PreviewScenario.CLARIFICATION -> "Sample clarification: which Android app should the workflow use, and what should happen first? Supply the package, orientation, and recorded or selected touch positions before asking for a runnable workflow. This sample does not inspect your screen."

    PreviewScenario.INVALID_OUTPUT, PreviewScenario.VALIDATED_PROPOSAL, PreviewScenario.INVALID_CANDIDATE -> ""

    PreviewScenario.PROPOSAL -> "Sample proposal: increase a Wait block from 1 second to 2 seconds. Open the review to compare the example values. Your workflow is unchanged."

    PreviewScenario.STALE -> "This sample proposal refers to an older draft. Its review shows how a stale change will be blocked. Your workflow is unchanged."

    PreviewScenario.ERROR -> "Sample connection failure. Your message is still here. Retry replays this same error example; no provider is contacted."

    PreviewScenario.LONG_ANSWER -> buildString {
        append("Sample long answer. These notes demonstrate scrolling and text selection, not a review of your workflow.\n\n")
        listOf(
            "Start with the target app" to "Choose the app explicitly. A model cannot infer its package or inspect its buttons from a name alone.",
            "Use known coordinates" to "Record or select the actual touch positions. A description such as 'tap the blue button' is not enough to locate it.",
            "Make waits deliberate" to "Allow the target app time to respond. A fixed wait describes timing; it does not establish that a screen is ready.",
            "Keep loops bounded" to "Review repeat counts and time limits. A condition must not create an unlimited run.",
            "Review every change" to "Compare the proposed blocks and their values. Unchanged blocks and local image references should be preserved.",
            "Save after approval" to "Applying a proposal will update an editable draft. Saving will create a revision; playback remains a separate action.",
        ).forEachIndexed { index, (heading, body) -> append("${index + 1}. $heading\n$body\n\n") }
    }
}

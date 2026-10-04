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
import kotlinx.coroutines.sync.withLock
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
    val provider: Boolean = false,
    val effort: DeepSeekEffort = DeepSeekEffort.OFF,
    val thinking: AssistantThinking? = null,
    val model: DeepSeekModel = DeepSeekModel.FLASH,
    val attachments: List<AssistantAttachment> = emptyList(),
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
    val pinned: Boolean = false,
    val effort: DeepSeekEffort = DeepSeekEffort.OFF,
    val model: DeepSeekModel = DeepSeekModel.FLASH,
    val composerAttachments: List<AssistantAttachment> = emptyList(),
)

/** Null storage is reserved for deterministic UI/unit fixtures. The app supplies encrypted storage. */
class AssistantPreviewState(
    private val repository: ChatRepository? = null,
    private val scope: CoroutineScope? = null,
    private val compileCandidate: ((WorkflowDocument) -> List<String>)? = null,
    private val applyCandidate: ((String, AssistantContextSnapshot, String) -> Boolean)? = null,
    private val providerCall: (suspend (DeepSeekRequest) -> ByteArray)? = null,
    private val onModelChanged: ((DeepSeekModel) -> Unit)? = null,
    val attachments: AssistantAttachmentImporter? = null,
    private val applyImageCandidate: (suspend (String, AssistantContextSnapshot, String, List<AssistantImageAddition>, List<AssistantAttachment>) -> Boolean)? = null,
    private val onDefaultEffortChanged: ((DeepSeekEffort) -> Unit)? = null,
    private var contextSource: (() -> AssistantContextCapture)? = null,
) {
    private val attachmentWork = kotlinx.coroutines.sync.Mutex()
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
    var providerEnabled by mutableStateOf(false)
        private set
    var connectionTestStatus by mutableStateOf<String?>(null)
        private set
    var testingConnection by mutableStateOf(false)
        private set
    var defaultModel by mutableStateOf(DeepSeekModel.FLASH)
        private set
    var defaultEffort by mutableStateOf(DeepSeekEffort.OFF)
        private set
    val selectedModel: DeepSeekModel get() = conversation?.model ?: defaultModel
    val usesProvider: Boolean get() = providerCall != null
    val isResponding: Boolean get() = requestId != null || pendingRequestId != null || testingConnection
    private var providerJob: Job? = null
    private var imageApplyJob: Job? = null
    var isApplyingImages by mutableStateOf(false)
        private set
    private var pendingRequestId: String? = null
    private var applyGeneration = 0
    private var draftSave: Job? = null
    var importingAttachments by mutableStateOf(false)
        private set
    private var blockingWrites by mutableStateOf(0)
    private val savedIds = mutableSetOf<String>()

    // One ordered writer prevents an older composer save from replacing a newer sent turn.
    private val writes = Channel<suspend () -> Unit>(64)
    val persistent: Boolean get() = repository != null
    val canChange: Boolean get() = !isLoading && blockingWrites == 0 && storageError == null && !isApplyingImages
    val conversation: PreviewConversation? get() = conversations.firstOrNull { it.id == selectedId }
    val history: List<PreviewConversation> get() = conversations.filter { it.workflowId == context.workflowId }
        .sortedWith(compareByDescending<PreviewConversation> { it.pinned }.thenByDescending { it.updatedAtMs })
    val canSend: Boolean get() = isOpen && (!usesProvider || providerEnabled) && canChange && !isResponding && conversation?.let {
        !importingAttachments && (it.composer.isNotBlank() || it.composerAttachments.isNotEmpty()) && it.composer.length <= MAX_PROMPT_LENGTH &&
            (it.model == DeepSeekModel.FLASH || it.composerAttachments.none { attachment -> attachment.needsVision })
    } == true

    init {
        require((repository == null && providerCall == null) || scope != null)
        if (repository != null) {
            scope!!.launch { for (write in writes) write() }
            reload()
        }
    }

    // Consent belongs to this ViewModel lifetime; it is never restored or inferred from a saved key.
    fun enableProvider() {
        if (usesProvider) providerEnabled = true
    }

    fun disableProvider() {
        connectionTestStatus = null
        providerEnabled = false
        stop(PreviewReplyStatus.INTERRUPTED)
    }

    fun testConnection() {
        if (!providerEnabled || providerCall == null || isResponding) return
        val generation = applyGeneration
        testingConnection = true
        connectionTestStatus = null
        val model = defaultModel
        providerJob = requireNotNull(scope).launch {
            try {
                val request = DeepSeekRequest.connectionTest(model)
                val bytes = providerCall(request)
                if (testingConnection && providerEnabled && generation == applyGeneration) {
                    val response = AssistantResponseCodec.decode(bytes, request.requestId, request.conversationId, request.baseSnapshotId)
                    require(response.outcome == AssistantOutcome.EXPLANATION && response.text.length <= 512)
                    connectionTestStatus = "Connected."
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (testingConnection && generation == applyGeneration) connectionTestStatus = (error as? DeepSeekException)?.reason?.message ?: "Connection test failed. No message was sent from your chat."
            } finally {
                if (generation == applyGeneration) testingConnection = false
            }
        }
    }

    fun dispose() {
        imageApplyJob?.cancel()
        providerEnabled = false
        testingConnection = false
        applyGeneration++
        requestId = null
        pendingRequestId = null
        providerJob?.cancel()
        providerJob = null
        draftSave?.cancel()
    }

    fun reload() {
        if (repository == null || pendingWrites != 0) return
        draftSave?.cancel()
        applyGeneration++
        testingConnection = false
        requestId = null
        pendingRequestId = null
        providerJob?.cancel()
        providerJob = null
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
                    turns = chat.turns.mapIndexed { index, it ->
                        when {
                            it.status == PreviewReplyStatus.RESPONDING -> it.copy(status = PreviewReplyStatus.INTERRUPTED)
                            it.proposal?.candidateJson != null -> it.copy(proposal = AssistantCandidateValidator.validate(it.baseSnapshot, it.proposal.candidateJson, compileCandidate, it.proposal.imageAdditions, chat.turns.take(index + 1).flatMap { turn -> turn.attachments }))
                            else -> it
                        }
                    },
                )
                if (recovered != chat) store.save(encode(recovered), create = false)
                recovered
            }
        }
        conversations = loaded
        pruneAttachments()
        savedIds.clear()
        savedIds.addAll(loaded.map { it.id })
        selectedId = history.maxByOrNull { it.updatedAtMs }?.id
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
        selectedId = conversations.filter { it.workflowId == currentContext.workflowId }.maxByOrNull { it.updatedAtMs }?.id
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
                conversation?.let { persist(it, blocksChanges = false) }
            }
        }
    }

    fun importAttachments(uris: List<android.net.Uri>, chatId: String, onFinished: (() -> Unit)? = null) {
        val importer = attachments
        if (importer == null || importingAttachments || !canChange || selectedId != chatId) {
            onFinished?.invoke()
            return
        }
        importingAttachments = true
        inputError = null
        requireNotNull(scope).launch {
            try {
                attachmentWork.lock()
                try {
                    val imported = kotlinx.coroutines.runInterruptible(Dispatchers.IO) { uris.map(importer::import) }
                    if (selectedId == chatId && canChange && isOpen) {
                        update { it.copy(composerAttachments = it.composerAttachments + imported) }
                        flush()
                    }
                } finally {
                    attachmentWork.unlock()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                inputError = error.message?.take(200) ?: "This attachment could not be added."
            } finally {
                importingAttachments = false
                onFinished?.invoke()
            }
        }
    }

    fun attachmentError(message: String) {
        inputError = message
    }

    fun removeAttachment(id: String) {
        if (!canChange || importingAttachments) return
        update { it.copy(composerAttachments = it.composerAttachments.filterNot { attachment -> attachment.id == id }) }
        flush()
    }

    fun replaceAttachment(id: String, replacement: AssistantAttachment, chatId: String): Boolean {
        if (!canChange || importingAttachments || selectedId != chatId || conversation?.composerAttachments?.none { it.id == id } != false) return false
        replacement.validate()
        update { it.copy(composerAttachments = it.composerAttachments.map { attachment -> if (attachment.id == id) replacement else attachment }) }
        flush()
        return true
    }

    internal suspend fun editAttachment(id: String, chatId: String, create: () -> AssistantAttachment): Boolean {
        if (!canChange || importingAttachments || selectedId != chatId) return false
        importingAttachments = true
        try {
            return attachmentWork.withLock {
                val replacement = kotlinx.coroutines.runInterruptible(Dispatchers.IO, create)
                importingAttachments = false
                replaceAttachment(id, replacement, chatId)
            }
        } finally {
            importingAttachments = false
        }
    }

    private suspend fun pruneAttachments() {
        val importer = attachments ?: return
        attachmentWork.lock()
        try {
            val referenced = attachmentIds()
            withContext(Dispatchers.IO) { importer.store.prune(referenced) }
        } finally {
            attachmentWork.unlock()
        }
    }

    private fun attachmentIds(): Set<String> = conversations.flatMap { it.composerAttachments + it.turns.flatMap { turn -> turn.attachments } }.mapTo(mutableSetOf()) { it.id }

    fun setEffort(effort: DeepSeekEffort) {
        inputError = null
        if (!usesProvider || !canChange || isResponding) return
        update { it.copy(effort = effort) }
        flush()
    }

    fun restoreModelPreference(model: DeepSeekModel) {
        defaultModel = model
    }

    fun restoreEffortPreference(effort: DeepSeekEffort) {
        defaultEffort = effort
    }

    fun chooseDefaultModel(model: DeepSeekModel) {
        if (!usesProvider) return
        defaultModel = model
        onModelChanged?.invoke(model)
    }

    fun chooseDefaultEffort(effort: DeepSeekEffort) {
        if (!usesProvider) return
        defaultEffort = effort
        onDefaultEffortChanged?.invoke(effort)
    }

    fun setModel(model: DeepSeekModel) {
        inputError = null
        if (!usesProvider || !canChange || isResponding) return
        if (conversation == null) {
            chooseDefaultModel(model)
            return
        }
        update { it.copy(model = model) }
        flush()
    }

    fun newChat() {
        if (!canChange || conversation?.let { it.workflowId == context.workflowId && it.turns.isEmpty() } == true) return
        stop(PreviewReplyStatus.INTERRUPTED)
        createChat()
    }

    private fun createChat() {
        val chat = PreviewConversation(UUID.randomUUID().toString(), context.workflowId, context = context, model = defaultModel, effort = defaultEffort)
        conversations = conversations + chat
        selectedId = chat.id
        persist(chat)
    }

    fun select(id: String) {
        if (!canChange || history.none { it.id == id }) return
        stop(PreviewReplyStatus.INTERRUPTED)
        selectedId = id
    }

    fun rename(title: String, id: String? = selectedId) {
        if (!canChange || title.isBlank() || title.length > 80) return
        changeHistory(id) { it.copy(title = title.trim()) }
    }

    fun togglePin(id: String? = selectedId) {
        if (!canChange) return
        changeHistory(id) { it.copy(pinned = !it.pinned) }
    }

    private fun changeHistory(id: String?, transform: (PreviewConversation) -> PreviewConversation) {
        val chat = history.firstOrNull { it.id == id } ?: return
        val changed = transform(chat).copy(updatedAtMs = maxOf(chat.updatedAtMs, System.currentTimeMillis()))
        conversations = conversations.map { if (it.id == id) changed else it }
        if (id == selectedId) draftSave?.cancel()
        persist(changed)
    }

    fun canBranch(turn: PreviewTurn): Boolean = isOpen && canChange && !isResponding &&
        conversation?.turns?.any { it.id == turn.id } == true && turn.status == PreviewReplyStatus.COMPLETE

    /** Copies the chosen exchange and earlier messages, never authority to apply a copied proposal. */
    fun branch(turnId: String) {
        val source = conversation ?: return
        val index = source.turns.indexOfFirst { it.id == turnId }
        if (index < 0 || !canBranch(source.turns[index])) return
        val fork = source.copy(
            id = UUID.randomUUID().toString(),
            title = "${source.title.take(68)} · Branch",
            composer = "",
            composerAttachments = emptyList(),
            turns = source.turns.take(index + 1).map {
                it.copy(id = UUID.randomUUID().toString(), localBindingId = null, applyState = ProposalApplyState.NONE)
            },
            createdAtMs = System.currentTimeMillis(),
            updatedAtMs = System.currentTimeMillis(),
            pinned = false,
        )
        if (runCatching { encode(fork) }.isFailure) {
            inputError = "This branch is too large. Start a new chat."
            return
        }
        flush()
        conversations = conversations + fork
        selectedId = fork.id
        persist(fork)
    }

    fun canRegenerate(turn: PreviewTurn): Boolean = turn.provider == usesProvider && (!usesProvider || providerEnabled) &&
        if (turn.status == PreviewReplyStatus.COMPLETE) canBranch(turn) else conversation?.turns?.lastOrNull()?.id == turn.id && canRetry

    /** A completed reply is kept in its original chat. Regeneration uses its captured context in a branch. */
    fun regenerate(turnId: String) {
        val turn = conversation?.turns?.firstOrNull { it.id == turnId } ?: return
        if (!canRegenerate(turn)) return
        if (turn.status != PreviewReplyStatus.COMPLETE) {
            retry()
            return
        }
        val sourceId = selectedId
        branch(turnId)
        if (selectedId == sourceId) return
        val token = UUID.randomUUID().toString()
        updateLast {
            it.copy(
                reply = "", thinking = null, status = PreviewReplyStatus.RESPONDING, requestId = token, attempt = it.attempt + 1,
                outcome = null, responseError = null, proposal = null, proposalDismissed = false,
                localBindingId = if (turn.applyState == ProposalApplyState.NONE) turn.localBindingId else null,
                effort = requireNotNull(conversation).effort,
                model = requireNotNull(conversation).model,
            )
        }
        begin(token)
    }

    fun deleteCurrent() {
        selectedId?.let { deleteChats(setOf(it)) }
    }

    fun deleteChats(ids: Set<String>) {
        if (!canChange) return
        val targets = history.map { it.id }.filter(ids::contains).toSet()
        if (targets.isEmpty()) return
        if (selectedId in targets) stop(PreviewReplyStatus.INTERRUPTED)
        fun removed() {
            conversations = conversations.filterNot { it.id in targets }
            savedIds.removeAll(targets)
            if (selectedId in targets) selectedId = history.firstOrNull()?.id
        }
        if (repository == null) {
            removed()
        } else {
            enqueue {
                withContext(Dispatchers.IO) { repository.delete(targets) }
                removed()
                pruneAttachments()
            }
        }
    }

    fun clearHistory(workflowOnly: Boolean = false) {
        if (isLoading || pendingWrites != 0) return
        val workflowId = context.workflowId
        if (workflowOnly && (workflowId == null || loadFailed)) return
        draftSave?.cancel()
        imageApplyJob?.cancel()
        applyGeneration++
        testingConnection = false
        requestId = null
        pendingRequestId = null
        providerJob?.cancel()
        providerJob = null
        fun cleared() {
            val removed = conversations.filter { !workflowOnly || it.workflowId == workflowId }.map { it.id }.toSet()
            conversations = conversations.filterNot { it.id in removed }
            if (!workflowOnly || selectedId in removed) selectedId = null
            if (workflowOnly) savedIds.removeAll(removed) else savedIds.clear()
            storageError = null
            loadFailed = false
        }
        if (repository == null) {
            cleared()
        } else {
            enqueue {
                withContext(Dispatchers.IO) {
                    if (workflowOnly) repository.clear(requireNotNull(workflowId)) else repository.clear()
                }
                cleared()
                pruneAttachments()
            }
        }
    }

    val supportsApply: Boolean get() = applyCandidate != null

    fun canApply(turn: PreviewTurn): Boolean {
        if (!canChange || !isOpen || applyCandidate == null || isResponding) return false
        if (turn.status != PreviewReplyStatus.COMPLETE || turn.proposalDismissed || turn.applyState != ProposalApplyState.NONE || turn.proposal?.isValid != true || turn.localBindingId == null) return false
        if (turn.proposal.imageAdditions.isNotEmpty() && applyImageCandidate == null) return false
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
            if (turn.proposal!!.imageAdditions.isNotEmpty()) {
                isApplyingImages = true
                imageApplyJob = requireNotNull(scope).launch {
                    try {
                        val applied = generation == applyGeneration && isOpen && selectedId == chat.id &&
                            applyImageCandidate!!.invoke(turn.localBindingId!!, turn.baseSnapshot!!, turn.proposal.candidateJson!!, turn.proposal.imageAdditions, imagesThrough(chat, turn.id))
                        finishApply(chat.id, turnId, applied)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        finishApply(chat.id, turnId, false)
                    } finally {
                        isApplyingImages = false
                        imageApplyJob = null
                    }
                }
                return@persist
            }
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

    private fun finishApply(chatId: String, turnId: String, applied: Boolean) {
        conversations = conversations.map { chat ->
            if (chat.id == chatId) chat.copy(turns = chat.turns.map { turn -> if (turn.id == turnId) turn.copy(applyState = if (applied) ProposalApplyState.APPLIED else ProposalApplyState.REJECTED) else turn }, updatedAtMs = maxOf(chat.updatedAtMs, System.currentTimeMillis())) else chat
        }
        conversations.firstOrNull { it.id == chatId }?.let { persist(it) }
    }

    private fun imagesThrough(chat: PreviewConversation, turnId: String): List<AssistantAttachment> = chat.turns.take(chat.turns.indexOfFirst { it.id == turnId } + 1).flatMap { it.attachments }.distinctBy { it.id }

    internal fun proposalImages(turn: PreviewTurn): List<AssistantAttachment> = conversation?.let { imagesThrough(it, turn.id) }.orEmpty()

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
        val prompt = current.composer.trim().ifEmpty { "Look at the attached ${if (current.composerAttachments.all { it.kind == AttachmentKind.IMAGE }) "images" else "files"}." }
        val turn = PreviewTurn(UUID.randomUUID().toString(), prompt, scenario, baseSnapshot = capture.snapshot, localBindingId = capture.localBindingId, provider = usesProvider, effort = current.effort, model = current.model, attachments = current.composerAttachments)
        val updated = current.copy(
            title = if (current.turns.isEmpty() && current.title == "New chat") prompt.take(48) else current.title,
            composer = "",
            composerAttachments = emptyList(),
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
                if (chat.turns.last().provider) dispatchProvider(chat, token)
            }
        }
    }

    private fun dispatchProvider(chat: PreviewConversation, token: String) {
        if (!providerEnabled || providerCall == null) {
            stop(PreviewReplyStatus.INTERRUPTED)
            return
        }
        providerJob = requireNotNull(scope).launch {
            var request: DeepSeekRequest? = null
            try {
                val contents = if (chat.turns.all { it.attachments.isEmpty() }) {
                    emptyMap()
                } else {
                    kotlinx.coroutines.runInterruptible(Dispatchers.IO) {
                        val prepared = mutableMapOf<String, AttachmentContent>()
                        var size = 0L
                        var images = 0
                        for (turn in chat.turns.asReversed()) {
                            for (attachment in turn.attachments) {
                                if (attachment.id in prepared) continue
                                val content = requireNotNull(attachments) { "Attachments are unavailable." }.content(attachment)
                                size += content.images.sumOf { it.size.toLong() } + (content.text?.toByteArray()?.size ?: 0)
                                images += content.images.size
                                if (size > 34 * 1024 * 1024 || images > DeepSeekRequest.MAX_IMAGES) {
                                    require(turn.id != chat.turns.last().id) { "These attachments exceed DeepSeek's request size or 600-image limit. Send fewer files together." }
                                    break
                                }
                                prepared[attachment.id] = content
                            }
                            if (size > 34 * 1024 * 1024 || images > DeepSeekRequest.MAX_IMAGES) break
                        }
                        prepared
                    }
                }
                val notifyThinking: (AssistantThinking) -> Unit = { thinking ->
                    // Network callbacks run on IO. State belongs to the ViewModel's main scope.
                    scope.launch { receiveThinking(token, thinking) }
                }
                request = if (contents.isEmpty()) providerRequest(chat, contents, notifyThinking) else withContext(Dispatchers.Default) { providerRequest(chat, contents, notifyThinking) }
                val bytes = providerCall(request)
                request.latestThinking?.let { receiveThinking(token, it) }
                receive(token, bytes)
            } catch (cancelled: CancellationException) {
                // Owner cancellation cannot publish a result. A persisted unfinished turn recovers interrupted.
                if (requestId == token) stop(PreviewReplyStatus.INTERRUPTED)
                throw cancelled
            } catch (error: Exception) {
                if (requestId == token) {
                    request?.latestThinking?.let { receiveThinking(token, it) }
                    updateLast { it.copy(status = PreviewReplyStatus.ERROR, responseError = (error as? DeepSeekException)?.reason?.message ?: (error as? IllegalArgumentException)?.message?.take(200) ?: "The AI request failed. Your prompt is kept; retry is explicit.") }
                    requestId = null
                    flush()
                }
            }
        }
    }

    internal fun providerRequest(chat: PreviewConversation, contents: Map<String, AttachmentContent> = emptyMap(), onThinking: ((AssistantThinking) -> Unit)? = null): DeepSeekRequest {
        val current = chat.turns.last()
        val history = chat.turns.dropLast(1).filter {
            it.provider && it.status == PreviewReplyStatus.COMPLETE && it.outcome != null &&
                (it.outcome != AssistantOutcome.PROPOSAL || it.proposal?.isValid == true) && it.attachments.all { attachment -> attachment.id in contents } &&
                (current.model == DeepSeekModel.FLASH || it.attachments.none { attachment -> attachment.needsVision })
        }.map { turn ->
            DeepSeekHistoryTurn(
                turn.prompt,
                AssistantResponse(
                    1,
                    turn.requestId,
                    chat.id,
                    turn.responseBaseId(chat),
                    requireNotNull(turn.outcome),
                    turn.reply,
                    turn.proposal?.candidateJson?.let { WorkflowJson.format.parseToJsonElement(it).jsonObject },
                ),
                turn.attachments.map { contents.getValue(it.id) },
            )
        }
        return DeepSeekRequest.create(current.requestId, chat.id, current.responseBaseId(chat), current.prompt, current.baseSnapshot, history, streaming = true, effort = current.effort, onThinking = onThinking, model = current.model, attachments = current.attachments.map { contents.getValue(it.id) })
    }

    private fun receiveThinking(token: String, thinking: AssistantThinking) {
        if (requestId != token) return
        val turn = conversation?.turns?.lastOrNull() ?: return
        if (turn.requestId != token || turn.status != PreviewReplyStatus.RESPONDING || !turn.provider || turn.effort == DeepSeekEffort.OFF) return
        updateLast { it.copy(thinking = thinking) }
    }

    fun advance(token: String) {
        if (requestId != token) return
        val chat = conversation ?: return
        val turn = chat.turns.lastOrNull() ?: return
        if (turn.provider) return
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
            val proposal = response.candidate?.let { AssistantCandidateValidator.validate(turn.baseSnapshot, AssistantWireWorkflow.restoreLocalFields(turn.baseSnapshot, it).toString(), compileCandidate, response.imageAdditions, imagesThrough(chat, turn.id)) }
            updateLast { it.copy(reply = response.text, status = PreviewReplyStatus.COMPLETE, outcome = response.outcome, responseError = null, proposal = proposal) }
        }
        requestId = null
        flush()
    }

    fun stop(status: PreviewReplyStatus = PreviewReplyStatus.STOPPED) {
        imageApplyJob?.cancel()
        applyGeneration++
        if (testingConnection) connectionTestStatus = "Connection test interrupted. Retry only when ready; charges may still apply."
        testingConnection = false
        if (requestId != null || pendingRequestId != null) {
            requestId = null
            pendingRequestId = null
            updateLast { it.copy(status = status) }
        }
        providerJob?.cancel()
        providerJob = null
        flush()
    }

    val canRetry: Boolean get() = conversation?.turns?.lastOrNull()?.let {
        isOpen && canChange && !isResponding && it.status !in listOf(PreviewReplyStatus.COMPLETE, PreviewReplyStatus.RESPONDING) &&
            it.provider == usesProvider && (!it.provider || providerEnabled)
    } == true

    fun retry() {
        if (!canRetry) return
        val token = UUID.randomUUID().toString()
        updateLast { it.copy(reply = "", thinking = null, status = PreviewReplyStatus.RESPONDING, requestId = token, attempt = it.attempt + 1, outcome = null, responseError = null, proposal = null, effort = requireNotNull(conversation).effort, model = requireNotNull(conversation).model) }
        begin(token)
    }

    private fun flush() {
        draftSave?.cancel()
        if (!isLoading && storageError == null) conversation?.let { persist(it) }
    }

    private fun persist(chat: PreviewConversation, blocksChanges: Boolean = true, onSaved: () -> Unit = {}) {
        if (repository == null) {
            onSaved()
            return
        }
        enqueue(blocksChanges) {
            val create = chat.id !in savedIds
            withContext(Dispatchers.IO) { repository.save(encode(chat), create) }
            savedIds.add(chat.id)
            onSaved()
        }
    }

    private fun enqueue(blocksChanges: Boolean = true, operation: suspend () -> Unit) {
        pendingWrites++
        if (blocksChanges) blockingWrites++
        check(
            writes.trySend {
                try {
                    operation()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    applyGeneration++
                    testingConnection = false
                    requestId = null
                    pendingRequestId = null
                    providerJob?.cancel()
                    providerJob = null
                    conversations = conversations.map { chat ->
                        chat.copy(turns = chat.turns.map { if (it.status == PreviewReplyStatus.RESPONDING) it.copy(status = PreviewReplyStatus.INTERRUPTED) else it })
                    }
                    storageError = "Chat storage failed. Your visible text is kept in memory. Retry saving or reload saved history; no message will be resent."
                    isLoading = false
                } finally {
                    pendingWrites--
                    if (blocksChanges) blockingWrites--
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
        // Bound pasted input by the request transport budget, rather than a small chat character quota.
        const val MAX_PROMPT_LENGTH = DeepSeekRequest.MAX_BYTES / 4
        const val MAX_REPLY_LENGTH = AssistantResponseCodec.MAX_BYTES
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
            require(chat.title.length in 1..80 && chat.composer.length <= MAX_PROMPT_LENGTH)
            require(chat.context.name.length <= 1000 && chat.createdAtMs >= 0 && chat.updatedAtMs >= chat.createdAtMs)
            require(chat.draftId.length in 1..128 && chat.context.workflowId == chat.workflowId)
            require(chat.turns.map { it.id }.distinct().size == chat.turns.size)
            chat.composerAttachments.forEach(AssistantAttachment::validate)
            chat.turns.forEachIndexed { index, turn ->
                turn.attachments.forEach(AssistantAttachment::validate)
                turn.baseSnapshot?.let { snapshot ->
                    require(snapshot.workflowId == chat.workflowId)
                    snapshot.validate()
                }
                require(UUID.fromString(turn.id).toString() == turn.id && UUID.fromString(turn.requestId).toString() == turn.requestId)
                require(turn.prompt.isNotBlank() && turn.prompt.length <= MAX_PROMPT_LENGTH && turn.reply.length <= MAX_REPLY_LENGTH && turn.attempt > 0 && turn.createdAtMs >= 0)
                turn.thinking?.let { require(turn.provider && turn.effort != DeepSeekEffort.OFF) }
                require((turn.outcome == AssistantOutcome.PROPOSAL) == (turn.proposal != null))
                require(turn.localBindingId == null || turn.localBindingId.length in 1..128)
                require(turn.applyState == ProposalApplyState.NONE || (turn.proposal != null && turn.baseSnapshot != null && turn.localBindingId != null && !turn.proposalDismissed))
                turn.proposal?.let { proposal ->
                    require(proposal.imageAdditions.size <= 64)
                    proposal.imageAdditions.forEach(AssistantImageAddition::validate)
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
        "## How workflows work\n\n" +
            "- **Steps** run in order.\n- **Wait** pauses before the next action.\n" +
            "- **Conditions** choose a branch.\n- **Repeat** runs a group of steps again.\n\n" +
            "> Sample reply. Your workflow hasn't changed."

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

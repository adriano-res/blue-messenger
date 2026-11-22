package com.bluemessenger.presentation.chat

import com.bluemessenger.domain.concurrency.CoroutineDispatchers
import com.bluemessenger.domain.enums.SessionState
import com.bluemessenger.domain.model.Conversation
import com.bluemessenger.domain.model.Peer
import com.bluemessenger.domain.ports.logging.AppLogger
import com.bluemessenger.domain.primitives.ConversationId
import com.bluemessenger.domain.primitives.MessageId
import com.bluemessenger.domain.primitives.PeerId
import com.bluemessenger.domain.usecase.message.FlushPendingMessagesUseCase
import com.bluemessenger.domain.usecase.message.ObserveIncomingFramesUseCase
import com.bluemessenger.domain.usecase.message.ObserveMessagesUseCase
import com.bluemessenger.domain.usecase.message.SendMessageUseCase
import com.bluemessenger.domain.usecase.peer.ObserveConversationsUseCase
import com.bluemessenger.domain.usecase.peer.VerifyPeerSafetyNumberUseCase
import com.bluemessenger.domain.usecase.session.EstablishSecureSessionUseCase
import com.bluemessenger.domain.usecase.session.ManageKeepAliveAndReconnectUseCase
import com.bluemessenger.presentation.PresentationError
import com.bluemessenger.presentation.constants.PresentationConstants
import com.bluemessenger.presentation.discovery.createPresentationScope
import com.bluemessenger.presentation.toPresentationError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * State holder managing an open encrypted chat thread, Bluetooth E2EE session lifecycle, incoming
 * frame observation, Keep-Alive monitoring, message sending/retrying, and Safety Number
 * verification.
 *
 * Thread safety: State updates are serialized via [MutableStateFlow.update]; UI observers collect
 * on [CoroutineDispatchers.main] while heavy cryptographic Safety Number derivation and QR encoding
 * execute on [CoroutineDispatchers.default] without blocking the Main Thread.
 *
 * Failure behavior: Maps domain failures to [PresentationError] on [uiState], preserves
 * [CancellationException], cancels peer-scoped jobs on peer switches/disconnect/clear, and rejects
 * sending before a conversation exists.
 */
@Suppress("LongParameterList", "TooManyFunctions")
class ChatViewModel(
    private val establishSecureSessionUseCase: EstablishSecureSessionUseCase,
    private val observeConversationsUseCase: ObserveConversationsUseCase,
    private val observeMessagesUseCase: ObserveMessagesUseCase,
    private val observeIncomingFramesUseCase: ObserveIncomingFramesUseCase,
    private val sendMessageUseCase: SendMessageUseCase,
    private val flushPendingMessagesUseCase: FlushPendingMessagesUseCase,
    private val manageKeepAliveAndReconnectUseCase: ManageKeepAliveAndReconnectUseCase,
    private val verifyPeerSafetyNumberUseCase: VerifyPeerSafetyNumberUseCase,
    private val dispatchers: CoroutineDispatchers,
    private val logger: AppLogger,
    private val scope: CoroutineScope = createPresentationScope(dispatchers),
) {
    private val mutableUiState: MutableStateFlow<ChatUiState> = MutableStateFlow(ChatUiState())

    /**
     * Reactive stream of immutable [ChatUiState] snapshots consumed by [ChatScreen].
     */
    val uiState: StateFlow<ChatUiState> = mutableUiState.asStateFlow()

    private var peerScope: CoroutineScope? = null
    private var conversationObservationJob: Job? = null
    private var messagesObservationJob: Job? = null
    private var connectJob: Job? = null
    private var disconnectJob: Job? = null
    private var safetyNumberJob: Job? = null
    private var verifyPeerJob: Job? = null
    private var observedConversationId: ConversationId? = null
    private var activeSessionScope: CoroutineScope? = null
    private var activeSessionConversationId: ConversationId? = null
    private var wasSessionActive: Boolean = false

    /**
     * Dispatches a [ChatIntent] from the UI layer.
     */
    fun onIntent(intent: ChatIntent) {
        when (intent) {
            is ChatIntent.OpenPeer -> openPeer(intent.peer, intent.conversationId, intent.sessionState)
            ChatIntent.Connect -> connect()
            ChatIntent.Disconnect -> disconnect()
            is ChatIntent.UpdateMessageInput -> updateMessageInput(intent.value)
            ChatIntent.SendMessage -> sendMessage()
            is ChatIntent.RetryFailedMessage -> retryFailedMessage(intent.messageId)
            ChatIntent.OpenSafetyNumberDialog -> openSafetyNumberDialog()
            ChatIntent.DismissSafetyNumberDialog -> dismissSafetyNumberDialog()
            ChatIntent.MarkPeerVerified -> markPeerVerified()
            ChatIntent.DismissError -> mutableUiState.update { it.copy(error = null) }
        }
    }

    /**
     * Cancels all peer observations, in-flight operations, active E2EE session loops, and the
     * managed ViewModel [CoroutineScope].
     */
    fun clear() {
        cancelPeerScope()
        cancelActiveSessionJobs()
        scope.cancel()
    }

    private fun openPeer(
        peer: Peer,
        conversationId: ConversationId?,
        sessionState: SessionState,
    ) {
        val current = mutableUiState.value
        if (current.peer?.id == peer.id) {
            val resolvedConvId = current.conversationId ?: conversationId
            if (resolvedConvId != null && observedConversationId != resolvedConvId) {
                mutableUiState.update { it.copy(conversationId = resolvedConvId) }
                ensureMessagesObserved(resolvedConvId)
            }
            return
        }
        cancelPeerScope()
        cancelActiveSessionJobs()
        peerScope = createChildScope(dispatchers.main)
        mutableUiState.value =
            ChatUiState(
                peer = peer,
                conversationId = conversationId,
                sessionState = sessionState,
                isConnecting = sessionState == SessionState.CONNECTING || sessionState == SessionState.HANDSHAKING,
            )
        if (conversationId != null) {
            ensureMessagesObserved(conversationId)
            if (sessionState == SessionState.SECURE_ACTIVE) {
                startActiveSessionLoops(
                    Conversation(
                        id = conversationId,
                        peer = peer,
                        state = sessionState,
                        updatedAt = peer.lastSeenAt,
                    ),
                )
            }
        }
        observePeerConversation(peer.id)
    }

    private fun observePeerConversation(peerId: PeerId) {
        conversationObservationJob =
            requirePeerScope().launch {
                observeConversationsUseCase()
                    .catch { cause ->
                        if (cause is CancellationException) throw cause
                        logger.error(
                            tag = PresentationConstants.TAG_CHAT_VIEW_MODEL,
                            message = "Failed to observe peer conversation state.",
                            throwable = cause,
                            context = mapOf("peerId" to peerId.toString()),
                        )
                        updateStateForPeer(peerId) { it.copy(error = cause.toPresentationError()) }
                    }.collect { conversations ->
                        conversations.firstOrNull { it.peer.id == peerId }?.let(::applyConversationSnapshot)
                    }
            }
    }

    private fun applyConversationSnapshot(conversation: Conversation) {
        if (mutableUiState.value.peer?.id != conversation.peer.id) {
            return
        }
        val isTransitioning =
            conversation.state == SessionState.CONNECTING ||
                conversation.state == SessionState.HANDSHAKING
        if (conversation.state == SessionState.SECURE_ACTIVE) {
            wasSessionActive = true
            if (activeSessionConversationId != conversation.id || activeSessionScope?.isActive != true) {
                startActiveSessionLoops(conversation)
            }
        } else if (conversation.state == SessionState.DISCONNECTED && wasSessionActive) {
            cancelActiveSessionJobs()
        }
        mutableUiState.update { state ->
            if (state.peer?.id != conversation.peer.id) {
                state
            } else {
                state.copy(
                    peer = conversation.peer,
                    conversationId = conversation.id,
                    sessionState = conversation.state,
                    isConnecting = isTransitioning,
                    safetyNumberDialog = state.safetyNumberDialog?.copy(isVerified = conversation.peer.isVerified),
                )
            }
        }
        ensureMessagesObserved(conversation.id)
    }

    private fun ensureMessagesObserved(conversationId: ConversationId) {
        if (observedConversationId == conversationId && messagesObservationJob?.isActive == true) {
            return
        }
        val activePeerId = mutableUiState.value.peer?.id ?: return
        messagesObservationJob?.cancel()
        observedConversationId = conversationId
        messagesObservationJob =
            requirePeerScope().launch {
                observeMessagesUseCase(conversationId)
                    .catch { cause ->
                        if (cause is CancellationException) throw cause
                        logger.error(
                            tag = PresentationConstants.TAG_CHAT_VIEW_MODEL,
                            message = "Failed to observe messages for conversation.",
                            throwable = cause,
                            context = mapOf("conversationId" to conversationId.toString()),
                        )
                        updateStateForPeer(activePeerId) { it.copy(error = cause.toPresentationError()) }
                    }.collect { messages ->
                        updateStateForPeer(activePeerId) { it.copy(messages = messages) }
                    }
            }
    }

    private fun connect() {
        val currentState = mutableUiState.value
        val peer = currentState.peer ?: return
        if (currentState.isConnecting || connectJob?.isActive == true || disconnectJob?.isActive == true) {
            return
        }
        mutableUiState.update { it.copy(isConnecting = true, error = null) }
        connectJob =
            requirePeerScope().launch {
                establishSecureSessionUseCase.connectAndEstablish(peer).fold(
                    onSuccess = { conversation ->
                        applyConversationSnapshot(conversation)
                    },
                    onFailure = { failure ->
                        updateStateForPeer(peer.id) {
                            it.copy(
                                isConnecting = false,
                                error = failure.toPresentationError(),
                            )
                        }
                    },
                )
            }
    }

    private fun startActiveSessionLoops(conversation: Conversation) {
        cancelActiveSessionJobs()
        wasSessionActive = true
        activeSessionConversationId = conversation.id
        val sessionScope = createChildScope(dispatchers.default)
        activeSessionScope = sessionScope
        observeIncomingFramesUseCase(
            scope = sessionScope,
            peerId = conversation.peer.id,
            conversationId = conversation.id,
            onPongReceived = manageKeepAliveAndReconnectUseCase::onPongReceived,
        )
        manageKeepAliveAndReconnectUseCase.start(
            scope = sessionScope,
            peer = conversation.peer,
            conversationId = conversation.id,
        )
        sessionScope.launch {
            flushPendingMessagesUseCase(conversation.peer.id, conversation.id).onFailure { flushError ->
                logger.warn(
                    tag = PresentationConstants.TAG_CHAT_VIEW_MODEL,
                    message = "Failed to flush pending messages after session activation.",
                    throwable = flushError,
                    context = mapOf("conversationId" to conversation.id.toString()),
                )
            }
        }
    }

    private fun disconnect() {
        val peer = mutableUiState.value.peer ?: return
        if (disconnectJob?.isActive == true) {
            return
        }
        connectJob?.cancel()
        connectJob = null
        cancelSafetyNumberJobs()
        cancelActiveSessionJobs()
        disconnectJob =
            requirePeerScope().launch {
                establishSecureSessionUseCase.disconnect(peer.id).fold(
                    onSuccess = {
                        updateStateForPeer(peer.id) {
                            it.copy(
                                sessionState = SessionState.DISCONNECTED,
                                isConnecting = false,
                            )
                        }
                    },
                    onFailure = { failure ->
                        updateStateForPeer(peer.id) {
                            it.copy(
                                isConnecting = false,
                                error = failure.toPresentationError(),
                            )
                        }
                    },
                )
            }
    }

    private fun updateMessageInput(value: String) {
        mutableUiState.update { state ->
            state.copy(
                messageInput = value,
                error = if (state.error is PresentationError.Validation) null else state.error,
            )
        }
    }

    private fun sendMessage() {
        val state = mutableUiState.value
        val peer = state.peer ?: return
        val conversationId = state.conversationId
        if (conversationId == null) {
            mutableUiState.update {
                it.copy(
                    error =
                        PresentationError.Validation(
                            "Establish a Bluetooth session with ${peer.nickname} before sending the first message.",
                        ),
                )
            }
            return
        }
        val plaintext = state.messageInput
        mutableUiState.update { it.copy(isSending = true, error = null) }
        requirePeerScope().launch {
            sendMessageUseCase(peer.id, conversationId, plaintext, ackTimeoutScope = scope).fold(
                onSuccess = {
                    updateStateForPeer(peer.id) { current ->
                        current.copy(
                            messageInput = if (current.messageInput == plaintext) "" else current.messageInput,
                            isSending = false,
                        )
                    }
                },
                onFailure = { failure ->
                    updateStateForPeer(peer.id) { current ->
                        current.copy(
                            isSending = false,
                            error = failure.toPresentationError(),
                        )
                    }
                },
            )
        }
    }

    private fun retryFailedMessage(messageId: MessageId) {
        val peer = mutableUiState.value.peer ?: return
        mutableUiState.update { it.copy(error = null) }
        requirePeerScope().launch {
            sendMessageUseCase.retryFailedMessage(peer.id, messageId, ackTimeoutScope = scope)
                .onFailure { failure ->
                    updateStateForPeer(peer.id) { it.copy(error = failure.toPresentationError()) }
                }
        }
    }

    private fun openSafetyNumberDialog() {
        val peer = mutableUiState.value.peer ?: return
        cancelSafetyNumberJobs()
        mutableUiState.update {
            it.copy(
                safetyNumberDialog =
                    SafetyNumberDialogState(
                        isVerified = peer.isVerified,
                        isLoading = true,
                    ),
            )
        }
        safetyNumberJob =
            requirePeerScope().launch {
                val safetyOutcome =
                    withContext(dispatchers.default) {
                        verifyPeerSafetyNumberUseCase.computeSafetyNumber(peer.id).map { safetyNumber ->
                            safetyNumber to QrCodeMatrixGenerator.encode(safetyNumber)
                        }
                    }
                safetyOutcome.fold(
                    onSuccess = { (safetyNumber, qrResult) ->
                        updateDialogForPeer(peer.id) { currentPeer, _ ->
                            SafetyNumberDialogState(
                                safetyNumber = safetyNumber,
                                qrCodeMatrix = qrResult.getOrNull(),
                                isVerified = currentPeer.isVerified,
                                isLoading = false,
                                error = qrResult.exceptionOrNull()?.toPresentationError(),
                            )
                        }
                    },
                    onFailure = { failure ->
                        updateDialogForPeer(peer.id) { currentPeer, _ ->
                            SafetyNumberDialogState(
                                isVerified = currentPeer.isVerified,
                                isLoading = false,
                                error = failure.toPresentationError(),
                            )
                        }
                    },
                )
            }
    }

    private fun dismissSafetyNumberDialog() {
        cancelSafetyNumberJobs()
        mutableUiState.update { it.copy(safetyNumberDialog = null) }
    }

    private fun markPeerVerified() {
        val peer = mutableUiState.value.peer ?: return
        if (verifyPeerJob?.isActive == true) {
            return
        }
        mutableUiState.update { state ->
            state.copy(
                safetyNumberDialog = state.safetyNumberDialog?.copy(isVerifying = true, error = null),
            )
        }
        verifyPeerJob =
            requirePeerScope().launch {
                verifyPeerSafetyNumberUseCase.markPeerVerified(peer.id, verified = true).fold(
                    onSuccess = {
                        updateStateForPeer(peer.id) { state ->
                            state.copy(
                                peer = state.peer?.copy(isVerified = true),
                                safetyNumberDialog =
                                    state.safetyNumberDialog?.copy(
                                        isVerified = true,
                                        isVerifying = false,
                                        error = null,
                                    ),
                            )
                        }
                    },
                    onFailure = { failure ->
                        updateDialogForPeer(peer.id) { _, dialog ->
                            dialog.copy(
                                isVerifying = false,
                                error = failure.toPresentationError(),
                            )
                        }
                    },
                )
            }
    }

    private fun requirePeerScope(): CoroutineScope =
        peerScope?.takeIf { it.isActive } ?: createChildScope(dispatchers.main).also { peerScope = it }

    private fun createChildScope(dispatcher: CoroutineDispatcher): CoroutineScope =
        CoroutineScope(SupervisorJob(scope.coroutineContext[Job]) + dispatcher)

    private fun updateStateForPeer(
        peerId: PeerId,
        transform: (ChatUiState) -> ChatUiState,
    ) {
        mutableUiState.update { current ->
            if (current.peer?.id == peerId) transform(current) else current
        }
    }

    private fun updateDialogForPeer(
        peerId: PeerId,
        transform: (Peer, SafetyNumberDialogState) -> SafetyNumberDialogState,
    ) {
        mutableUiState.update { current ->
            val activePeer = current.peer
            val activeDialog = current.safetyNumberDialog
            if (activePeer?.id == peerId && activeDialog != null) {
                current.copy(safetyNumberDialog = transform(activePeer, activeDialog))
            } else {
                current
            }
        }
    }

    private fun cancelSafetyNumberJobs() {
        safetyNumberJob?.cancel()
        safetyNumberJob = null
        verifyPeerJob?.cancel()
        verifyPeerJob = null
    }

    private fun cancelPeerScope() {
        peerScope?.cancel()
        peerScope = null
        conversationObservationJob = null
        messagesObservationJob = null
        connectJob = null
        disconnectJob = null
        safetyNumberJob = null
        verifyPeerJob = null
        observedConversationId = null
    }

    private fun cancelActiveSessionJobs() {
        activeSessionScope?.cancel()
        activeSessionScope = null
        activeSessionConversationId = null
        wasSessionActive = false
    }
}

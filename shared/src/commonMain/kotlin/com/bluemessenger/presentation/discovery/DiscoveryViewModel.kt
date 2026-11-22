package com.bluemessenger.presentation.discovery

import com.bluemessenger.domain.concurrency.CoroutineDispatchers
import com.bluemessenger.domain.ports.logging.AppLogger
import com.bluemessenger.domain.usecase.peer.DiscoverPeersUseCase
import com.bluemessenger.domain.usecase.peer.InitializeIdentityUseCase
import com.bluemessenger.domain.usecase.peer.ObserveConversationsUseCase
import com.bluemessenger.presentation.PresentationError
import com.bluemessenger.presentation.constants.PresentationConstants
import com.bluemessenger.presentation.toPresentationError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Creates a managed [CoroutineScope] bound to [CoroutineDispatchers.main] for presentation
 * ViewModels without leaking `GlobalScope`.
 */
fun createPresentationScope(dispatchers: CoroutineDispatchers): CoroutineScope {
    return CoroutineScope(SupervisorJob() + dispatchers.main)
}

/**
 * State holder managing local identity initialization, Bluetooth peer discovery and advertising,
 * and live conversation list observation.
 *
 * Thread safety: State updates are serialized via [MutableStateFlow.update] and asynchronous
 * work runs on the injected managed [CoroutineScope] and [CoroutineDispatchers].
 *
 * Failure behavior: Maps domain failures into [PresentationError] on [uiState], preserves
 * [CancellationException], and logs unexpected stream errors via [AppLogger].
 */
class DiscoveryViewModel(
    private val initializeIdentityUseCase: InitializeIdentityUseCase,
    private val discoverPeersUseCase: DiscoverPeersUseCase,
    private val observeConversationsUseCase: ObserveConversationsUseCase,
    private val dispatchers: CoroutineDispatchers,
    private val logger: AppLogger,
    private val scope: CoroutineScope = createPresentationScope(dispatchers),
) {
    private val mutableUiState: MutableStateFlow<DiscoveryUiState> = MutableStateFlow(DiscoveryUiState())

    /**
     * Reactive stream of immutable [DiscoveryUiState] snapshots consumed by [DiscoveryScreen].
     */
    val uiState: StateFlow<DiscoveryUiState> = mutableUiState.asStateFlow()

    private var loadIdentityJob: Job? = null
    private var submitIdentityJob: Job? = null

    init {
        observeScannerState()
        observeConversations()
        loadInitialState()
    }

    /**
     * Cancels all active observation and identity jobs and disposes the managed [CoroutineScope].
     */
    fun clear() {
        loadIdentityJob?.cancel()
        loadIdentityJob = null
        submitIdentityJob?.cancel()
        submitIdentityJob = null
        scope.cancel()
    }

    /**
     * Dispatches a [DiscoveryIntent] from the UI layer.
     */
    fun onIntent(intent: DiscoveryIntent) {
        when (intent) {
            DiscoveryIntent.LoadInitialState -> loadInitialState()
            is DiscoveryIntent.UpdateNicknameInput -> updateNicknameInput(intent.value)
            DiscoveryIntent.SubmitIdentity -> submitIdentity()
            DiscoveryIntent.StartDiscovery -> runScannerAction { discoverPeersUseCase.startDiscovery() }
            DiscoveryIntent.StopDiscovery -> runScannerAction { discoverPeersUseCase.stopDiscovery() }
            DiscoveryIntent.StartAdvertising -> startAdvertising()
            DiscoveryIntent.StopAdvertising -> runScannerAction { discoverPeersUseCase.stopAdvertising() }
            is DiscoveryIntent.SelectPeer ->
                mutableUiState.update { it.copy(selectedPeerId = intent.peer.id) }
            is DiscoveryIntent.SelectConversation ->
                mutableUiState.update { it.copy(selectedPeerId = intent.conversation.peer.id) }
            DiscoveryIntent.DismissError ->
                mutableUiState.update { it.copy(error = null) }
        }
    }

    private fun observeScannerState() {
        scope.launch(dispatchers.main) {
            discoverPeersUseCase.discoveredPeers.collect { peers ->
                mutableUiState.update { it.copy(discoveredPeers = peers) }
            }
        }
        scope.launch(dispatchers.main) {
            discoverPeersUseCase.isScanning.collect { scanning ->
                mutableUiState.update { it.copy(isScanning = scanning) }
            }
        }
        scope.launch(dispatchers.main) {
            discoverPeersUseCase.isAdvertising.collect { advertising ->
                mutableUiState.update { it.copy(isAdvertising = advertising) }
            }
        }
    }

    private fun observeConversations() {
        scope.launch(dispatchers.main) {
            observeConversationsUseCase()
                .catch { cause ->
                    if (cause is CancellationException) throw cause
                    logger.error(
                        tag = PresentationConstants.TAG_DISCOVERY_VIEW_MODEL,
                        message = "Failed to observe conversations in presentation layer.",
                        throwable = cause,
                    )
                    mutableUiState.update { it.copy(error = cause.toPresentationError()) }
                }.collect { conversations ->
                    mutableUiState.update { it.copy(conversations = conversations) }
                }
        }
    }

    private fun loadInitialState() {
        if (mutableUiState.value.isInitializingIdentity) {
            return
        }
        loadIdentityJob?.cancel()
        mutableUiState.update { it.copy(isLoadingIdentity = true, error = null) }
        loadIdentityJob =
            scope.launch(dispatchers.main) {
                initializeIdentityUseCase.loadExisting().fold(
                    onSuccess = { identity ->
                        mutableUiState.update { state ->
                            if (state.isInitializingIdentity) {
                                state.copy(isLoadingIdentity = false)
                            } else {
                                state.copy(
                                    localIdentity = identity ?: state.localIdentity,
                                    nicknameInput = identity?.nickname ?: state.nicknameInput,
                                    isLoadingIdentity = false,
                                )
                            }
                        }
                    },
                    onFailure = { failure ->
                        mutableUiState.update { state ->
                            state.copy(
                                isLoadingIdentity = false,
                                error = failure.toPresentationError(),
                            )
                        }
                    },
                )
            }
    }

    private fun updateNicknameInput(value: String) {
        mutableUiState.update { state ->
            state.copy(
                nicknameInput = value,
                error = if (state.error is PresentationError.Validation) null else state.error,
            )
        }
    }

    private fun submitIdentity() {
        if (mutableUiState.value.isInitializingIdentity) {
            return
        }
        loadIdentityJob?.cancel()
        loadIdentityJob = null
        val nickname = mutableUiState.value.nicknameInput
        mutableUiState.update {
            it.copy(
                isLoadingIdentity = false,
                isInitializingIdentity = true,
                error = null,
            )
        }
        submitIdentityJob =
            scope.launch(dispatchers.main) {
                initializeIdentityUseCase(nickname).fold(
                    onSuccess = { identity ->
                        mutableUiState.update { state ->
                            state.copy(
                                localIdentity = identity,
                                nicknameInput = identity.nickname,
                                isInitializingIdentity = false,
                                error = null,
                            )
                        }
                    },
                    onFailure = { failure ->
                        mutableUiState.update { state ->
                            state.copy(
                                isInitializingIdentity = false,
                                error = failure.toPresentationError(),
                            )
                        }
                    },
                )
            }
    }

    private fun startAdvertising() {
        val currentState = mutableUiState.value
        val nickname = currentState.localIdentity?.nickname ?: currentState.nicknameInput
        runScannerAction { discoverPeersUseCase.startAdvertising(nickname) }
    }

    private fun runScannerAction(action: suspend () -> Result<Unit>) {
        mutableUiState.update { it.copy(error = null) }
        scope.launch(dispatchers.main) {
            action().onFailure { failure ->
                mutableUiState.update { it.copy(error = failure.toPresentationError()) }
            }
        }
    }
}

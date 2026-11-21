package com.bluemessenger.domain.usecase

import com.bluemessenger.crypto.CryptoServiceImpl
import com.bluemessenger.crypto.model.IdentityKeyPair
import com.bluemessenger.domain.concurrency.CoroutineDispatchers
import com.bluemessenger.domain.enums.MessageStatus
import com.bluemessenger.domain.enums.SessionState
import com.bluemessenger.domain.error.DomainError
import com.bluemessenger.domain.error.DomainException
import com.bluemessenger.domain.error.StorageError
import com.bluemessenger.domain.error.TransportDisconnectedError
import com.bluemessenger.domain.error.asFailure
import com.bluemessenger.domain.model.Conversation
import com.bluemessenger.domain.model.LocalIdentity
import com.bluemessenger.domain.model.Message
import com.bluemessenger.domain.model.Peer
import com.bluemessenger.domain.ports.crypto.KeyStoreProvider
import com.bluemessenger.domain.ports.logging.AppLogger
import com.bluemessenger.domain.ports.storage.IdentityRepository
import com.bluemessenger.domain.ports.storage.MessageRepository
import com.bluemessenger.domain.ports.storage.PeerRepository
import com.bluemessenger.domain.ports.transport.BluetoothScanner
import com.bluemessenger.domain.ports.transport.BluetoothTransport
import com.bluemessenger.domain.ports.transport.model.Packet
import com.bluemessenger.domain.primitives.BluetoothAddress
import com.bluemessenger.domain.primitives.ConversationId
import com.bluemessenger.domain.primitives.Instant
import com.bluemessenger.domain.primitives.MessageId
import com.bluemessenger.domain.primitives.PeerId
import com.bluemessenger.domain.primitives.PublicKey
import com.bluemessenger.domain.usecase.message.util.EncryptedPacketSender
import com.bluemessenger.domain.usecase.session.EstablishSecureSessionUseCase
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlin.test.assertFalse

internal fun testDispatchers(scheduler: TestCoroutineScheduler): CoroutineDispatchers {
    val dispatcher = StandardTestDispatcher(scheduler)
    return CoroutineDispatchers(main = dispatcher, io = dispatcher, default = dispatcher)
}

internal class FakeAppLogger : AppLogger {
    enum class Level { DEBUG, INFO, WARN, ERROR }

    data class LogEntry(
        val level: Level,
        val tag: String,
        val message: String,
        val throwable: Throwable?,
        val context: Map<String, String>,
    )

    val entries: MutableList<LogEntry> = mutableListOf()

    override fun debug(
        tag: String,
        message: String,
        context: Map<String, String>,
    ) {
        entries.add(LogEntry(Level.DEBUG, tag, message, null, context))
    }

    override fun info(
        tag: String,
        message: String,
        context: Map<String, String>,
    ) {
        entries.add(LogEntry(Level.INFO, tag, message, null, context))
    }

    override fun warn(
        tag: String,
        message: String,
        throwable: Throwable?,
        context: Map<String, String>,
    ) {
        entries.add(LogEntry(Level.WARN, tag, message, throwable, context))
    }

    override fun error(
        tag: String,
        message: String,
        throwable: Throwable?,
        context: Map<String, String>,
    ) {
        entries.add(LogEntry(Level.ERROR, tag, message, throwable, context))
    }

    fun assertNoSensitiveData(vararg forbiddenSubstrings: String) {
        for (entry in entries) {
            for (secret in forbiddenSubstrings) {
                assertFalse(
                    entry.message.contains(secret),
                    "Log message leaked sensitive text: ${entry.message}",
                )
                for ((key, value) in entry.context) {
                    assertFalse(
                        key.contains(secret) || value.contains(secret),
                        "Log context leaked sensitive text under key '$key'.",
                    )
                }
            }
        }
    }
}

internal class FakeKeyStoreProvider(
    initialKeyPair: IdentityKeyPair? = null,
) : KeyStoreProvider {
    private var keyPair: IdentityKeyPair? = initialKeyPair
    var generateFailure: DomainError? = null
    var signFailure: DomainError? = null
    var hasKeyPairChecks: Int = 0

    fun setKeyPair(newKeyPair: IdentityKeyPair) {
        keyPair = newKeyPair
    }

    override suspend fun hasIdentityKeyPair(): Result<Boolean> {
        hasKeyPairChecks += 1
        return Result.success(keyPair != null)
    }

    override suspend fun generateIdentityKeyPair(): Result<PublicKey> {
        generateFailure?.let { return it.asFailure() }
        val generated = IdentityKeyPair.generate()
        keyPair = generated
        return Result.success(generated.publicKey)
    }

    override suspend fun getIdentityPublicKey(): Result<PublicKey?> = Result.success(keyPair?.publicKey)

    override suspend fun signWithIdentityKey(data: ByteArray): Result<ByteArray> =
        signFailure?.asFailure()
            ?: keyPair?.sign(data)
            ?: StorageError("Identity key pair is not present in FakeKeyStoreProvider.").asFailure()

    override suspend fun deleteIdentityKeyPair(): Result<Unit> {
        keyPair?.zeroize()
        keyPair = null
        return Result.success(Unit)
    }
}

internal class InMemoryIdentityRepository(
    initialIdentity: LocalIdentity? = null,
) : IdentityRepository {
    private val state = MutableStateFlow(initialIdentity)
    var saveFailure: DomainError? = null
    var getFailure: DomainError? = null

    override fun observeLocalIdentity(): Flow<LocalIdentity?> = state.asStateFlow()

    override suspend fun getLocalIdentity(): Result<LocalIdentity?> {
        return getFailure?.asFailure() ?: Result.success(state.value)
    }

    override suspend fun saveLocalIdentity(identity: LocalIdentity): Result<Unit> {
        saveFailure?.let { return it.asFailure() }
        state.value = identity
        return Result.success(Unit)
    }
}

internal class InMemoryPeerRepository : PeerRepository {
    private val peers = MutableStateFlow<Map<PeerId, Peer>>(emptyMap())
    private val conversations = MutableStateFlow<Map<PeerId, Conversation>>(emptyMap())
    val conversationStateHistory: MutableList<SessionState> = mutableListOf()
    var upsertFailure: DomainError? = null
    var observeFailure: DomainError? = null

    override fun observePeers(): Flow<List<Peer>> = peers.map { it.values.toList() }

    override fun observeConversations(): Flow<List<Conversation>> =
        flow {
            observeFailure?.let { throw DomainException(it) }
            emitAll(conversations.map { it.values.toList() })
        }

    override suspend fun getPeerById(peerId: PeerId): Result<Peer?> = Result.success(peers.value[peerId])

    override suspend fun getPeerByAddress(address: BluetoothAddress): Result<Peer?> =
        Result.success(peers.value.values.firstOrNull { it.address == address })

    override suspend fun upsertPeer(peer: Peer): Result<Unit> {
        upsertFailure?.let { return it.asFailure() }
        peers.value = peers.value + (peer.id to peer)
        return Result.success(Unit)
    }

    override suspend fun upsertConversation(conversation: Conversation): Result<Unit> {
        upsertFailure?.let { return it.asFailure() }
        peers.value = peers.value + (conversation.peer.id to conversation.peer)
        conversations.value = conversations.value + (conversation.peer.id to conversation)
        conversationStateHistory.add(conversation.state)
        return Result.success(Unit)
    }

    override suspend fun getConversationByPeer(peerId: PeerId): Result<Conversation?> {
        return Result.success(conversations.value[peerId])
    }

    override suspend fun markPeerVerified(
        peerId: PeerId,
        verified: Boolean,
    ): Result<Unit> {
        val current =
            peers.value[peerId]
                ?: return StorageError("Peer $peerId was not found.").asFailure()
        val updated = current.copy(isVerified = verified)
        peers.value = peers.value + (peerId to updated)
        val existingConv = conversations.value[peerId]
        if (existingConv != null) {
            conversations.value = conversations.value + (peerId to existingConv.copy(peer = updated))
        }
        return Result.success(Unit)
    }
}

internal class InMemoryMessageRepository : MessageRepository {
    private val messages = MutableStateFlow<LinkedHashMap<MessageId, Message>>(linkedMapOf())
    var saveFailure: DomainError? = null
    var updateFailure: DomainError? = null
    var loadFailure: DomainError? = null
    var observeFailure: DomainError? = null

    override fun observeByConversation(conversationId: ConversationId): Flow<List<Message>> =
        flow {
            observeFailure?.let { throw DomainException(it) }
            emitAll(
                messages.map { map ->
                    map.values.filter { it.conversationId == conversationId }.sortedBy { it.sentAt }
                },
            )
        }

    override suspend fun getById(messageId: MessageId): Result<Message?> =
        loadFailure?.asFailure() ?: Result.success(messages.value[messageId])

    override suspend fun getPendingOrFailedOutgoing(conversationId: ConversationId): Result<List<Message>> {
        loadFailure?.let { return it.asFailure() }
        val matching =
            messages.value.values.filter {
                it.conversationId == conversationId &&
                    (it.status == MessageStatus.PENDING || it.status == MessageStatus.FAILED)
            }.sortedBy { it.sentAt }
        return Result.success(matching)
    }

    override suspend fun saveIdempotent(message: Message): Result<Boolean> {
        val failure = saveFailure
        return when {
            failure != null -> failure.asFailure()
            messages.value.containsKey(message.id) -> Result.success(false)
            else -> {
                val updated = LinkedHashMap(messages.value)
                updated[message.id] = message
                messages.value = updated
                Result.success(true)
            }
        }
    }

    override suspend fun updateStatus(
        messageId: MessageId,
        newStatus: MessageStatus,
        deliveredAt: Instant?,
    ): Result<Unit> {
        val failure = updateFailure
        val existing = messages.value[messageId]
        return when {
            failure != null -> failure.asFailure()
            existing == null -> StorageError("Message $messageId was not found.").asFailure()
            else ->
                existing.transitionStatus(newStatus, deliveredAt).map { transitioned ->
                    val updated = LinkedHashMap(messages.value)
                    updated[messageId] = transitioned
                    messages.value = updated
                }
        }
    }

    fun allMessages(): List<Message> = messages.value.values.toList()
}

internal class FakeBluetoothScanner : BluetoothScanner {
    private val _discoveredPeers = MutableStateFlow<List<Peer>>(emptyList())
    private val _isScanning = MutableStateFlow(false)
    private val _isAdvertising = MutableStateFlow(false)

    override val discoveredPeers: StateFlow<List<Peer>> = _discoveredPeers.asStateFlow()
    override val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()
    override val isAdvertising: StateFlow<Boolean> = _isAdvertising.asStateFlow()

    var startDiscoveryError: DomainError? = null
    var startAdvertisingError: DomainError? = null

    fun emitPeer(peer: Peer) {
        _discoveredPeers.value = _discoveredPeers.value + peer
    }

    override suspend fun startDiscovery(): Result<Unit> {
        startDiscoveryError?.let { return it.asFailure() }
        _isScanning.value = true
        return Result.success(Unit)
    }

    override suspend fun stopDiscovery(): Result<Unit> {
        _isScanning.value = false
        return Result.success(Unit)
    }

    override suspend fun startAdvertising(localNickname: String): Result<Unit> {
        startAdvertisingError?.let { return it.asFailure() }
        _isAdvertising.value = true
        return Result.success(Unit)
    }

    override suspend fun stopAdvertising(): Result<Unit> {
        _isAdvertising.value = false
        return Result.success(Unit)
    }
}

internal class FakeBluetoothTransport(
    initialState: SessionState = SessionState.DISCONNECTED,
) : BluetoothTransport {
    private val _sessionState = MutableStateFlow(initialState)
    private val _incomingPackets = MutableSharedFlow<Packet>(extraBufferCapacity = 64)

    override val sessionState: StateFlow<SessionState> = _sessionState.asStateFlow()
    override val incomingPackets: Flow<Packet> = _incomingPackets.asSharedFlow()

    val sentPackets: MutableList<Packet> = mutableListOf()
    var connectAttempts: Int = 0
    var disconnectCount: Int = 0

    var nextConnectOutcome: Result<Unit> = Result.success(Unit)
    var onConnect: (suspend (BluetoothAddress) -> Result<Unit>)? = null
    var onSend: (suspend (Packet) -> Result<Unit>)? = null
    var failSendAfterCount: Int? = null
    var sendFailureError: DomainError = TransportDisconnectedError()

    fun setSessionState(state: SessionState) {
        _sessionState.value = state
    }

    suspend fun emitIncomingPacket(packet: Packet) {
        _incomingPackets.emit(packet)
    }

    override suspend fun startServer(): Result<Unit> {
        _sessionState.value = SessionState.CONNECTING
        return Result.success(Unit)
    }

    override suspend fun connect(address: BluetoothAddress): Result<Unit> {
        connectAttempts += 1
        val custom = onConnect
        val result =
            if (custom != null) {
                custom.invoke(address)
            } else {
                nextConnectOutcome
            }
        if (result.isSuccess) {
            _sessionState.value = SessionState.HANDSHAKING
        } else {
            _sessionState.value = SessionState.DISCONNECTED
        }
        return result
    }

    override suspend fun send(packet: Packet): Result<Unit> {
        val limit = failSendAfterCount
        if (limit != null && sentPackets.size >= limit) {
            return Result.failure(DomainException(sendFailureError))
        }
        sentPackets.add(packet)
        val hook = onSend
        return if (hook != null) {
            hook.invoke(packet)
        } else {
            Result.success(Unit)
        }
    }

    override suspend fun disconnect(): Result<Unit> {
        disconnectCount += 1
        _sessionState.value = SessionState.DISCONNECTED
        return Result.success(Unit)
    }
}

internal data class PeerSideFixture(
    val keyStore: FakeKeyStoreProvider,
    val crypto: CryptoServiceImpl,
    val transport: FakeBluetoothTransport,
    val peerRepo: InMemoryPeerRepository,
    val messageRepo: InMemoryMessageRepository,
    val logger: FakeAppLogger,
    val packetSender: EncryptedPacketSender,
    val establish: EstablishSecureSessionUseCase,
)

internal data class PairedSessionHarness(
    val alice: PeerSideFixture,
    val bob: PeerSideFixture,
    val alicePeer: Peer,
    val bobPeer: Peer,
    val aliceConversation: Conversation,
    val bobConversation: Conversation,
) {
    fun wireDuplexTransports() {
        alice.transport.onSend = { packet ->
            bob.transport.emitIncomingPacket(packet)
            Result.success(Unit)
        }
        bob.transport.onSend = { packet ->
            alice.transport.emitIncomingPacket(packet)
            Result.success(Unit)
        }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
internal fun TestScope.createPeerSideFixture(clock: () -> Instant): PeerSideFixture {
    val keyStore = FakeKeyStoreProvider(IdentityKeyPair.generate())
    val crypto = CryptoServiceImpl(keyStore)
    val transport = FakeBluetoothTransport()
    val peerRepo = InMemoryPeerRepository()
    val logger = FakeAppLogger()
    val packetSender = EncryptedPacketSender(crypto, transport)
    val establish =
        EstablishSecureSessionUseCase(
            bluetoothTransport = transport,
            cryptoService = crypto,
            peerRepository = peerRepo,
            dispatchers = testDispatchers(testScheduler),
            logger = logger,
            clock = clock,
        )
    return PeerSideFixture(
        keyStore,
        crypto,
        transport,
        peerRepo,
        InMemoryMessageRepository(),
        logger,
        packetSender,
        establish,
    )
}

@OptIn(ExperimentalCoroutinesApi::class)
internal suspend fun TestScope.createPairedActiveSession(baseTimeMs: Long = 1_700_000_000_000L): PairedSessionHarness {
    val clock: () -> Instant = { Instant(testScheduler.currentTime + baseTimeMs) }
    val alice = createPeerSideFixture(clock)
    val bob = createPeerSideFixture(clock)
    alice.transport.onSend = { packet ->
        bob.transport.emitIncomingPacket(packet)
        Result.success(Unit)
    }
    bob.transport.onSend = { packet ->
        alice.transport.emitIncomingPacket(packet)
        Result.success(Unit)
    }
    bob.transport.setSessionState(SessionState.HANDSHAKING)

    val now = clock()
    val bobPeer = Peer(PeerId.generate(now), "Bob", BluetoothAddress("11:22:33:44:55:66"), lastSeenAt = now)
    val alicePeer =
        Peer(
            PeerId.generate(Instant(now.epochMillis + 1L)),
            "Alice",
            BluetoothAddress("AA:BB:CC:DD:EE:FF"),
            lastSeenAt = now,
        )

    val (aliceConv, bobConv) =
        coroutineScope {
            val responderDeferred =
                async(start = CoroutineStart.UNDISPATCHED) {
                    bob.establish.acceptAndEstablish(alicePeer)
                }
            val initiated = alice.establish.connectAndEstablish(bobPeer).getOrThrow()
            val accepted = responderDeferred.await().getOrThrow()
            initiated to accepted
        }

    return PairedSessionHarness(alice, bob, alicePeer, bobPeer, aliceConv, bobConv)
}

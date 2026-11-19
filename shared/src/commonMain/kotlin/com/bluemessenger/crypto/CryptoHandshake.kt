package com.bluemessenger.crypto

import com.bluemessenger.crypto.constants.CryptoConstants
import com.bluemessenger.crypto.model.EphemeralKeyPair
import com.bluemessenger.crypto.model.IdentityKeyPair
import com.bluemessenger.crypto.util.ByteArrayZeroizer
import com.bluemessenger.domain.error.CorruptedFrameError
import com.bluemessenger.domain.error.DecryptionFailedError
import com.bluemessenger.domain.error.InvalidStateTransitionError
import com.bluemessenger.domain.error.asFailure
import com.bluemessenger.domain.primitives.PublicKey

/**
 * Completed Noise XX handshake output used to bootstrap a Double Ratchet [CryptoSession].
 */
class HandshakeCompletion internal constructor(
    val rootKey: ByteArray,
    val remoteIdentityKey: PublicKey,
    val localEphemeralKeyPair: EphemeralKeyPair?,
    val remoteEphemeralPublicKey: PublicKey?,
    val isInitiator: Boolean,
) {
    /**
     * Erases the derived root key and any owned local ephemeral key pair.
     */
    fun zeroize() {
        ByteArrayZeroizer.zeroize(rootKey)
        localEphemeralKeyPair?.zeroize()
    }
}

/**
 * Output of a single [CryptoHandshake] step.
 */
class CryptoHandshakeStep internal constructor(
    val outboundMessage: ByteArray?,
    val completion: HandshakeCompletion?,
)

/**
 * Three-way authenticated P2P key-exchange handshake (`Noise_XX`-inspired) combining X25519
 * ephemeral ECDH, Ed25519 identity signatures, HKDF-SHA256, and ChaCha20-Poly1305 AEAD.
 *
 * Thread safety: Not internally synchronized; callers must serialize invocations per instance.
 *
 * Error handling: Any AEAD verification failure, invalid Ed25519 signature, malformed payload,
 * low-order X25519 key, or out-of-order transition aborts the handshake, zeroizes intermediate
 * secrets, and fails fast with a `DomainException` (`DecryptionFailedError`, `CorruptedFrameError`,
 * or `InvalidStateTransitionError`).
 */
class CryptoHandshake(
    private val isInitiator: Boolean,
    private val localIdentityPublicKey: PublicKey,
    private val signWithIdentity: suspend (ByteArray) -> Result<ByteArray>,
) {
    private enum class HandshakeState {
        AWAITING_START,
        AWAITING_MSG_1,
        AWAITING_MSG_2,
        AWAITING_MSG_3,
        COMPLETED,
        FAILED,
    }

    private var state: HandshakeState =
        if (isInitiator) {
            HandshakeState.AWAITING_START
        } else {
            HandshakeState.AWAITING_MSG_1
        }
    private var transcriptHash: ByteArray = CryptoEngine.sha256(protocolNameBytes)
    private var chainingKey: ByteArray = transcriptHash.copyOf()
    private var localEphemeral: EphemeralKeyPair? = null
    private var remoteEphemeralBytes: ByteArray? = null

    /**
     * Creates a [CryptoHandshake] using an in-memory [IdentityKeyPair].
     */
    constructor(
        isInitiator: Boolean,
        identityKeyPair: IdentityKeyPair,
    ) : this(
        isInitiator = isInitiator,
        localIdentityPublicKey = identityKeyPair.publicKey,
        signWithIdentity = { data -> identityKeyPair.sign(data) },
    )

    /**
     * Initiates the handshake and produces message 1 (the initiator's 32-byte X25519 ephemeral key).
     */
    fun start(): Result<ByteArray> =
        if (!isInitiator || state != HandshakeState.AWAITING_START) {
            InvalidStateTransitionError(
                entity = ENTITY_NAME,
                fromState = state.name,
                toState = ACTION_START,
            ).asFailure()
        } else {
            val ephemeral = EphemeralKeyPair.generate()
            val ephemeralBytes = ephemeral.publicKey.toByteArray()
            localEphemeral = ephemeral
            transcriptHash = mixHash(transcriptHash, ephemeralBytes)
            state = HandshakeState.AWAITING_MSG_2
            Result.success(ephemeralBytes)
        }

    /**
     * Advances the handshake state machine using [incomingPayload].
     */
    suspend fun processMessage(incomingPayload: ByteArray): Result<CryptoHandshakeStep> =
        when (state) {
            HandshakeState.AWAITING_MSG_1 -> processMsg1AsResponder(incomingPayload)
            HandshakeState.AWAITING_MSG_2 -> processMsg2AsInitiator(incomingPayload)
            HandshakeState.AWAITING_MSG_3 -> processMsg3AsResponder(incomingPayload)
            HandshakeState.AWAITING_START,
            HandshakeState.COMPLETED,
            HandshakeState.FAILED,
            ->
                InvalidStateTransitionError(
                    entity = ENTITY_NAME,
                    fromState = state.name,
                    toState = ACTION_PROCESS,
                ).asFailure()
        }

    /**
     * Zeroizes all intermediate handshake secrets held in memory.
     */
    fun zeroize() {
        ByteArrayZeroizer.zeroize(transcriptHash, chainingKey, remoteEphemeralBytes)
        localEphemeral?.zeroize()
        localEphemeral = null
        remoteEphemeralBytes = null
        if (state != HandshakeState.COMPLETED) {
            state = HandshakeState.FAILED
        }
    }

    private suspend fun processMsg1AsResponder(incomingPayload: ByteArray): Result<CryptoHandshakeStep> {
        val outcome =
            if (incomingPayload.size != CryptoConstants.X25519_KEY_LENGTH_BYTES) {
                CorruptedFrameError(
                    "Handshake message 1 must be ${CryptoConstants.X25519_KEY_LENGTH_BYTES} bytes.",
                ).asFailure()
            } else {
                PublicKey.fromBytes(incomingPayload).fold(
                    onSuccess = { initiatorEphemeralKey ->
                        buildMsg2Step(initiatorEphemeralKey)
                    },
                    onFailure = { error ->
                        CorruptedFrameError("Invalid initiator ephemeral key: ${error.message}").asFailure()
                    },
                )
            }
        return outcome.onFailure { zeroize() }
    }

    private suspend fun buildMsg2Step(initiatorEphemeralKey: PublicKey): Result<CryptoHandshakeStep> {
        val initiatorEphemeralRaw = initiatorEphemeralKey.toByteArray()
        remoteEphemeralBytes = initiatorEphemeralRaw
        transcriptHash = mixHash(transcriptHash, initiatorEphemeralRaw)

        val responderEphemeral = EphemeralKeyPair.generate()
        localEphemeral = responderEphemeral
        val responderEphemeralRaw = responderEphemeral.publicKey.toByteArray()
        transcriptHash = mixHash(transcriptHash, responderEphemeralRaw)

        return responderEphemeral.computeSharedSecret(initiatorEphemeralKey).fold(
            onSuccess = { dhSecret ->
                val (nextCk, encKey) = splitKeyMaterial(chainingKey, dhSecret, msg2KdfInfo)
                ByteArrayZeroizer.zeroize(dhSecret, chainingKey)
                chainingKey = nextCk
                encryptIdentityProof(encKey, responderEphemeralRaw).map { ciphertext ->
                    transcriptHash = mixHash(transcriptHash, ciphertext)
                    state = HandshakeState.AWAITING_MSG_3
                    CryptoHandshakeStep(
                        outboundMessage = responderEphemeralRaw + ciphertext,
                        completion = null,
                    )
                }
            },
            onFailure = { Result.failure(it) },
        )
    }

    private suspend fun processMsg2AsInitiator(incomingPayload: ByteArray): Result<CryptoHandshakeStep> {
        val outcome =
            if (incomingPayload.size != CryptoConstants.HANDSHAKE_MSG2_SIZE_BYTES) {
                CorruptedFrameError(
                    "Handshake message 2 must be ${CryptoConstants.HANDSHAKE_MSG2_SIZE_BYTES} bytes.",
                ).asFailure()
            } else {
                val responderEphemeralRaw = incomingPayload.copyOfRange(0, CryptoConstants.X25519_KEY_LENGTH_BYTES)
                val ciphertext =
                    incomingPayload.copyOfRange(CryptoConstants.X25519_KEY_LENGTH_BYTES, incomingPayload.size)
                PublicKey.fromBytes(responderEphemeralRaw).fold(
                    onSuccess = { responderEphemeralKey ->
                        completeInitiatorHandshake(responderEphemeralKey, responderEphemeralRaw, ciphertext)
                    },
                    onFailure = { error ->
                        CorruptedFrameError("Invalid responder ephemeral key: ${error.message}").asFailure()
                    },
                )
            }
        return outcome.onFailure { zeroize() }
    }

    private suspend fun completeInitiatorHandshake(
        responderEphemeralKey: PublicKey,
        responderEphemeralRaw: ByteArray,
        ciphertext: ByteArray,
    ): Result<CryptoHandshakeStep> {
        transcriptHash = mixHash(transcriptHash, responderEphemeralRaw)
        val initiatorEphemeral = localEphemeral
        return if (initiatorEphemeral == null) {
            DecryptionFailedError("Missing initiator ephemeral key.").asFailure()
        } else {
            val initiatorEphemeralRaw = initiatorEphemeral.publicKey.toByteArray()
            val dhResult = initiatorEphemeral.computeSharedSecret(responderEphemeralKey)
            initiatorEphemeral.zeroize()
            localEphemeral = null
            dhResult.fold(
                onSuccess = { dhSecret ->
                    val (nextCk, decKey) = splitKeyMaterial(chainingKey, dhSecret, msg2KdfInfo)
                    ByteArrayZeroizer.zeroize(dhSecret, chainingKey)
                    chainingKey = nextCk
                    decryptAndVerifyIdentityProof(
                        decryptionKey = decKey,
                        ciphertext = ciphertext,
                        ephemeralPublicBytes = responderEphemeralRaw,
                        transcriptHash = transcriptHash,
                    ).fold(
                        onSuccess = { remoteIdentityKey ->
                            buildMsg3Step(remoteIdentityKey, responderEphemeralKey, initiatorEphemeralRaw, ciphertext)
                        },
                        onFailure = { Result.failure(it) },
                    )
                },
                onFailure = { Result.failure(it) },
            )
        }
    }

    private suspend fun buildMsg3Step(
        remoteIdentityKey: PublicKey,
        responderEphemeralKey: PublicKey,
        initiatorEphemeralRaw: ByteArray,
        msg2Ciphertext: ByteArray,
    ): Result<CryptoHandshakeStep> {
        transcriptHash = mixHash(transcriptHash, msg2Ciphertext)
        val (finalCk, encKey) = splitKeyMaterial(chainingKey, transcriptHash, msg3KdfInfo)
        ByteArrayZeroizer.zeroize(chainingKey)
        chainingKey = finalCk
        return encryptIdentityProof(encKey, initiatorEphemeralRaw).map { msg3Ciphertext ->
            transcriptHash = mixHash(transcriptHash, msg3Ciphertext)
            val rootKey = deriveRootKey(chainingKey, transcriptHash)
            state = HandshakeState.COMPLETED
            zeroize()
            CryptoHandshakeStep(
                outboundMessage = msg3Ciphertext,
                completion =
                    HandshakeCompletion(
                        rootKey = rootKey,
                        remoteIdentityKey = remoteIdentityKey,
                        localEphemeralKeyPair = null,
                        remoteEphemeralPublicKey = responderEphemeralKey,
                        isInitiator = true,
                    ),
            )
        }
    }

    private fun processMsg3AsResponder(incomingPayload: ByteArray): Result<CryptoHandshakeStep> {
        val initiatorEphemeralRaw = remoteEphemeralBytes
        val outcome =
            when {
                incomingPayload.size != CryptoConstants.HANDSHAKE_ENCRYPTED_PROOF_SIZE_BYTES ->
                    CorruptedFrameError(
                        "Handshake message 3 must be ${CryptoConstants.HANDSHAKE_ENCRYPTED_PROOF_SIZE_BYTES} bytes.",
                    ).asFailure()
                initiatorEphemeralRaw == null ->
                    DecryptionFailedError("Missing initiator ephemeral key.").asFailure()
                else -> {
                    val (finalCk, decKey) = splitKeyMaterial(chainingKey, transcriptHash, msg3KdfInfo)
                    ByteArrayZeroizer.zeroize(chainingKey)
                    chainingKey = finalCk
                    decryptAndVerifyIdentityProof(
                        decryptionKey = decKey,
                        ciphertext = incomingPayload,
                        ephemeralPublicBytes = initiatorEphemeralRaw,
                        transcriptHash = transcriptHash,
                    ).map { remoteId ->
                        transcriptHash = mixHash(transcriptHash, incomingPayload)
                        val rootKey = deriveRootKey(chainingKey, transcriptHash)
                        val responderDhPair = localEphemeral
                        localEphemeral = null
                        state = HandshakeState.COMPLETED
                        zeroize()
                        CryptoHandshakeStep(
                            outboundMessage = null,
                            completion =
                                HandshakeCompletion(
                                    rootKey = rootKey,
                                    remoteIdentityKey = remoteId,
                                    localEphemeralKeyPair = responderDhPair,
                                    remoteEphemeralPublicKey = null,
                                    isInitiator = false,
                                ),
                        )
                    }
                }
            }
        return outcome.onFailure { zeroize() }
    }

    private suspend fun encryptIdentityProof(
        encryptionKey: ByteArray,
        ephemeralPublicBytes: ByteArray,
    ): Result<ByteArray> =
        ByteArrayZeroizer.useAndZeroize(encryptionKey) { key ->
            val localIdentityBytes = localIdentityPublicKey.toByteArray()
            val signatureInput = signatureDomainBytes + transcriptHash + ephemeralPublicBytes + localIdentityBytes
            signWithIdentity(signatureInput).fold(
                onSuccess = { signature ->
                    if (signature.size != CryptoConstants.ED25519_SIGNATURE_LENGTH_BYTES) {
                        ByteArrayZeroizer.zeroize(signature)
                        DecryptionFailedError(
                            "Identity signature must be ${CryptoConstants.ED25519_SIGNATURE_LENGTH_BYTES} bytes, " +
                                "got ${signature.size}.",
                        ).asFailure()
                    } else {
                        val proofPlaintext = localIdentityBytes + signature
                        ByteArrayZeroizer.zeroize(signature)
                        ByteArrayZeroizer.useAndZeroize(proofPlaintext) { plaintext ->
                            CryptoCipher.encrypt(
                                key = key,
                                nonce = zeroNonce,
                                plaintext = plaintext,
                                associatedData = transcriptHash,
                            )
                        }
                    }
                },
                onFailure = { Result.failure(it) },
            )
        }

    private companion object {
        private const val ENTITY_NAME: String = "CryptoHandshake"
        private const val ACTION_START: String = "START"
        private const val ACTION_PROCESS: String = "PROCESS"
        private const val DOUBLE_KEY_BYTES: Int =
            CryptoConstants.SYMMETRIC_KEY_LENGTH_BYTES + CryptoConstants.SYMMETRIC_KEY_LENGTH_BYTES

        private val protocolNameBytes: ByteArray =
            "BlueMessenger_NoiseXX_X25519_Ed25519_ChaChaPoly_SHA256".encodeToByteArray()
        private val msg2KdfInfo: ByteArray = "BlueMessenger_NoiseXX_Msg2_v1".encodeToByteArray()
        private val msg3KdfInfo: ByteArray = "BlueMessenger_NoiseXX_Msg3_v1".encodeToByteArray()
        private val rootKeyInfo: ByteArray = "BlueMessenger_NoiseXX_RootKey_v1".encodeToByteArray()
        private val signatureDomainBytes: ByteArray = "BlueMessenger_NoiseXX_IdentitySig_v1".encodeToByteArray()
        private val zeroNonce: ByteArray = ByteArray(CryptoConstants.NONCE_LENGTH_BYTES)

        private fun mixHash(
            currentHash: ByteArray,
            data: ByteArray,
        ): ByteArray {
            val nextHash = CryptoEngine.sha256(currentHash + data)
            ByteArrayZeroizer.zeroize(currentHash)
            return nextHash
        }

        private fun splitKeyMaterial(
            chainingKey: ByteArray,
            input: ByteArray,
            info: ByteArray,
        ): Pair<ByteArray, ByteArray> {
            val derived =
                CryptoEngine.hkdfSha256(
                    ikm = input,
                    salt = chainingKey,
                    info = info,
                    outputLength = DOUBLE_KEY_BYTES,
                )
            return ByteArrayZeroizer.useAndZeroize(derived) { buffer ->
                val nextCk = buffer.copyOfRange(0, CryptoConstants.SYMMETRIC_KEY_LENGTH_BYTES)
                val encKey = buffer.copyOfRange(CryptoConstants.SYMMETRIC_KEY_LENGTH_BYTES, DOUBLE_KEY_BYTES)
                nextCk to encKey
            }
        }

        private fun deriveRootKey(
            chainingKey: ByteArray,
            transcriptHash: ByteArray,
        ): ByteArray =
            CryptoEngine.hkdfSha256(
                ikm = chainingKey,
                salt = transcriptHash,
                info = rootKeyInfo,
                outputLength = CryptoConstants.SYMMETRIC_KEY_LENGTH_BYTES,
            )

        private fun decryptAndVerifyIdentityProof(
            decryptionKey: ByteArray,
            ciphertext: ByteArray,
            ephemeralPublicBytes: ByteArray,
            transcriptHash: ByteArray,
        ): Result<PublicKey> =
            ByteArrayZeroizer.useAndZeroize(decryptionKey) { key ->
                CryptoCipher.decrypt(
                    key = key,
                    nonce = zeroNonce,
                    ciphertextWithTag = ciphertext,
                    associatedData = transcriptHash,
                ).fold(
                    onSuccess = { plaintext ->
                        ByteArrayZeroizer.useAndZeroize(plaintext) { proof ->
                            verifyProofBytes(proof, ephemeralPublicBytes, transcriptHash)
                        }
                    },
                    onFailure = { Result.failure(it) },
                )
            }

        private fun verifyProofBytes(
            proof: ByteArray,
            ephemeralPublicBytes: ByteArray,
            transcriptHash: ByteArray,
        ): Result<PublicKey> =
            if (proof.size != CryptoConstants.HANDSHAKE_IDENTITY_PROOF_SIZE_BYTES) {
                DecryptionFailedError("Invalid handshake identity proof size: ${proof.size}.").asFailure()
            } else {
                val identityBytes = proof.copyOfRange(0, CryptoConstants.ED25519_PUBLIC_KEY_LENGTH_BYTES)
                val signature = proof.copyOfRange(CryptoConstants.ED25519_PUBLIC_KEY_LENGTH_BYTES, proof.size)
                val signatureInput = signatureDomainBytes + transcriptHash + ephemeralPublicBytes + identityBytes
                if (!CryptoEngine.ed25519Verify(identityBytes, signatureInput, signature)) {
                    DecryptionFailedError("Handshake Ed25519 identity signature verification failed.").asFailure()
                } else {
                    PublicKey.fromBytes(identityBytes)
                }
            }
    }
}

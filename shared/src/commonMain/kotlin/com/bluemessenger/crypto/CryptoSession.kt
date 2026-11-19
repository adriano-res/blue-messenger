package com.bluemessenger.crypto

import com.bluemessenger.crypto.constants.CryptoConstants
import com.bluemessenger.crypto.model.EphemeralKeyPair
import com.bluemessenger.crypto.model.RatchetHeader
import com.bluemessenger.crypto.util.ByteArrayZeroizer
import com.bluemessenger.crypto.util.ChainKdfOutput
import com.bluemessenger.crypto.util.KdfChain
import com.bluemessenger.crypto.util.RootKdfOutput
import com.bluemessenger.domain.error.CorruptedFrameError
import com.bluemessenger.domain.error.DecryptionFailedError
import com.bluemessenger.domain.error.InvalidStateTransitionError
import com.bluemessenger.domain.error.asFailure
import com.bluemessenger.domain.primitives.PublicKey
import kotlin.concurrent.Volatile

/**
 * Signal Double Ratchet session combining Diffie-Hellman key rotation with symmetric KDF chains.
 *
 * Thread safety: Not internally synchronized; callers must serialize operations per session
 * (as enforced by [CryptoServiceImpl] via per-peer `Mutex`).
 *
 * Error handling: Fails fast with [DecryptionFailedError], [CorruptedFrameError], or
 * [InvalidStateTransitionError]. On any decryption or authentication failure, tentative keys are
 * zeroized and live session state remains completely unmodified.
 */
class CryptoSession private constructor(
    private var rootKey: ByteArray,
    private var localDhKeyPair: EphemeralKeyPair,
    private var remoteDhPublicKey: PublicKey?,
    private var sendingChainKey: ByteArray,
    private var receivingChainKey: ByteArray?,
    private val maxSkippedKeys: Int,
) {
    @Volatile
    private var isDestroyed: Boolean = false
    private var sendingMessageNumber: Int = 0
    private var receivingMessageNumber: Int = 0
    private var previousSendingChainLength: Int = 0
    private val skippedMessageKeys: MutableMap<SkippedKeyId, SkippedMessageKey> = LinkedHashMap()

    /**
     * Encrypts [plaintext] with a fresh ephemeral message key and prepends the 40-byte [RatchetHeader].
     */
    fun encrypt(
        plaintext: ByteArray,
        associatedData: ByteArray = ByteArray(0),
    ): Result<ByteArray> =
        if (isDestroyed) {
            destroyedFailure(ACTION_ENCRYPT)
        } else {
            KdfChain.kdfChainKey(sendingChainKey).fold(
                onSuccess = { step -> encryptWithStep(step, plaintext, associatedData) },
                onFailure = { Result.failure(it) },
            )
        }

    /**
     * Authenticates and decrypts [packet], verifying header integrity and rejecting replays.
     */
    fun decrypt(
        packet: ByteArray,
        associatedData: ByteArray = ByteArray(0),
    ): Result<ByteArray> =
        when {
            isDestroyed -> destroyedFailure(ACTION_DECRYPT)
            packet.size < CryptoConstants.RATCHET_OVERHEAD_BYTES ->
                CorruptedFrameError(
                    "Encrypted packet requires at least ${CryptoConstants.RATCHET_OVERHEAD_BYTES} bytes, " +
                        "got ${packet.size}.",
                ).asFailure()
            else -> {
                val headerBytes = packet.copyOfRange(0, CryptoConstants.RATCHET_HEADER_SIZE_BYTES)
                val ciphertextWithTag = packet.copyOfRange(CryptoConstants.RATCHET_HEADER_SIZE_BYTES, packet.size)
                val fullAd = associatedData + headerBytes
                RatchetHeader.decode(headerBytes).fold(
                    onSuccess = { header -> dispatchDecrypt(header, ciphertextWithTag, fullAd) },
                    onFailure = { Result.failure(it) },
                )
            }
        }

    /**
     * Permanently erases all root, chain, ephemeral DH, and skipped message keys from memory.
     */
    fun zeroize() {
        isDestroyed = true
        ByteArrayZeroizer.zeroize(rootKey, sendingChainKey, receivingChainKey)
        receivingChainKey = null
        localDhKeyPair.zeroize()
        skippedMessageKeys.values.forEach(SkippedMessageKey::zeroize)
        skippedMessageKeys.clear()
    }

    private fun encryptWithStep(
        step: ChainKdfOutput,
        plaintext: ByteArray,
        associatedData: ByteArray,
    ): Result<ByteArray> {
        val headerBytes =
            RatchetHeader(
                dhPublicKey = localDhKeyPair.publicKey,
                previousChainLength = previousSendingChainLength,
                messageNumber = sendingMessageNumber,
            ).encode()
        val ciphertextResult =
            CryptoCipher.encrypt(
                key = step.messageKey,
                nonce = step.nonce,
                plaintext = plaintext,
                associatedData = associatedData + headerBytes,
            )
        step.zeroizeMessageMaterial()
        return ciphertextResult.fold(
            onSuccess = { ciphertextWithTag ->
                ByteArrayZeroizer.zeroize(sendingChainKey)
                sendingChainKey = step.nextChainKey
                sendingMessageNumber += 1
                Result.success(headerBytes + ciphertextWithTag)
            },
            onFailure = { error ->
                ByteArrayZeroizer.zeroize(step.nextChainKey)
                Result.failure(error)
            },
        )
    }

    private fun dispatchDecrypt(
        header: RatchetHeader,
        ciphertextWithTag: ByteArray,
        fullAd: ByteArray,
    ): Result<ByteArray> {
        val skippedId = SkippedKeyId(header.dhPublicKey, header.messageNumber)
        return when {
            skippedMessageKeys.containsKey(skippedId) ->
                decryptWithSkippedKey(skippedId, ciphertextWithTag, fullAd)
            header.dhPublicKey != remoteDhPublicKey ->
                decryptWithDhRatchet(header, ciphertextWithTag, fullAd)
            else ->
                decryptOnCurrentChain(header, ciphertextWithTag, fullAd)
        }
    }

    private fun decryptWithSkippedKey(
        skippedId: SkippedKeyId,
        ciphertextWithTag: ByteArray,
        fullAd: ByteArray,
    ): Result<ByteArray> {
        val entry = skippedMessageKeys.getValue(skippedId)
        return CryptoCipher.decrypt(
            key = entry.messageKey,
            nonce = entry.nonce,
            ciphertextWithTag = ciphertextWithTag,
            associatedData = fullAd,
        ).onSuccess {
            skippedMessageKeys.remove(skippedId)?.zeroize()
        }
    }

    private fun decryptOnCurrentChain(
        header: RatchetHeader,
        ciphertextWithTag: ByteArray,
        fullAd: ByteArray,
    ): Result<ByteArray> {
        val currentChain = receivingChainKey
        val validationError =
            if (currentChain == null) {
                DecryptionFailedError("Receiving chain is not initialized.")
            } else {
                validateSameChainCounters(
                    messageNumber = header.messageNumber,
                    receivingMessageNumber = receivingMessageNumber,
                    maxSkippedKeys = maxSkippedKeys,
                    currentSkippedCount = skippedMessageKeys.size,
                )
            }
        return if (validationError != null) {
            validationError.asFailure()
        } else {
            advanceChainTentatively(
                startingChainKey = currentChain!!,
                dhPublicKey = header.dhPublicKey,
                startNumber = receivingMessageNumber,
                targetNumber = header.messageNumber,
            ).fold(
                onSuccess = { advance -> applySameChainDecrypt(header, advance, ciphertextWithTag, fullAd) },
                onFailure = { Result.failure(it) },
            )
        }
    }

    private fun applySameChainDecrypt(
        header: RatchetHeader,
        advance: TentativeChainAdvance,
        ciphertextWithTag: ByteArray,
        fullAd: ByteArray,
    ): Result<ByteArray> =
        CryptoCipher.decrypt(
            key = advance.targetKey.messageKey,
            nonce = advance.targetKey.nonce,
            ciphertextWithTag = ciphertextWithTag,
            associatedData = fullAd,
        ).fold(
            onSuccess = { plaintext ->
                advance.targetKey.zeroize()
                ByteArrayZeroizer.zeroize(receivingChainKey)
                receivingChainKey = advance.finalChainKey
                receivingMessageNumber = header.messageNumber + 1
                advance.skipped.forEach { (keyId, messageKey) ->
                    skippedMessageKeys.put(keyId, messageKey)?.zeroize()
                }
                Result.success(plaintext)
            },
            onFailure = { error ->
                advance.zeroizeAll()
                Result.failure(error)
            },
        )

    private fun decryptWithDhRatchet(
        header: RatchetHeader,
        ciphertextWithTag: ByteArray,
        fullAd: ByteArray,
    ): Result<ByteArray> {
        val preValidation =
            validateDhRatchetCounters(
                header = header,
                hasReceivingChain = receivingChainKey != null,
                receivingMessageNumber = receivingMessageNumber,
                maxSkippedKeys = maxSkippedKeys,
                currentSkippedCount = skippedMessageKeys.size,
            )
        return if (preValidation != null) {
            preValidation.asFailure()
        } else {
            collectPreviousChainSkipped(
                existingChain = receivingChainKey,
                existingRemoteDh = remoteDhPublicKey,
                receivingMessageNumber = receivingMessageNumber,
                targetPreviousLength = header.previousChainLength,
            ).fold(
                onSuccess = { prevSkipped ->
                    prepareTentativeDhRatchet(
                        rootKey = rootKey,
                        localDhKeyPair = localDhKeyPair,
                        header = header,
                        prevSkipped = prevSkipped,
                    ).fold(
                        onSuccess = { tentative ->
                            commitOrRollbackDhRatchet(header, tentative, ciphertextWithTag, fullAd)
                        },
                        onFailure = { Result.failure(it) },
                    )
                },
                onFailure = { Result.failure(it) },
            )
        }
    }

    private fun commitOrRollbackDhRatchet(
        header: RatchetHeader,
        tentative: TentativeDhRatchet,
        ciphertextWithTag: ByteArray,
        fullAd: ByteArray,
    ): Result<ByteArray> =
        CryptoCipher.decrypt(
            key = tentative.advance.targetKey.messageKey,
            nonce = tentative.advance.targetKey.nonce,
            ciphertextWithTag = ciphertextWithTag,
            associatedData = fullAd,
        ).fold(
            onSuccess = { plaintext ->
                tentative.advance.targetKey.zeroize()
                ByteArrayZeroizer.zeroize(rootKey, sendingChainKey, receivingChainKey)
                localDhKeyPair.zeroize()
                previousSendingChainLength = sendingMessageNumber
                sendingMessageNumber = 0
                receivingMessageNumber = header.messageNumber + 1
                remoteDhPublicKey = header.dhPublicKey
                localDhKeyPair = tentative.newLocalDh
                rootKey = tentative.sendRootOutput.rootKey
                sendingChainKey = tentative.sendRootOutput.chainKey
                receivingChainKey = tentative.advance.finalChainKey
                tentative.prevSkipped.forEach { (keyId, messageKey) ->
                    skippedMessageKeys.put(keyId, messageKey)?.zeroize()
                }
                tentative.advance.skipped.forEach { (keyId, messageKey) ->
                    skippedMessageKeys.put(keyId, messageKey)?.zeroize()
                }
                Result.success(plaintext)
            },
            onFailure = { error ->
                tentative.zeroizeAll()
                Result.failure(error)
            },
        )

    companion object {
        private const val ENTITY_NAME: String = "CryptoSession"
        private const val STATE_DESTROYED: String = "DESTROYED"
        private const val ACTION_ENCRYPT: String = "ENCRYPT"
        private const val ACTION_DECRYPT: String = "DECRYPT"

        private fun <T> destroyedFailure(action: String): Result<T> =
            InvalidStateTransitionError(
                entity = ENTITY_NAME,
                fromState = STATE_DESTROYED,
                toState = action,
            ).asFailure()

        /**
         * Creates an initiator [CryptoSession] from [rootKey] and the responder's [remoteDhPublicKey].
         */
        fun createInitiator(
            rootKey: ByteArray,
            remoteDhPublicKey: PublicKey,
            maxSkippedKeys: Int = CryptoConstants.MAX_SKIPPED_MESSAGE_KEYS,
        ): Result<CryptoSession> {
            val localDh = EphemeralKeyPair.generate()
            return localDh.computeSharedSecret(remoteDhPublicKey).fold(
                onSuccess = { dhSecret ->
                    val rootOutputResult =
                        ByteArrayZeroizer.useAndZeroize(dhSecret) { secret ->
                            KdfChain.kdfRootKey(rootKey, secret)
                        }
                    rootOutputResult.fold(
                        onSuccess = { rootOutput ->
                            KdfChain.deriveInitialResponderChainKey(rootKey).fold(
                                onSuccess = { initialReceivingChain ->
                                    Result.success(
                                        CryptoSession(
                                            rootKey = rootOutput.rootKey,
                                            localDhKeyPair = localDh,
                                            remoteDhPublicKey = remoteDhPublicKey,
                                            sendingChainKey = rootOutput.chainKey,
                                            receivingChainKey = initialReceivingChain,
                                            maxSkippedKeys = maxSkippedKeys,
                                        ),
                                    )
                                },
                                onFailure = { error ->
                                    localDh.zeroize()
                                    rootOutput.zeroize()
                                    Result.failure(error)
                                },
                            )
                        },
                        onFailure = { error ->
                            localDh.zeroize()
                            Result.failure(error)
                        },
                    )
                },
                onFailure = { error ->
                    localDh.zeroize()
                    Result.failure(error)
                },
            )
        }

        /**
         * Creates a responder [CryptoSession] owning [localDhKeyPair] and [rootKey].
         */
        fun createResponder(
            rootKey: ByteArray,
            localDhKeyPair: EphemeralKeyPair,
            maxSkippedKeys: Int = CryptoConstants.MAX_SKIPPED_MESSAGE_KEYS,
        ): Result<CryptoSession> =
            KdfChain.deriveInitialResponderChainKey(rootKey).map { initialSendingChain ->
                CryptoSession(
                    rootKey = rootKey.copyOf(),
                    localDhKeyPair = localDhKeyPair,
                    remoteDhPublicKey = null,
                    sendingChainKey = initialSendingChain,
                    receivingChainKey = null,
                    maxSkippedKeys = maxSkippedKeys,
                )
            }
    }
}

private data class SkippedKeyId(
    val dhPublicKey: PublicKey,
    val messageNumber: Int,
)

private class SkippedMessageKey(
    val messageKey: ByteArray,
    val nonce: ByteArray,
) {
    fun zeroize() {
        ByteArrayZeroizer.zeroize(messageKey, nonce)
    }
}

private class TentativeChainAdvance(
    val finalChainKey: ByteArray,
    val targetKey: ChainKdfOutput,
    val skipped: List<Pair<SkippedKeyId, SkippedMessageKey>>,
) {
    fun zeroizeAll() {
        ByteArrayZeroizer.zeroize(finalChainKey)
        targetKey.zeroize()
        skipped.forEach { (_, entry) -> entry.zeroize() }
    }
}

private class TentativeDhRatchet(
    val prevSkipped: List<Pair<SkippedKeyId, SkippedMessageKey>>,
    val newLocalDh: EphemeralKeyPair,
    val sendRootOutput: RootKdfOutput,
    val advance: TentativeChainAdvance,
) {
    fun zeroizeAll() {
        advance.zeroizeAll()
        newLocalDh.zeroize()
        sendRootOutput.zeroize()
        zeroizeSkippedList(prevSkipped)
    }
}

private fun validateSameChainCounters(
    messageNumber: Int,
    receivingMessageNumber: Int,
    maxSkippedKeys: Int,
    currentSkippedCount: Int,
): DecryptionFailedError? {
    val skipCount = messageNumber - receivingMessageNumber
    return when {
        messageNumber < receivingMessageNumber ->
            DecryptionFailedError("Message replay detected for number $messageNumber.")
        skipCount > maxSkippedKeys ->
            DecryptionFailedError("Skipped message count $skipCount exceeds limit $maxSkippedKeys.")
        currentSkippedCount + skipCount > CryptoConstants.MAX_SKIPPED_KEYS_TOTAL ->
            DecryptionFailedError(
                "Total skipped key cache limit ${CryptoConstants.MAX_SKIPPED_KEYS_TOTAL} exceeded.",
            )
        else -> null
    }
}

private fun validateDhRatchetCounters(
    header: RatchetHeader,
    hasReceivingChain: Boolean,
    receivingMessageNumber: Int,
    maxSkippedKeys: Int,
    currentSkippedCount: Int,
): DecryptionFailedError? {
    val prevSkipCount = header.previousChainLength - receivingMessageNumber
    val totalAddition = prevSkipCount.coerceAtLeast(0) + header.messageNumber
    return when {
        !hasReceivingChain && header.previousChainLength > 0 ->
            DecryptionFailedError("Unexpected previous chain length before initial receive chain.")
        hasReceivingChain && header.previousChainLength < receivingMessageNumber ->
            DecryptionFailedError("Stale previous chain length ${header.previousChainLength}.")
        hasReceivingChain && prevSkipCount > maxSkippedKeys ->
            DecryptionFailedError("Previous chain skip count $prevSkipCount exceeds limit $maxSkippedKeys.")
        header.messageNumber > maxSkippedKeys ->
            DecryptionFailedError("Message number ${header.messageNumber} exceeds limit $maxSkippedKeys.")
        currentSkippedCount + totalAddition > CryptoConstants.MAX_SKIPPED_KEYS_TOTAL ->
            DecryptionFailedError(
                "Total skipped key cache limit ${CryptoConstants.MAX_SKIPPED_KEYS_TOTAL} exceeded.",
            )
        else -> null
    }
}

private fun collectPreviousChainSkipped(
    existingChain: ByteArray?,
    existingRemoteDh: PublicKey?,
    receivingMessageNumber: Int,
    targetPreviousLength: Int,
): Result<List<Pair<SkippedKeyId, SkippedMessageKey>>> =
    if (existingChain == null || existingRemoteDh == null || receivingMessageNumber >= targetPreviousLength) {
        Result.success(emptyList())
    } else {
        val skippedCount = targetPreviousLength - receivingMessageNumber
        val skipped = ArrayList<Pair<SkippedKeyId, SkippedMessageKey>>(skippedCount)
        var cursor = existingChain.copyOf()
        var failure: Throwable? = null
        for (index in receivingMessageNumber until targetPreviousLength) {
            val stepResult = KdfChain.kdfChainKey(cursor)
            ByteArrayZeroizer.zeroize(cursor)
            val step = stepResult.getOrNull()
            if (step == null) {
                failure = stepResult.exceptionOrNull()
                break
            }
            cursor = step.nextChainKey
            skipped.add(
                SkippedKeyId(existingRemoteDh, index) to SkippedMessageKey(step.messageKey, step.nonce),
            )
        }
        if (failure != null) {
            zeroizeSkippedList(skipped)
            Result.failure(failure)
        } else {
            ByteArrayZeroizer.zeroize(cursor)
            Result.success(skipped)
        }
    }

private fun advanceChainTentatively(
    startingChainKey: ByteArray,
    dhPublicKey: PublicKey,
    startNumber: Int,
    targetNumber: Int,
): Result<TentativeChainAdvance> {
    val skipped = ArrayList<Pair<SkippedKeyId, SkippedMessageKey>>(targetNumber - startNumber)
    var cursor = startingChainKey.copyOf()
    var failure: Throwable? = null
    for (index in startNumber until targetNumber) {
        val stepResult = KdfChain.kdfChainKey(cursor)
        ByteArrayZeroizer.zeroize(cursor)
        val step = stepResult.getOrNull()
        if (step == null) {
            failure = stepResult.exceptionOrNull()
            break
        }
        cursor = step.nextChainKey
        skipped.add(
            SkippedKeyId(dhPublicKey, index) to SkippedMessageKey(step.messageKey, step.nonce),
        )
    }
    return if (failure != null) {
        zeroizeSkippedList(skipped)
        Result.failure(failure)
    } else {
        val targetResult = KdfChain.kdfChainKey(cursor)
        ByteArrayZeroizer.zeroize(cursor)
        targetResult.fold(
            onSuccess = { targetStep ->
                Result.success(
                    TentativeChainAdvance(
                        finalChainKey = targetStep.nextChainKey.copyOf(),
                        targetKey = targetStep,
                        skipped = skipped,
                    ),
                )
            },
            onFailure = { error ->
                zeroizeSkippedList(skipped)
                Result.failure(error)
            },
        )
    }
}

private fun prepareTentativeDhRatchet(
    rootKey: ByteArray,
    localDhKeyPair: EphemeralKeyPair,
    header: RatchetHeader,
    prevSkipped: List<Pair<SkippedKeyId, SkippedMessageKey>>,
): Result<TentativeDhRatchet> =
    localDhKeyPair.computeSharedSecret(header.dhPublicKey).fold(
        onSuccess = { dhReceive ->
            ByteArrayZeroizer.useAndZeroize(dhReceive) { secret ->
                KdfChain.kdfRootKey(rootKey, secret)
            }.fold(
                onSuccess = { receiveRoot ->
                    deriveSendChainAndAdvance(header, prevSkipped, receiveRoot)
                },
                onFailure = { error ->
                    zeroizeSkippedList(prevSkipped)
                    Result.failure(error)
                },
            )
        },
        onFailure = { error ->
            zeroizeSkippedList(prevSkipped)
            Result.failure(error)
        },
    )

private fun deriveSendChainAndAdvance(
    header: RatchetHeader,
    prevSkipped: List<Pair<SkippedKeyId, SkippedMessageKey>>,
    receiveRoot: RootKdfOutput,
): Result<TentativeDhRatchet> {
    val newLocalDh = EphemeralKeyPair.generate()
    return newLocalDh.computeSharedSecret(header.dhPublicKey).fold(
        onSuccess = { dhSend ->
            val sendRootResult =
                ByteArrayZeroizer.useAndZeroize(dhSend) { secret ->
                    KdfChain.kdfRootKey(receiveRoot.rootKey, secret)
                }
            ByteArrayZeroizer.zeroize(receiveRoot.rootKey)
            sendRootResult.fold(
                onSuccess = { sendRoot ->
                    val advanceResult =
                        advanceChainTentatively(
                            startingChainKey = receiveRoot.chainKey,
                            dhPublicKey = header.dhPublicKey,
                            startNumber = 0,
                            targetNumber = header.messageNumber,
                        )
                    ByteArrayZeroizer.zeroize(receiveRoot.chainKey)
                    advanceResult.fold(
                        onSuccess = { advance ->
                            Result.success(TentativeDhRatchet(prevSkipped, newLocalDh, sendRoot, advance))
                        },
                        onFailure = { error ->
                            newLocalDh.zeroize()
                            sendRoot.zeroize()
                            zeroizeSkippedList(prevSkipped)
                            Result.failure(error)
                        },
                    )
                },
                onFailure = { error ->
                    newLocalDh.zeroize()
                    ByteArrayZeroizer.zeroize(receiveRoot.chainKey)
                    zeroizeSkippedList(prevSkipped)
                    Result.failure(error)
                },
            )
        },
        onFailure = { error ->
            newLocalDh.zeroize()
            receiveRoot.zeroize()
            zeroizeSkippedList(prevSkipped)
            Result.failure(error)
        },
    )
}

private fun zeroizeSkippedList(entries: List<Pair<SkippedKeyId, SkippedMessageKey>>) {
    for ((_, entry) in entries) {
        entry.zeroize()
    }
}

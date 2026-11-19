package com.bluemessenger.crypto.util

import com.bluemessenger.crypto.CryptoEngine
import com.bluemessenger.crypto.constants.CryptoConstants
import com.bluemessenger.domain.error.DecryptionFailedError
import com.bluemessenger.domain.error.asFailure

/**
 * Output of a Double Ratchet root-key KDF step containing the next root key and chain key.
 */
internal class RootKdfOutput(
    val rootKey: ByteArray,
    val chainKey: ByteArray,
) {
    /**
     * Erases both derived key arrays from memory.
     */
    fun zeroize() {
        ByteArrayZeroizer.zeroize(rootKey, chainKey)
    }
}

/**
 * Output of a symmetric chain-key KDF step containing the next chain key, message key, and nonce.
 */
internal class ChainKdfOutput(
    val nextChainKey: ByteArray,
    val messageKey: ByteArray,
    val nonce: ByteArray,
) {
    /**
     * Erases the ephemeral message key and nonce while leaving [nextChainKey] intact.
     */
    fun zeroizeMessageMaterial() {
        ByteArrayZeroizer.zeroize(messageKey, nonce)
    }

    /**
     * Erases all key and nonce material from memory.
     */
    fun zeroize() {
        ByteArrayZeroizer.zeroize(nextChainKey, messageKey, nonce)
    }
}

/**
 * Stateless HKDF/HMAC key derivation functions for Double Ratchet root and symmetric chains.
 *
 * Thread safety: Stateless and safe for concurrent invocation.
 *
 * Error handling: Fails fast with [DecryptionFailedError] if input key material sizes are invalid.
 */
internal object KdfChain {
    private const val DOUBLE_KEY_OUTPUT_BYTES: Int =
        CryptoConstants.SYMMETRIC_KEY_LENGTH_BYTES + CryptoConstants.SYMMETRIC_KEY_LENGTH_BYTES
    private const val MESSAGE_MATERIAL_OUTPUT_BYTES: Int =
        CryptoConstants.SYMMETRIC_KEY_LENGTH_BYTES + CryptoConstants.NONCE_LENGTH_BYTES
    private const val CHAIN_KEY_STEP_BYTE: Byte = 0x02
    private const val MESSAGE_KEY_STEP_BYTE: Byte = 0x01

    private val rootKdfInfo: ByteArray = "BlueMessenger_Ratchet_RootKDF_v1".encodeToByteArray()
    private val messageExpandInfo: ByteArray = "BlueMessenger_Ratchet_MessageKey_v1".encodeToByteArray()
    private val initialChainInfo: ByteArray = "BlueMessenger_Ratchet_InitialResponderChain_v1".encodeToByteArray()
    private val chainKeyStepInput: ByteArray = byteArrayOf(CHAIN_KEY_STEP_BYTE)
    private val messageKeyStepInput: ByteArray = byteArrayOf(MESSAGE_KEY_STEP_BYTE)

    /**
     * Derives the next root key and chain key from [rootKey] and X25519 [dhOutput] via HKDF-SHA256.
     */
    fun kdfRootKey(
        rootKey: ByteArray,
        dhOutput: ByteArray,
    ): Result<RootKdfOutput> {
        if (
            rootKey.size != CryptoConstants.SYMMETRIC_KEY_LENGTH_BYTES ||
            dhOutput.size != CryptoConstants.X25519_KEY_LENGTH_BYTES
        ) {
            return DecryptionFailedError("Invalid key length for root KDF step.").asFailure()
        }
        val derived =
            CryptoEngine.hkdfSha256(
                ikm = dhOutput,
                salt = rootKey,
                info = rootKdfInfo,
                outputLength = DOUBLE_KEY_OUTPUT_BYTES,
            )
        return ByteArrayZeroizer.useAndZeroize(derived) { buffer ->
            val nextRoot = buffer.copyOfRange(0, CryptoConstants.SYMMETRIC_KEY_LENGTH_BYTES)
            val nextChain = buffer.copyOfRange(CryptoConstants.SYMMETRIC_KEY_LENGTH_BYTES, DOUBLE_KEY_OUTPUT_BYTES)
            Result.success(RootKdfOutput(rootKey = nextRoot, chainKey = nextChain))
        }
    }

    /**
     * Advances [chainKey] via HMAC-SHA256 and expands a unique message key and nonce via HKDF-SHA256.
     */
    fun kdfChainKey(chainKey: ByteArray): Result<ChainKdfOutput> {
        if (chainKey.size != CryptoConstants.SYMMETRIC_KEY_LENGTH_BYTES) {
            return DecryptionFailedError("Invalid chain key length for symmetric KDF step.").asFailure()
        }
        val nextChainKey = CryptoEngine.hmacSha256(key = chainKey, data = chainKeyStepInput)
        val messageSeed = CryptoEngine.hmacSha256(key = chainKey, data = messageKeyStepInput)
        return ByteArrayZeroizer.useAndZeroize(messageSeed) { seed ->
            val expanded =
                CryptoEngine.hkdfSha256(
                    ikm = seed,
                    salt = ByteArray(0),
                    info = messageExpandInfo,
                    outputLength = MESSAGE_MATERIAL_OUTPUT_BYTES,
                )
            ByteArrayZeroizer.useAndZeroize(expanded) { buffer ->
                val messageKey = buffer.copyOfRange(0, CryptoConstants.SYMMETRIC_KEY_LENGTH_BYTES)
                val nonce =
                    buffer.copyOfRange(CryptoConstants.SYMMETRIC_KEY_LENGTH_BYTES, MESSAGE_MATERIAL_OUTPUT_BYTES)
                Result.success(
                    ChainKdfOutput(
                        nextChainKey = nextChainKey,
                        messageKey = messageKey,
                        nonce = nonce,
                    ),
                )
            }
        }
    }

    /**
     * Derives an initial responder-to-initiator symmetric chain key from [rootKey].
     */
    fun deriveInitialResponderChainKey(rootKey: ByteArray): Result<ByteArray> {
        if (rootKey.size != CryptoConstants.SYMMETRIC_KEY_LENGTH_BYTES) {
            return DecryptionFailedError("Invalid root key length for initial chain derivation.").asFailure()
        }
        return Result.success(
            CryptoEngine.hkdfSha256(
                ikm = rootKey,
                salt = ByteArray(0),
                info = initialChainInfo,
                outputLength = CryptoConstants.SYMMETRIC_KEY_LENGTH_BYTES,
            ),
        )
    }
}

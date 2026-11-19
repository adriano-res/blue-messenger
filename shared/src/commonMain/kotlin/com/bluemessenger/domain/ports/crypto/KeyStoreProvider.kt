package com.bluemessenger.domain.ports.crypto

import com.bluemessenger.domain.error.DomainException
import com.bluemessenger.domain.primitives.PublicKey

/**
 * Platform secure-storage port for long-term local identity keys (Android Keystore / Linux
 * Secret Service).
 *
 * Thread safety: Implementations must serialize keystore mutations and perform blocking OS keystore
 * IPC on an injected IO dispatcher.
 *
 * Error handling: Private key material must never leave hardware/OS protection boundaries. Missing
 * keys, locked keyrings, or signing errors must fail fast via [Result.failure] wrapping a
 * [DomainException] without falling back to insecure storage.
 */
interface KeyStoreProvider {
    /**
     * Returns whether a long-term identity key pair already exists in secure platform storage.
     */
    suspend fun hasIdentityKeyPair(): Result<Boolean>

    /**
     * Generates and persists a new long-term identity key pair, returning its [PublicKey].
     */
    suspend fun generateIdentityKeyPair(): Result<PublicKey>

    /**
     * Retrieves the stored identity [PublicKey], or `null` if no identity key pair has been created.
     */
    suspend fun getIdentityPublicKey(): Result<PublicKey?>

    /**
     * Signs [data] with the hardware/OS-protected identity private key without exporting key bytes.
     */
    suspend fun signWithIdentityKey(data: ByteArray): Result<ByteArray>

    /**
     * Permanently deletes the stored local identity key pair.
     */
    suspend fun deleteIdentityKeyPair(): Result<Unit>
}

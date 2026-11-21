package com.bluemessenger.domain.ports.crypto.model

import com.bluemessenger.domain.primitives.PublicKey

/**
 * Result produced by advancing an authenticated P2P key-exchange handshake step.
 */
class HandshakeStepResult private constructor(
    private val responsePayloadBytes: ByteArray?,
    val isComplete: Boolean,
    val remoteIdentityKey: PublicKey?,
) {
    /**
     * Returns an isolated defensive copy of the outbound handshake response payload, if any.
     */
    fun copyResponsePayload(): ByteArray? = responsePayloadBytes?.copyOf()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is HandshakeStepResult) return false
        val samePayload =
            when {
                responsePayloadBytes == null && other.responsePayloadBytes == null -> true
                responsePayloadBytes != null && other.responsePayloadBytes != null ->
                    responsePayloadBytes.contentEquals(other.responsePayloadBytes)
                else -> false
            }
        return samePayload &&
            isComplete == other.isComplete &&
            remoteIdentityKey == other.remoteIdentityKey
    }

    override fun hashCode(): Int {
        var result = responsePayloadBytes?.contentHashCode() ?: 0
        result = HASH_MULTIPLIER * result + isComplete.hashCode()
        result = HASH_MULTIPLIER * result + (remoteIdentityKey?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String =
        "HandshakeStepResult(hasResponse=${responsePayloadBytes != null}, isComplete=$isComplete, " +
            "remoteIdentityKey=$remoteIdentityKey)"

    companion object {
        private const val HASH_MULTIPLIER: Int = 31

        /**
         * Creates a [HandshakeStepResult] with a defensive copy of [responsePayload].
         */
        operator fun invoke(
            responsePayload: ByteArray?,
            isComplete: Boolean,
            remoteIdentityKey: PublicKey?,
        ): HandshakeStepResult =
            HandshakeStepResult(
                responsePayloadBytes = responsePayload?.copyOf(),
                isComplete = isComplete,
                remoteIdentityKey = remoteIdentityKey,
            )
    }
}

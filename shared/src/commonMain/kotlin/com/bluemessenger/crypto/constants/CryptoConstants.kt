package com.bluemessenger.crypto.constants

/**
 * Cryptographic protocol constants for ChaCha20-Poly1305, X25519, Ed25519, Noise XX,
 * Double Ratchet, and Safety Numbers.
 */
object CryptoConstants {
    /** Length in bytes of a Poly1305 authentication tag. */
    const val TAG_LENGTH_BYTES: Int = 16

    /** Length in bytes of an AEAD ChaCha20-Poly1305 nonce. */
    const val NONCE_LENGTH_BYTES: Int = 12

    /** Length in bytes of symmetric root, chain, and message keys. */
    const val SYMMETRIC_KEY_LENGTH_BYTES: Int = 32

    /** Total numeric digits in a human-verifiable Safety Number (SAS). */
    const val SAFETY_NUMBER_DIGITS: Int = 60

    /** Maximum allowed per-message cryptographic overhead in bytes (RNF05). */
    const val MAX_CRYPTO_OVERHEAD_BYTES: Int = 80

    internal const val BITS_PER_BYTE: Int = 8
    internal const val BYTE_MASK: Int = 0xFF
    internal const val TAG_LENGTH_BITS: Int = TAG_LENGTH_BYTES * BITS_PER_BYTE

    internal const val X25519_KEY_LENGTH_BYTES: Int = 32
    internal const val ED25519_PUBLIC_KEY_LENGTH_BYTES: Int = 32
    internal const val ED25519_PRIVATE_KEY_LENGTH_BYTES: Int = 32
    internal const val ED25519_SIGNATURE_LENGTH_BYTES: Int = 64
    internal const val SHA256_DIGEST_LENGTH_BYTES: Int = 32

    internal const val RATCHET_COUNTER_SIZE_BYTES: Int = 4
    internal const val RATCHET_HEADER_SIZE_BYTES: Int =
        X25519_KEY_LENGTH_BYTES + RATCHET_COUNTER_SIZE_BYTES + RATCHET_COUNTER_SIZE_BYTES
    internal const val RATCHET_OVERHEAD_BYTES: Int = RATCHET_HEADER_SIZE_BYTES + TAG_LENGTH_BYTES

    internal const val MAX_SKIPPED_MESSAGE_KEYS: Int = 256
    internal const val MAX_SKIPPED_KEYS_TOTAL: Int = 1024

    internal const val SAFETY_NUMBER_GROUP_DIGITS: Int = 5
    internal const val SAFETY_NUMBER_GROUPS: Int = SAFETY_NUMBER_DIGITS / SAFETY_NUMBER_GROUP_DIGITS
    internal const val SAFETY_NUMBER_ITERATIONS: Int = 5200
    internal const val SAFETY_NUMBER_CHUNK_BYTES: Int = 5
    internal const val SAFETY_NUMBER_MODULUS: Long = 100_000L

    internal const val HANDSHAKE_IDENTITY_PROOF_SIZE_BYTES: Int =
        ED25519_PUBLIC_KEY_LENGTH_BYTES + ED25519_SIGNATURE_LENGTH_BYTES
    internal const val HANDSHAKE_ENCRYPTED_PROOF_SIZE_BYTES: Int =
        HANDSHAKE_IDENTITY_PROOF_SIZE_BYTES + TAG_LENGTH_BYTES
    internal const val HANDSHAKE_MSG2_SIZE_BYTES: Int =
        X25519_KEY_LENGTH_BYTES + HANDSHAKE_ENCRYPTED_PROOF_SIZE_BYTES
}

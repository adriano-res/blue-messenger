package com.bluemessenger.crypto.constants

/**
 * Cryptographic, storage, and logging constants for the Android KeyStore identity key adapter.
 */
internal object AndroidKeyStoreConstants {
    const val KEYSTORE_PROVIDER: String = "AndroidKeyStore"
    const val MASTER_KEY_ALIAS: String = "com.bluemessenger.identity.master_key.v1"
    const val CIPHER_TRANSFORMATION: String = "AES/GCM/NoPadding"
    const val AES_KEY_SIZE_BITS: Int = 256
    const val GCM_TAG_LENGTH_BITS: Int = 128
    const val GCM_TAG_LENGTH_BYTES: Int = 16

    const val ENVELOPE_VERSION: Byte = 1
    const val ENVELOPE_HEADER_SIZE_BYTES: Int = 2
    const val BYTE_MASK: Int = 0xFF
    const val MAX_IV_LENGTH_BYTES: Int = 255
    const val VAULT_DIRECTORY_NAME: String = "keystore_vault"
    const val ENVELOPE_FILE_NAME: String = "identity_key_envelope.bin"
    const val TEMP_ENVELOPE_FILE_NAME: String = "identity_key_envelope.bin.tmp"

    const val TAG_KEYSTORE: String = "AndroidKeyStoreProviderImpl"
    const val KEYSTORE_FAILURE_PREFIX: String =
        "Android KeyStore secure storage operation failed."
}

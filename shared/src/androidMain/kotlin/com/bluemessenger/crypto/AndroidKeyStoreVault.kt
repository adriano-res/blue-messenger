package com.bluemessenger.crypto

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.bluemessenger.crypto.constants.AndroidKeyStoreConstants
import com.bluemessenger.crypto.util.ByteArrayZeroizer
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.KeyStoreException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypted envelope vault backed by a non-exportable AES-256-GCM master key in `"AndroidKeyStore"`
 * and authenticated ciphertext persistence inside [Context.getNoBackupFilesDir].
 *
 * Envelope wire layout: `[version (1B)] [ivLength (1B)] [iv (ivLength B)] [ciphertext + tag]`.
 *
 * Thread safety: Callers must serialize vault mutations and I/O externally (enforced by
 * [AndroidKeyStoreProviderImpl]'s `Mutex` on `CoroutineDispatchers.io`).
 *
 * Failure behavior: Never writes plaintext key material to disk; fails fast on security,
 * tampering (`AEADBadTagException`), corruption, or filesystem I/O errors while zeroizing
 * temporary buffers in `finally` blocks.
 */
internal open class AndroidKeyStoreVault internal constructor(
    private val vaultDirProvider: () -> File,
    private val masterKeyProvider: () -> SecretKey,
    private val masterKeyDeleter: () -> Unit,
) {
    constructor(context: Context) : this(
        vaultDirProvider = buildVaultDirProvider(context.applicationContext),
        masterKeyProvider = { getOrCreateAndroidKeyStoreKey() },
        masterKeyDeleter = { deleteAndroidKeyStoreKey() },
    )

    /**
     * Returns whether an encrypted identity key envelope file exists on disk.
     */
    open fun hasEnvelope(): Boolean {
        val file = envelopeFile()
        return file.exists() && file.isFile
    }

    /**
     * Encrypts [plaintextSecret] under the AES-256-GCM master key and persists the authenticated
     * envelope atomically via a temporary file inside `noBackupFilesDir`.
     */
    @Throws(GeneralSecurityException::class, IOException::class)
    open fun encryptAndStore(plaintextSecret: ByteArray) {
        val masterKey = masterKeyProvider()
        val cipher = Cipher.getInstance(AndroidKeyStoreConstants.CIPHER_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, masterKey)
        val iv = cipher.iv
        require(iv.isNotEmpty() && iv.size <= AndroidKeyStoreConstants.MAX_IV_LENGTH_BYTES) {
            "Invalid AES-GCM IV length: ${iv.size}."
        }
        val ciphertextWithTag = cipher.doFinal(plaintextSecret)
        val envelopeBytes = encodeEnvelope(iv, ciphertextWithTag)
        try {
            writeAtomically(envelopeBytes)
        } finally {
            ByteArrayZeroizer.zeroize(iv, ciphertextWithTag, envelopeBytes)
        }
    }

    /**
     * Loads and authenticates the encrypted identity envelope from disk, returning the decrypted
     * secret bytes or `null` when no envelope exists.
     */
    @Throws(GeneralSecurityException::class, IOException::class)
    open fun loadAndDecrypt(): ByteArray? {
        val file = envelopeFile()
        if (!file.exists()) {
            return null
        }
        val envelopeBytes = file.readBytes()
        var iv: ByteArray? = null
        var ciphertextWithTag: ByteArray? = null
        return try {
            val (parsedIv, parsedCiphertext) = decodeEnvelope(envelopeBytes)
            iv = parsedIv
            ciphertextWithTag = parsedCiphertext
            val masterKey = masterKeyProvider()
            val cipher = Cipher.getInstance(AndroidKeyStoreConstants.CIPHER_TRANSFORMATION)
            val spec = GCMParameterSpec(AndroidKeyStoreConstants.GCM_TAG_LENGTH_BITS, parsedIv)
            cipher.init(Cipher.DECRYPT_MODE, masterKey, spec)
            cipher.doFinal(parsedCiphertext)
        } finally {
            ByteArrayZeroizer.zeroize(envelopeBytes, iv, ciphertextWithTag)
        }
    }

    /**
     * Securely erases persisted envelope files and deletes the master key entry from KeyStore.
     */
    @Throws(GeneralSecurityException::class, IOException::class)
    open fun deleteAll() {
        wipeAndDeleteFile(tempEnvelopeFile())
        wipeAndDeleteFile(envelopeFile())
        masterKeyDeleter()
    }

    private fun vaultDirectory(): File = vaultDirProvider()

    private fun envelopeFile(): File = File(vaultDirectory(), AndroidKeyStoreConstants.ENVELOPE_FILE_NAME)

    private fun tempEnvelopeFile(): File = File(vaultDirectory(), AndroidKeyStoreConstants.TEMP_ENVELOPE_FILE_NAME)

    private fun encodeEnvelope(
        iv: ByteArray,
        ciphertextWithTag: ByteArray,
    ): ByteArray {
        val totalSize = AndroidKeyStoreConstants.ENVELOPE_HEADER_SIZE_BYTES + iv.size + ciphertextWithTag.size
        val output = ByteArray(totalSize)
        output[0] = AndroidKeyStoreConstants.ENVELOPE_VERSION
        output[1] = iv.size.toByte()
        iv.copyInto(output, destinationOffset = AndroidKeyStoreConstants.ENVELOPE_HEADER_SIZE_BYTES)
        ciphertextWithTag.copyInto(
            output,
            destinationOffset = AndroidKeyStoreConstants.ENVELOPE_HEADER_SIZE_BYTES + iv.size,
        )
        return output
    }

    private fun decodeEnvelope(envelopeBytes: ByteArray): Pair<ByteArray, ByteArray> {
        require(envelopeBytes.size >= AndroidKeyStoreConstants.ENVELOPE_HEADER_SIZE_BYTES) {
            "Corrupted KeyStore envelope: truncated header (${envelopeBytes.size} bytes)."
        }
        val version = envelopeBytes[0]
        require(version == AndroidKeyStoreConstants.ENVELOPE_VERSION) {
            "Unsupported KeyStore envelope version: $version."
        }
        val ivLength = envelopeBytes[1].toInt() and AndroidKeyStoreConstants.BYTE_MASK
        val minimumPayloadLength =
            AndroidKeyStoreConstants.ENVELOPE_HEADER_SIZE_BYTES +
                ivLength +
                AndroidKeyStoreConstants.GCM_TAG_LENGTH_BYTES
        require(ivLength > 0 && envelopeBytes.size > minimumPayloadLength) {
            "Corrupted KeyStore envelope: invalid IV or ciphertext length."
        }
        val ivStart = AndroidKeyStoreConstants.ENVELOPE_HEADER_SIZE_BYTES
        val cipherStart = ivStart + ivLength
        val iv = envelopeBytes.copyOfRange(ivStart, cipherStart)
        val ciphertextWithTag = envelopeBytes.copyOfRange(cipherStart, envelopeBytes.size)
        return iv to ciphertextWithTag
    }

    private fun writeAtomically(envelopeBytes: ByteArray) {
        val dir = vaultDirectory()
        if (!dir.exists() && !dir.mkdirs()) {
            throw IOException("Failed to create KeyStore vault directory: ${dir.absolutePath}")
        }
        val tempFile = tempEnvelopeFile()
        val targetFile = envelopeFile()
        try {
            FileOutputStream(tempFile).use { stream ->
                stream.write(envelopeBytes)
                stream.flush()
                stream.fd.sync()
            }
            moveReplacing(tempFile, targetFile)
        } finally {
            if (tempFile.exists()) {
                wipeAndDeleteFile(tempFile)
            }
        }
    }

    private fun moveReplacing(
        source: File,
        target: File,
    ) {
        try {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
    }

    private fun wipeAndDeleteFile(file: File) {
        if (!file.exists()) {
            return
        }
        val length = file.length().toInt().coerceAtLeast(0)
        if (length > 0) {
            val zeros = ByteArray(length)
            FileOutputStream(file).use { stream ->
                stream.write(zeros)
                stream.flush()
                stream.fd.sync()
            }
        }
        if (!file.delete() && file.exists()) {
            throw IOException("Failed to delete KeyStore vault file: ${file.name}")
        }
    }

    private companion object {
        private fun buildVaultDirProvider(applicationContext: Context): () -> File =
            {
                File(applicationContext.noBackupFilesDir, AndroidKeyStoreConstants.VAULT_DIRECTORY_NAME)
            }

        private fun getOrCreateAndroidKeyStoreKey(): SecretKey {
            val keyStore = KeyStore.getInstance(AndroidKeyStoreConstants.KEYSTORE_PROVIDER).apply { load(null) }
            if (keyStore.containsAlias(AndroidKeyStoreConstants.MASTER_KEY_ALIAS)) {
                val existingKey = keyStore.getKey(AndroidKeyStoreConstants.MASTER_KEY_ALIAS, null)
                if (existingKey is SecretKey) {
                    return existingKey
                }
                throw KeyStoreException(
                    "Entry '${AndroidKeyStoreConstants.MASTER_KEY_ALIAS}' in AndroidKeyStore is not a SecretKey.",
                )
            }
            val keyGenerator =
                KeyGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_AES,
                    AndroidKeyStoreConstants.KEYSTORE_PROVIDER,
                )
            val spec =
                KeyGenParameterSpec.Builder(
                    AndroidKeyStoreConstants.MASTER_KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(AndroidKeyStoreConstants.AES_KEY_SIZE_BITS)
                    .setRandomizedEncryptionRequired(true)
                    .build()
            keyGenerator.init(spec)
            return keyGenerator.generateKey()
        }

        private fun deleteAndroidKeyStoreKey() {
            val keyStore = KeyStore.getInstance(AndroidKeyStoreConstants.KEYSTORE_PROVIDER).apply { load(null) }
            if (keyStore.containsAlias(AndroidKeyStoreConstants.MASTER_KEY_ALIAS)) {
                keyStore.deleteEntry(AndroidKeyStoreConstants.MASTER_KEY_ALIAS)
            }
        }
    }
}

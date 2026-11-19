package com.bluemessenger.crypto

import com.bluemessenger.crypto.constants.CryptoConstants
import com.bluemessenger.domain.primitives.PublicKey

/**
 * Deterministic generator for 60-digit Safety Numbers (SAS) and canonical symmetric QR payloads.
 *
 * Thread safety: Stateless and safe to call concurrently.
 *
 * Symmetry: [generate], [generateDigits], and [generateQrPayload] sort the two [PublicKey] inputs
 * lexicographically by raw unsigned bytes so Alice and Bob always obtain identical results.
 */
object CryptoSafetyNumber {
    private const val QR_PAYLOAD_VERSION_PREFIX: String = "BM1"
    private const val GROUP_SEPARATOR: String = " "
    private const val QR_FIELD_SEPARATOR: String = ":"
    private const val ZERO_PAD_CHAR: Char = '0'

    private val safetyDomainBytes: ByteArray = "BlueMessenger_SafetyNumber_v1".encodeToByteArray()
    private val safetyExpandInfo: ByteArray = "BlueMessenger_SafetyNumber_Expand_v1".encodeToByteArray()

    /**
     * Generates a formatted 60-digit Safety Number organized as 12 space-separated 5-digit groups.
     */
    fun generate(
        localKey: PublicKey,
        remoteKey: PublicKey,
    ): String {
        val rawDigits = generateDigits(localKey, remoteKey)
        return rawDigits.chunked(CryptoConstants.SAFETY_NUMBER_GROUP_DIGITS).joinToString(GROUP_SEPARATOR)
    }

    /**
     * Generates the unformatted 60-character numeric Safety Number string.
     */
    fun generateDigits(
        localKey: PublicKey,
        remoteKey: PublicKey,
    ): String {
        val (firstKey, secondKey) = orderKeys(localKey, remoteKey)
        val firstBytes = firstKey.toByteArray()
        val secondBytes = secondKey.toByteArray()
        val initialInput = safetyDomainBytes + firstBytes + secondBytes
        var digest = CryptoEngine.sha256(initialInput)
        repeat(CryptoConstants.SAFETY_NUMBER_ITERATIONS - 1) {
            digest = CryptoEngine.sha256(digest + firstBytes + secondBytes)
        }
        val expanded =
            CryptoEngine.hkdfSha256(
                ikm = digest,
                salt = ByteArray(0),
                info = safetyExpandInfo,
                outputLength = CryptoConstants.SAFETY_NUMBER_GROUPS * CryptoConstants.SAFETY_NUMBER_CHUNK_BYTES,
            )
        val builder = StringBuilder(CryptoConstants.SAFETY_NUMBER_DIGITS)
        for (groupIndex in 0 until CryptoConstants.SAFETY_NUMBER_GROUPS) {
            val offset = groupIndex * CryptoConstants.SAFETY_NUMBER_CHUNK_BYTES
            val chunkValue = readFiveBytesUnsigned(expanded, offset)
            val groupDigits =
                (chunkValue % CryptoConstants.SAFETY_NUMBER_MODULUS)
                    .toString()
                    .padStart(CryptoConstants.SAFETY_NUMBER_GROUP_DIGITS, ZERO_PAD_CHAR)
            builder.append(groupDigits)
        }
        return builder.toString()
    }

    /**
     * Generates a canonical, order-independent QR payload string binding both public keys and SAS.
     */
    fun generateQrPayload(
        localKey: PublicKey,
        remoteKey: PublicKey,
    ): String {
        val (firstKey, secondKey) = orderKeys(localKey, remoteKey)
        val digits = generateDigits(firstKey, secondKey)
        return listOf(
            QR_PAYLOAD_VERSION_PREFIX,
            firstKey.toHex(),
            secondKey.toHex(),
            digits,
        ).joinToString(QR_FIELD_SEPARATOR)
    }

    private fun orderKeys(
        first: PublicKey,
        second: PublicKey,
    ): Pair<PublicKey, PublicKey> {
        val firstBytes = first.toByteArray()
        val secondBytes = second.toByteArray()
        val comparison = compareUnsignedBytes(firstBytes, secondBytes)
        return if (comparison <= 0) first to second else second to first
    }

    private fun compareUnsignedBytes(
        left: ByteArray,
        right: ByteArray,
    ): Int {
        val minLength = minOf(left.size, right.size)
        for (index in 0 until minLength) {
            val leftByte = left[index].toInt() and CryptoConstants.BYTE_MASK
            val rightByte = right[index].toInt() and CryptoConstants.BYTE_MASK
            if (leftByte != rightByte) {
                return leftByte.compareTo(rightByte)
            }
        }
        return left.size.compareTo(right.size)
    }

    private fun readFiveBytesUnsigned(
        source: ByteArray,
        offset: Int,
    ): Long {
        var result = 0L
        for (byteIndex in 0 until CryptoConstants.SAFETY_NUMBER_CHUNK_BYTES) {
            val unsigned = (source[offset + byteIndex].toInt() and CryptoConstants.BYTE_MASK).toLong()
            result = (result shl CryptoConstants.BITS_PER_BYTE) or unsigned
        }
        return result
    }
}

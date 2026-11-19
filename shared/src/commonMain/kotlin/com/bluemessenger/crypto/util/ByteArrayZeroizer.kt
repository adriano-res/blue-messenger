package com.bluemessenger.crypto.util

/**
 * Utility for deterministic in-memory erasure of sensitive byte arrays.
 *
 * Thread safety: Callers are responsible for synchronizing concurrent access to the passed arrays.
 */
object ByteArrayZeroizer {
    /**
     * Overwrites every non-null array in [arrays] with zeros.
     */
    fun zeroize(vararg arrays: ByteArray?) {
        for (array in arrays) {
            array?.fill(0)
        }
    }

    /**
     * Executes [block] with [secret] and unconditionally zeroizes [secret] in a `finally` block.
     */
    inline fun <T> useAndZeroize(
        secret: ByteArray,
        block: (ByteArray) -> T,
    ): T {
        try {
            return block(secret)
        } finally {
            zeroize(secret)
        }
    }
}

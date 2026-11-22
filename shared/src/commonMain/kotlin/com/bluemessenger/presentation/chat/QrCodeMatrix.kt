package com.bluemessenger.presentation.chat

import androidx.compose.runtime.Immutable

/**
 * Immutable square bit-matrix representing an encoded QR code.
 */
@Immutable
data class QrCodeMatrix(
    val size: Int,
    val darkModules: List<Boolean>,
) {
    init {
        require(size > 0) {
            "QR code matrix size must be positive, got $size."
        }
        require(darkModules.size == size * size) {
            "Expected ${size * size} QR modules for size $size, got ${darkModules.size}."
        }
    }

    /**
     * Returns whether the module at zero-based ([row], [col]) should be rendered dark.
     */
    fun isDark(
        row: Int,
        col: Int,
    ): Boolean {
        require(row in 0 until size && col in 0 until size) {
            "QR coordinates ($row, $col) out of bounds for size $size."
        }
        return darkModules[row * size + col]
    }
}

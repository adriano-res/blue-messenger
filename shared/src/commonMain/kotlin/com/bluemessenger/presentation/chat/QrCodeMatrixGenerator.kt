package com.bluemessenger.presentation.chat

/**
 * Multiplatform QR code bit-matrix encoder.
 *
 * Actual implementation:
 * `shared/src/jvmCommonMain/kotlin/com/bluemessenger/presentation/chat/QrCodeMatrixGenerator.kt`
 * (shared between `androidMain` and `jvmMain` via `shared/build.gradle.kts` `jvmCommonMain`).
 */
internal expect object QrCodeMatrixGenerator {
    /**
     * Encodes [content] into a square [QrCodeMatrix], failing fast with `InvalidInputError` when
     * [content] is blank.
     */
    fun encode(content: String): Result<QrCodeMatrix>
}

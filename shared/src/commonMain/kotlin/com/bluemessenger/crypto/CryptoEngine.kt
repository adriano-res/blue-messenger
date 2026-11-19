package com.bluemessenger.crypto

/**
 * Low-level cryptographic execution engine implemented at compile time via Kotlin Multiplatform
 * `expect`/`actual`.
 *
 * Why this declaration has no body here: `commonMain` is compiled as pure multiplatform Kotlin and
 * cannot import Java/JVM JAR dependencies such as BouncyCastle (`org.bouncycastle.*`) or
 * `java.security.SecureRandom`.
 *
 * Actual implementation: `shared/src/jvmCommonMain/kotlin/com/bluemessenger/crypto/CryptoEngine.kt`,
 * backed by BouncyCastle's Lightweight API (`bcprov-jdk18on`).
 *
 * Compile-time wiring: `shared/build.gradle.kts` (`applyDefaultHierarchyTemplate` groups
 * `androidTarget` and `jvm` into the shared `jvmCommonMain` intermediate source set).
 */
internal expect object CryptoEngine {
    fun randomBytes(length: Int): ByteArray

    fun generateX25519PrivateKey(): ByteArray

    fun deriveX25519PublicKey(privateKey: ByteArray): ByteArray

    fun x25519SharedSecret(
        privateKey: ByteArray,
        peerPublicKey: ByteArray,
    ): Result<ByteArray>

    fun generateEd25519PrivateKey(): ByteArray

    fun deriveEd25519PublicKey(privateKey: ByteArray): ByteArray

    fun ed25519Sign(
        privateKey: ByteArray,
        message: ByteArray,
    ): ByteArray

    fun ed25519Verify(
        publicKey: ByteArray,
        message: ByteArray,
        signature: ByteArray,
    ): Boolean

    fun constantTimeEquals(
        first: ByteArray,
        second: ByteArray,
    ): Boolean

    fun sha256(data: ByteArray): ByteArray

    fun hmacSha256(
        key: ByteArray,
        data: ByteArray,
    ): ByteArray

    fun hkdfSha256(
        ikm: ByteArray,
        salt: ByteArray,
        info: ByteArray,
        outputLength: Int,
    ): ByteArray

    fun chaCha20Poly1305Encrypt(
        key: ByteArray,
        nonce: ByteArray,
        plaintext: ByteArray,
        associatedData: ByteArray,
    ): ByteArray

    fun chaCha20Poly1305Decrypt(
        key: ByteArray,
        nonce: ByteArray,
        ciphertextWithTag: ByteArray,
        associatedData: ByteArray,
    ): Result<ByteArray>
}

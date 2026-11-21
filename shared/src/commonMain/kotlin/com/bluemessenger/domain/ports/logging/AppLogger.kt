package com.bluemessenger.domain.ports.logging

/**
 * Structured multiplatform logging port for domain use cases and infrastructure adapters.
 *
 * Security policy: Callers and implementations must NEVER log message plaintext, raw private or
 * session keys, or cryptographic secrets at any log level.
 *
 * Thread safety: Implementations must be safe to invoke concurrently from any coroutine dispatcher.
 */
interface AppLogger {
    /**
     * Logs a diagnostic event at debug level with structured metadata [context].
     */
    fun debug(
        tag: String,
        message: String,
        context: Map<String, String> = emptyMap(),
    )

    /**
     * Logs an operational lifecycle event at info level with structured metadata [context].
     */
    fun info(
        tag: String,
        message: String,
        context: Map<String, String> = emptyMap(),
    )

    /**
     * Logs a recoverable failure or degraded transport condition at warn level.
     */
    fun warn(
        tag: String,
        message: String,
        throwable: Throwable? = null,
        context: Map<String, String> = emptyMap(),
    )

    /**
     * Logs an unrecoverable operation or stream failure at error level.
     */
    fun error(
        tag: String,
        message: String,
        throwable: Throwable? = null,
        context: Map<String, String> = emptyMap(),
    )
}

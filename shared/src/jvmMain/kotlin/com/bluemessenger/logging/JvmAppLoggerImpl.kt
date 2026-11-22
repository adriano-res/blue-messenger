package com.bluemessenger.logging

import com.bluemessenger.domain.ports.logging.AppLogger
import com.bluemessenger.logging.constants.JvmLoggerConstants
import java.util.logging.Level
import java.util.logging.Logger

/**
 * JVM [AppLogger] implementation backed by [java.util.logging.Logger].
 *
 * Maps `debug` to [Level.FINE], `info` to [Level.INFO], `warn` to [Level.WARNING], and `error`
 * to [Level.SEVERE], formatting messages as `[tag] message` with deterministically sorted
 * `key=value` context pairs.
 *
 * Thread safety: Thread-safe; delegates directly to the thread-safe [java.util.logging.Logger]
 * instance and performs no shared mutable state updates.
 *
 * Failure behavior: Logging never throws on normal inputs; underlying [java.util.logging.Handler]
 * errors are handled by JUL's configured `ErrorManager`.
 */
class JvmAppLoggerImpl(
    private val julLogger: Logger = Logger.getLogger(JvmLoggerConstants.DEFAULT_LOGGER_NAME),
) : AppLogger {
    /**
     * Logs [message] under [tag] at [Level.FINE] with sorted [context] metadata.
     */
    override fun debug(
        tag: String,
        message: String,
        context: Map<String, String>,
    ) {
        emit(level = Level.FINE, tag = tag, message = message, throwable = null, context = context)
    }

    /**
     * Logs [message] under [tag] at [Level.INFO] with sorted [context] metadata.
     */
    override fun info(
        tag: String,
        message: String,
        context: Map<String, String>,
    ) {
        emit(level = Level.INFO, tag = tag, message = message, throwable = null, context = context)
    }

    /**
     * Logs [message] under [tag] at [Level.WARNING] with optional [throwable] and sorted [context].
     */
    override fun warn(
        tag: String,
        message: String,
        throwable: Throwable?,
        context: Map<String, String>,
    ) {
        emit(level = Level.WARNING, tag = tag, message = message, throwable = throwable, context = context)
    }

    /**
     * Logs [message] under [tag] at [Level.SEVERE] with optional [throwable] and sorted [context].
     */
    override fun error(
        tag: String,
        message: String,
        throwable: Throwable?,
        context: Map<String, String>,
    ) {
        emit(level = Level.SEVERE, tag = tag, message = message, throwable = throwable, context = context)
    }

    private fun emit(
        level: Level,
        tag: String,
        message: String,
        throwable: Throwable?,
        context: Map<String, String>,
    ) {
        if (!julLogger.isLoggable(level)) {
            return
        }
        val formattedMessage = formatMessage(tag = tag, message = message, context = context)
        if (throwable == null) {
            julLogger.log(level, formattedMessage)
        } else {
            julLogger.log(level, formattedMessage, throwable)
        }
    }

    private fun formatMessage(
        tag: String,
        message: String,
        context: Map<String, String>,
    ): String {
        val base =
            buildString {
                append(JvmLoggerConstants.TAG_PREFIX)
                append(tag)
                append(JvmLoggerConstants.TAG_SUFFIX)
                append(message)
            }
        if (context.isEmpty()) {
            return base
        }
        val sortedContext =
            context.entries
                .sortedBy { entry -> entry.key }
                .joinToString(separator = JvmLoggerConstants.CONTEXT_ENTRY_DELIMITER) { entry ->
                    "${entry.key}${JvmLoggerConstants.CONTEXT_KEY_VALUE_SEPARATOR}${entry.value}"
                }
        return "$base${JvmLoggerConstants.CONTEXT_SEPARATOR}$sortedContext"
    }
}

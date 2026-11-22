package com.bluemessenger.logging

import android.util.Log
import com.bluemessenger.domain.ports.logging.AppLogger
import com.bluemessenger.logging.constants.AndroidLoggerConstants

/**
 * Android [AppLogger] implementation backed by [android.util.Log].
 *
 * Formats log messages with deterministically sorted `key=value` [context] pairs while passing
 * [tag] directly to Logcat.
 *
 * Thread safety: Thread-safe; delegates directly to [android.util.Log] or the injected
 * stateless log sink without mutable shared state.
 *
 * Failure behavior: Never exposes cryptographic secrets or message plaintext when callers adhere
 * to [AppLogger] security policy.
 */
class AndroidAppLoggerImpl internal constructor(
    private val logSink: (level: Int, tag: String, message: String, throwable: Throwable?) -> Unit,
) : AppLogger {
    constructor() : this(
        logSink = ::dispatchToLogcat,
    )

    /**
     * Logs [message] under [tag] at [Log.DEBUG] with sorted [context] metadata.
     */
    override fun debug(
        tag: String,
        message: String,
        context: Map<String, String>,
    ) {
        emit(
            level = AndroidLoggerConstants.LEVEL_DEBUG,
            tag = tag,
            message = message,
            throwable = null,
            context = context,
        )
    }

    /**
     * Logs [message] under [tag] at [Log.INFO] with sorted [context] metadata.
     */
    override fun info(
        tag: String,
        message: String,
        context: Map<String, String>,
    ) {
        emit(
            level = AndroidLoggerConstants.LEVEL_INFO,
            tag = tag,
            message = message,
            throwable = null,
            context = context,
        )
    }

    /**
     * Logs [message] under [tag] at [Log.WARN] with optional [throwable] and sorted [context].
     */
    override fun warn(
        tag: String,
        message: String,
        throwable: Throwable?,
        context: Map<String, String>,
    ) {
        emit(
            level = AndroidLoggerConstants.LEVEL_WARN,
            tag = tag,
            message = message,
            throwable = throwable,
            context = context,
        )
    }

    /**
     * Logs [message] under [tag] at [Log.ERROR] with optional [throwable] and sorted [context].
     */
    override fun error(
        tag: String,
        message: String,
        throwable: Throwable?,
        context: Map<String, String>,
    ) {
        emit(
            level = AndroidLoggerConstants.LEVEL_ERROR,
            tag = tag,
            message = message,
            throwable = throwable,
            context = context,
        )
    }

    private fun emit(
        level: Int,
        tag: String,
        message: String,
        throwable: Throwable?,
        context: Map<String, String>,
    ) {
        val formattedMessage = formatMessage(message = message, context = context)
        logSink(level, tag, formattedMessage, throwable)
    }

    private fun formatMessage(
        message: String,
        context: Map<String, String>,
    ): String {
        if (context.isEmpty()) {
            return message
        }
        val sortedContext =
            context.entries
                .sortedBy { entry -> entry.key }
                .joinToString(separator = AndroidLoggerConstants.CONTEXT_ENTRY_DELIMITER) { entry ->
                    "${entry.key}${AndroidLoggerConstants.CONTEXT_KEY_VALUE_SEPARATOR}${entry.value}"
                }
        return "$message${AndroidLoggerConstants.CONTEXT_SEPARATOR}$sortedContext"
    }

    private companion object {
        private fun dispatchToLogcat(
            level: Int,
            tag: String,
            message: String,
            throwable: Throwable?,
        ) {
            when (level) {
                AndroidLoggerConstants.LEVEL_DEBUG -> Log.d(tag, message)
                AndroidLoggerConstants.LEVEL_INFO -> Log.i(tag, message)
                AndroidLoggerConstants.LEVEL_WARN -> {
                    if (throwable == null) {
                        Log.w(tag, message)
                    } else {
                        Log.w(tag, message, throwable)
                    }
                }
                AndroidLoggerConstants.LEVEL_ERROR -> {
                    if (throwable == null) {
                        Log.e(tag, message)
                    } else {
                        Log.e(tag, message, throwable)
                    }
                }
            }
        }
    }
}

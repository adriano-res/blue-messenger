package com.bluemessenger.logging.constants

/**
 * Formatting and default logger constants for the JVM `java.util.logging` adapter.
 */
internal object JvmLoggerConstants {
    const val DEFAULT_LOGGER_NAME: String = "com.bluemessenger.BlueMessenger"
    const val TAG_PREFIX: String = "["
    const val TAG_SUFFIX: String = "] "
    const val CONTEXT_SEPARATOR: String = " | "
    const val CONTEXT_ENTRY_DELIMITER: String = ", "
    const val CONTEXT_KEY_VALUE_SEPARATOR: String = "="
}

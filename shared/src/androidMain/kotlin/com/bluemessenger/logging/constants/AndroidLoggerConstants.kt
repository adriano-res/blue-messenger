package com.bluemessenger.logging.constants

import android.util.Log

/**
 * Logcat level and structured context formatting constants for
 * [com.bluemessenger.logging.AndroidAppLoggerImpl].
 */
internal object AndroidLoggerConstants {
    const val LEVEL_DEBUG: Int = Log.DEBUG
    const val LEVEL_INFO: Int = Log.INFO
    const val LEVEL_WARN: Int = Log.WARN
    const val LEVEL_ERROR: Int = Log.ERROR

    const val CONTEXT_SEPARATOR: String = " | "
    const val CONTEXT_ENTRY_DELIMITER: String = ", "
    const val CONTEXT_KEY_VALUE_SEPARATOR: String = "="
}

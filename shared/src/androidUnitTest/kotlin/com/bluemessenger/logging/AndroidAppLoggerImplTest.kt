package com.bluemessenger.logging

import android.util.Log
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class AndroidAppLoggerImplTest {
    @Test
    fun mapsDebugAndInfoLevelsAndFormatsSortedContextDeterministically() {
        val recorded = mutableListOf<RecordedLog>()
        val logger =
            AndroidAppLoggerImpl { level, tag, message, throwable ->
                recorded.add(RecordedLog(level, tag, message, throwable))
            }

        logger.debug(
            tag = "Scanner",
            message = "Discovery started",
            context = mapOf("zeta" to "last", "alpha" to "first"),
        )
        logger.info(
            tag = "Transport",
            message = "Channel ready",
        )

        assertEquals(2, recorded.size)

        val debugEntry = recorded[0]
        assertEquals(Log.DEBUG, debugEntry.level)
        assertEquals("Scanner", debugEntry.tag)
        assertEquals("Discovery started | alpha=first, zeta=last", debugEntry.message)
        assertNull(debugEntry.throwable)

        val infoEntry = recorded[1]
        assertEquals(Log.INFO, infoEntry.level)
        assertEquals("Transport", infoEntry.tag)
        assertEquals("Channel ready", infoEntry.message)
        assertNull(infoEntry.throwable)
    }

    @Test
    fun mapsWarnAndErrorLevelsWithThrowableAndSortedContext() {
        val recorded = mutableListOf<RecordedLog>()
        val logger =
            AndroidAppLoggerImpl { level, tag, message, throwable ->
                recorded.add(RecordedLog(level, tag, message, throwable))
            }
        val warnCause = IllegalStateException("Adapter reset")
        val errorCause = IllegalArgumentException("Corrupted stream")

        logger.warn(
            tag = "Rfcomm",
            message = "Frame dropped",
            throwable = warnCause,
            context = mapOf("peerId" to "peer-1", "attempt" to "2"),
        )
        logger.error(
            tag = "KeyStore",
            message = "Vault authentication failed",
            throwable = errorCause,
            context = mapOf("provider" to "AndroidKeyStore"),
        )

        assertEquals(2, recorded.size)

        val warnEntry = recorded[0]
        assertEquals(Log.WARN, warnEntry.level)
        assertEquals("Rfcomm", warnEntry.tag)
        assertEquals("Frame dropped | attempt=2, peerId=peer-1", warnEntry.message)
        assertSame(warnCause, warnEntry.throwable)

        val errorEntry = recorded[1]
        assertEquals(Log.ERROR, errorEntry.level)
        assertEquals("KeyStore", errorEntry.tag)
        assertEquals("Vault authentication failed | provider=AndroidKeyStore", errorEntry.message)
        assertSame(errorCause, errorEntry.throwable)
    }

    private data class RecordedLog(
        val level: Int,
        val tag: String,
        val message: String,
        val throwable: Throwable?,
    )
}

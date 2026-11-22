package com.bluemessenger.logging

import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class JvmAppLoggerImplTest {
    @Test
    fun mapsLogLevelsAndFormatsTagAndSortedContext() {
        val (julLogger, handler) = createTestLogger(Level.ALL)
        val appLogger = JvmAppLoggerImpl(julLogger)

        appLogger.debug(
            tag = "Scanner",
            message = "Discovery started",
            context = mapOf("zeta" to "last", "alpha" to "first"),
        )
        appLogger.info(
            tag = "Transport",
            message = "Channel ready",
        )

        assertEquals(2, handler.records.size)

        val debugRecord = handler.records[0]
        assertEquals(Level.FINE, debugRecord.level)
        assertEquals("[Scanner] Discovery started | alpha=first, zeta=last", debugRecord.message)
        assertNull(debugRecord.thrown)

        val infoRecord = handler.records[1]
        assertEquals(Level.INFO, infoRecord.level)
        assertEquals("[Transport] Channel ready", infoRecord.message)
        assertNull(infoRecord.thrown)
    }

    @Test
    fun mapsWarnAndErrorLevelsWithThrowableAndContext() {
        val (julLogger, handler) = createTestLogger(Level.ALL)
        val appLogger = JvmAppLoggerImpl(julLogger)
        val warnCause = IllegalStateException("Adapter reset")
        val errorCause = IllegalArgumentException("Corrupted stream")

        appLogger.warn(
            tag = "BlueZ",
            message = "Frame dropped",
            throwable = warnCause,
            context = mapOf("peerId" to "peer-1", "attempt" to "2"),
        )
        appLogger.error(
            tag = "KeyStore",
            message = "Secret service unavailable",
            throwable = errorCause,
            context = mapOf("backend" to "org.freedesktop.secrets"),
        )

        assertEquals(2, handler.records.size)

        val warnRecord = handler.records[0]
        assertEquals(Level.WARNING, warnRecord.level)
        assertEquals("[BlueZ] Frame dropped | attempt=2, peerId=peer-1", warnRecord.message)
        assertSame(warnCause, warnRecord.thrown)

        val errorRecord = handler.records[1]
        assertEquals(Level.SEVERE, errorRecord.level)
        assertEquals(
            "[KeyStore] Secret service unavailable | backend=org.freedesktop.secrets",
            errorRecord.message,
        )
        assertSame(errorCause, errorRecord.thrown)
    }

    @Test
    fun respectsUnderlyingJulLoggerLevelFilter() {
        val (julLogger, handler) = createTestLogger(Level.WARNING)
        val appLogger = JvmAppLoggerImpl(julLogger)

        appLogger.debug(tag = "Tag", message = "Debug filtered")
        appLogger.info(tag = "Tag", message = "Info filtered")
        appLogger.warn(tag = "Tag", message = "Warn kept")

        assertEquals(1, handler.records.size)
        assertTrue(handler.records.single().message.contains("Warn kept"))
    }

    private fun createTestLogger(level: Level): Pair<Logger, RecordingHandler> {
        val julLogger =
            Logger.getAnonymousLogger().apply {
                useParentHandlers = false
                this.level = level
            }
        val handler = RecordingHandler().apply { this.level = level }
        julLogger.addHandler(handler)
        return julLogger to handler
    }

    private class RecordingHandler : Handler() {
        val records: MutableList<LogRecord> = mutableListOf()

        override fun publish(record: LogRecord) {
            if (isLoggable(record)) {
                records.add(record)
            }
        }

        override fun flush() = Unit

        override fun close() {
            records.clear()
        }
    }
}

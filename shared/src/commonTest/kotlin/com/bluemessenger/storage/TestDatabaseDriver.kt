package com.bluemessenger.storage

import app.cash.sqldelight.db.SqlDriver

/**
 * Creates an isolated in-memory SQLite driver with foreign keys enforced and the schema applied.
 *
 * Actual implementation: `shared/src/jvmCommonTest/kotlin/com/bluemessenger/storage/TestDatabaseDriver.kt`,
 * wired through the `jvmCommon` intermediate source set in `shared/build.gradle.kts`.
 */
internal expect fun createInMemoryTestDriver(): SqlDriver

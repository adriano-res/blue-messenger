package com.bluemessenger.storage

import app.cash.sqldelight.db.SqlDriver

internal const val DATABASE_NAME: String = "blue_messenger.db"
internal const val SINGLETON_IDENTITY_ROW_ID: Long = 1L
internal const val SQLITE_TRUE: Long = 1L
internal const val SQLITE_FALSE: Long = 0L

/**
 * Platform boundary that creates the SQLite [SqlDriver] backing [BlueMessengerDatabase].
 *
 * Implementations: `AndroidDatabaseDriverFactory` (`shared/src/androidMain`) and
 * `JvmDatabaseDriverFactory` (`shared/src/jvmMain`), wired through `shared/build.gradle.kts`.
 *
 * Contract: returned drivers have foreign key enforcement enabled and an initialized, up-to-date
 * schema. Encryption at rest is intentionally deferred behind this boundary.
 */
interface DatabaseDriverFactory {
    /**
     * Opens a new driver, creating or migrating the schema when required.
     */
    fun createDriver(): SqlDriver
}

/**
 * Creates a [BlueMessengerDatabase] on top of a fresh driver produced by this factory.
 */
fun DatabaseDriverFactory.createDatabase(): BlueMessengerDatabase = BlueMessengerDatabase(createDriver())

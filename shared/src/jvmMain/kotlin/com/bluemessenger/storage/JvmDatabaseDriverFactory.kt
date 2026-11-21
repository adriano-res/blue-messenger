package com.bluemessenger.storage

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.util.Properties

private const val FOREIGN_KEYS_PROPERTY: String = "foreign_keys"
private const val ENABLED_PROPERTY_VALUE: String = "true"
private const val USER_VERSION_QUERY: String = "PRAGMA user_version"
private const val USER_VERSION_ASSIGNMENT_PREFIX: String = "PRAGMA user_version = "
private const val UNINITIALIZED_SCHEMA_VERSION: Long = 0L
private const val JDBC_SQLITE_URL_PREFIX: String = "jdbc:sqlite:"

/**
 * JVM (Linux desktop) [DatabaseDriverFactory] backed by [JdbcSqliteDriver].
 *
 * Creates the schema on first use and applies pending migrations using SQLite `user_version`.
 *
 * @param jdbcUrl a `jdbc:sqlite:` URL, either [JdbcSqliteDriver.IN_MEMORY] or the result of [forFile].
 */
class JvmDatabaseDriverFactory(
    private val jdbcUrl: String,
) : DatabaseDriverFactory {
    override fun createDriver(): SqlDriver {
        val properties = Properties().apply { setProperty(FOREIGN_KEYS_PROPERTY, ENABLED_PROPERTY_VALUE) }
        val driver = JdbcSqliteDriver(jdbcUrl, properties)
        try {
            initializeSchema(driver)
        } catch (failure: IllegalStateException) {
            driver.close()
            throw failure
        }
        return driver
    }

    private fun initializeSchema(driver: SqlDriver) {
        val schema = BlueMessengerDatabase.Schema
        val currentVersion = readUserVersion(driver)
        check(currentVersion <= schema.version) {
            "Database schema version $currentVersion is newer than supported version ${schema.version}."
        }
        when (currentVersion) {
            UNINITIALIZED_SCHEMA_VERSION -> schema.create(driver)
            schema.version -> return
            else -> schema.migrate(driver, currentVersion, schema.version)
        }
        driver.execute(null, "$USER_VERSION_ASSIGNMENT_PREFIX${schema.version}", 0)
    }

    private fun readUserVersion(driver: SqlDriver): Long =
        driver
            .executeQuery(
                identifier = null,
                sql = USER_VERSION_QUERY,
                mapper = { cursor ->
                    check(cursor.next().value) { "SQLite did not return a user_version row." }
                    QueryResult.Value(checkNotNull(cursor.getLong(0)) { "SQLite user_version was null." })
                },
                parameters = 0,
            ).value

    companion object {
        /**
         * Builds the JDBC URL for a database file stored at [path].
         */
        fun forFile(path: String): String = "$JDBC_SQLITE_URL_PREFIX$path"
    }
}

package com.bluemessenger.storage

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.util.Properties

private const val FOREIGN_KEYS_PROPERTY = "foreign_keys"
private const val ENABLED_PROPERTY_VALUE = "true"

internal actual fun createInMemoryTestDriver(): SqlDriver {
    val properties = Properties().apply { setProperty(FOREIGN_KEYS_PROPERTY, ENABLED_PROPERTY_VALUE) }
    val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, properties)
    BlueMessengerDatabase.Schema.create(driver)
    return driver
}

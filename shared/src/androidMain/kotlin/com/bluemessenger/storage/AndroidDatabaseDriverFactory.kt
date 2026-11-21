package com.bluemessenger.storage

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver

/**
 * Android [DatabaseDriverFactory] backed by the platform SQLite framework via [AndroidSqliteDriver].
 */
class AndroidDatabaseDriverFactory(
    context: Context,
) : DatabaseDriverFactory {
    private val applicationContext: Context = context.applicationContext

    override fun createDriver(): SqlDriver =
        AndroidSqliteDriver(
            schema = BlueMessengerDatabase.Schema,
            context = applicationContext,
            name = DATABASE_NAME,
            callback = ForeignKeyEnforcingCallback,
        )

    private object ForeignKeyEnforcingCallback :
        AndroidSqliteDriver.Callback(BlueMessengerDatabase.Schema) {
        override fun onConfigure(db: SupportSQLiteDatabase) {
            db.setForeignKeyConstraintsEnabled(true)
        }
    }
}

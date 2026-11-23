package com.bluemessenger.desktop.constants

/**
 * Window metadata, XDG storage resolution constants, and structured logging tags for the Linux
 * Desktop Compose application entrypoint.
 */
internal object DesktopAppConstants {
    const val APP_TITLE: String = "Blue Messenger"

    const val WINDOW_WIDTH_DP: Int = 1120
    const val WINDOW_HEIGHT_DP: Int = 760

    const val XDG_DATA_HOME_ENV: String = "XDG_DATA_HOME"
    const val USER_HOME_PROPERTY: String = "user.home"
    const val LOCAL_DIR: String = ".local"
    const val SHARE_DIR: String = "share"
    const val APP_DATA_DIR_NAME: String = "blue-messenger"
    const val DATABASE_FILE_NAME: String = "bluemessenger.db"
    const val JDBC_SQLITE_PREFIX: String = "jdbc:sqlite:"

    const val TAG_DESKTOP_MAIN: String = "DesktopMain"
}

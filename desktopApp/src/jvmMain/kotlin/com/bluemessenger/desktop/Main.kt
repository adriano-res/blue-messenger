package com.bluemessenger.desktop

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.bluemessenger.LinuxAppModule
import com.bluemessenger.desktop.constants.DesktopAppConstants
import com.bluemessenger.domain.concurrency.CoroutineDispatchers
import com.bluemessenger.presentation.BlueMessengerApp
import com.bluemessenger.presentation.chat.ChatViewModel
import com.bluemessenger.presentation.discovery.DiscoveryViewModel
import kotlinx.coroutines.Dispatchers
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Entry point for the Linux Compose Desktop application, wiring [LinuxAppModule] to
 * [BlueMessengerApp] with deterministic shutdown cleanup.
 */
fun main() {
    val dispatchers = createAppDispatchers()
    val appModule = LinuxAppModule(jdbcUrl = resolveDatabaseJdbcUrl(), dispatchers = dispatchers)
    val discoveryViewModel = createDiscoveryViewModel(appModule, dispatchers)
    val chatViewModel = createChatViewModel(appModule, dispatchers)
    val released = AtomicBoolean(false)
    val shutdownOnce: () -> Unit = {
        if (released.compareAndSet(false, true)) {
            chatViewModel.clear()
            discoveryViewModel.clear()
            closeAppModule(appModule)
        }
    }

    runDesktopWindow(
        discoveryViewModel = discoveryViewModel,
        chatViewModel = chatViewModel,
        shutdownOnce = shutdownOnce,
    )
}

private fun runDesktopWindow(
    discoveryViewModel: DiscoveryViewModel,
    chatViewModel: ChatViewModel,
    shutdownOnce: () -> Unit,
) {
    try {
        application {
            val windowState =
                rememberWindowState(
                    width = DesktopAppConstants.WINDOW_WIDTH_DP.dp,
                    height = DesktopAppConstants.WINDOW_HEIGHT_DP.dp,
                )
            Window(
                onCloseRequest = {
                    shutdownOnce()
                    exitApplication()
                },
                title = DesktopAppConstants.APP_TITLE,
                state = windowState,
            ) {
                BlueMessengerApp(
                    discoveryViewModel = discoveryViewModel,
                    chatViewModel = chatViewModel,
                )
            }
        }
    } finally {
        shutdownOnce()
    }
}

@Suppress("InjectDispatcher")
private fun createAppDispatchers(): CoroutineDispatchers =
    CoroutineDispatchers(
        main = Dispatchers.Main,
        io = Dispatchers.IO,
        default = Dispatchers.Default,
    )

private fun createDiscoveryViewModel(
    appModule: LinuxAppModule,
    dispatchers: CoroutineDispatchers,
): DiscoveryViewModel =
    DiscoveryViewModel(
        initializeIdentityUseCase = appModule.initializeIdentityUseCase,
        discoverPeersUseCase = appModule.discoverPeersUseCase,
        observeConversationsUseCase = appModule.observeConversationsUseCase,
        dispatchers = dispatchers,
        logger = appModule.logger,
        acceptIncomingSessionsUseCase = appModule.acceptIncomingSessionsUseCase,
    )

private fun createChatViewModel(
    appModule: LinuxAppModule,
    dispatchers: CoroutineDispatchers,
): ChatViewModel =
    ChatViewModel(
        establishSecureSessionUseCase = appModule.establishSecureSessionUseCase,
        observeConversationsUseCase = appModule.observeConversationsUseCase,
        observeMessagesUseCase = appModule.observeMessagesUseCase,
        observeIncomingFramesUseCase = appModule.observeIncomingFramesUseCase,
        sendMessageUseCase = appModule.sendMessageUseCase,
        flushPendingMessagesUseCase = appModule.flushPendingMessagesUseCase,
        manageKeepAliveAndReconnectUseCase = appModule.manageKeepAliveAndReconnectUseCase,
        verifyPeerSafetyNumberUseCase = appModule.verifyPeerSafetyNumberUseCase,
        dispatchers = dispatchers,
        logger = appModule.logger,
        acceptIncomingSessionsUseCase = appModule.acceptIncomingSessionsUseCase,
    )

private fun resolveDatabaseJdbcUrl(): String {
    val xdgDataHome = System.getenv(DesktopAppConstants.XDG_DATA_HOME_ENV)?.takeIf { it.isNotBlank() }
    val baseDir: Path =
        if (xdgDataHome != null) {
            Paths.get(xdgDataHome)
        } else {
            val userHome =
                System.getProperty(DesktopAppConstants.USER_HOME_PROPERTY)?.takeIf { it.isNotBlank() }
                    ?: error("System property ${DesktopAppConstants.USER_HOME_PROPERTY} is unavailable.")
            Paths.get(userHome, DesktopAppConstants.LOCAL_DIR, DesktopAppConstants.SHARE_DIR)
        }
    val appDataDir = baseDir.resolve(DesktopAppConstants.APP_DATA_DIR_NAME)
    Files.createDirectories(appDataDir)
    val dbFile = appDataDir.resolve(DesktopAppConstants.DATABASE_FILE_NAME)
    return "${DesktopAppConstants.JDBC_SQLITE_PREFIX}${dbFile.toAbsolutePath()}"
}

private fun closeAppModule(appModule: LinuxAppModule) {
    try {
        appModule.close()
    } catch (exception: IOException) {
        appModule.logger.warn(
            tag = DesktopAppConstants.TAG_DESKTOP_MAIN,
            message = "Failed to close LinuxAppModule during desktop shutdown.",
            throwable = exception,
        )
    }
}

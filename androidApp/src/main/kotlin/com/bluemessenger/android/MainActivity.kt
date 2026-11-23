package com.bluemessenger.android

import android.bluetooth.BluetoothAdapter
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import com.bluemessenger.AndroidAppModule
import com.bluemessenger.android.constants.AndroidAppConstants
import com.bluemessenger.domain.concurrency.CoroutineDispatchers
import com.bluemessenger.presentation.BlueMessengerApp
import com.bluemessenger.presentation.chat.ChatViewModel
import com.bluemessenger.presentation.discovery.DiscoveryViewModel
import kotlinx.coroutines.Dispatchers
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Primary Android application entrypoint bootstrapping [AndroidAppModule], runtime Bluetooth
 * permission requests, and the shared Compose Multiplatform [BlueMessengerApp] UI.
 *
 * Thread safety: Lifecycle callbacks (`onCreate`, `onDestroy`) execute on the Android Main Thread
 * and [shutdownOnce] guards resource disposal with an [AtomicBoolean] for idempotency.
 *
 * Failure behavior: Logs any [IOException] encountered while closing [AndroidAppModule] on
 * destruction without exposing sensitive data.
 */
class MainActivity : ComponentActivity() {
    private val released: AtomicBoolean = AtomicBoolean(false)

    private var appModule: AndroidAppModule? = null
    private var discoveryViewModel: DiscoveryViewModel? = null
    private var chatViewModel: ChatViewModel? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val discoverableLauncher = registerDiscoverableLauncher()
        val dispatchers = createAppDispatchers()
        val module =
            AndroidAppModule(
                context = applicationContext,
                dispatchers = dispatchers,
                onDiscoverableRequested = { launchDiscoverableRequest(discoverableLauncher) },
            )
        val discovery =
            DiscoveryViewModel(
                initializeIdentityUseCase = module.initializeIdentityUseCase,
                discoverPeersUseCase = module.discoverPeersUseCase,
                observeConversationsUseCase = module.observeConversationsUseCase,
                dispatchers = dispatchers,
                logger = module.logger,
                acceptIncomingSessionsUseCase = module.acceptIncomingSessionsUseCase,
            )
        val chat =
            ChatViewModel(
                establishSecureSessionUseCase = module.establishSecureSessionUseCase,
                observeConversationsUseCase = module.observeConversationsUseCase,
                observeMessagesUseCase = module.observeMessagesUseCase,
                observeIncomingFramesUseCase = module.observeIncomingFramesUseCase,
                sendMessageUseCase = module.sendMessageUseCase,
                flushPendingMessagesUseCase = module.flushPendingMessagesUseCase,
                manageKeepAliveAndReconnectUseCase = module.manageKeepAliveAndReconnectUseCase,
                verifyPeerSafetyNumberUseCase = module.verifyPeerSafetyNumberUseCase,
                dispatchers = dispatchers,
                logger = module.logger,
                acceptIncomingSessionsUseCase = module.acceptIncomingSessionsUseCase,
            )

        appModule = module
        discoveryViewModel = discovery
        chatViewModel = chat

        val permissionRequester =
            BluetoothPermissionRequester(
                activity = this,
                logger = module.logger,
                onPermissionsResult = { discovery.onBluetoothPermissionsUpdated() },
            )
        permissionRequester.requestMissingPermissions()

        setContent {
            BlueMessengerApp(
                discoveryViewModel = discovery,
                chatViewModel = chat,
            )
        }
    }

    private fun registerDiscoverableLauncher(): ActivityResultLauncher<Intent> =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            appModule?.logger?.debug(
                tag = AndroidAppConstants.TAG_MAIN_ACTIVITY,
                message = "Bluetooth discoverable prompt finished.",
                context = mapOf("resultCode" to result.resultCode.toString()),
            )
        }

    private fun launchDiscoverableRequest(launcher: ActivityResultLauncher<Intent>) {
        runOnUiThread {
            val discoverableIntent =
                Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).putExtra(
                    BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION,
                    AndroidAppConstants.DISCOVERABLE_DURATION_SECONDS,
                )
            try {
                launcher.launch(discoverableIntent)
            } catch (exception: ActivityNotFoundException) {
                appModule?.logger?.warn(
                    tag = AndroidAppConstants.TAG_MAIN_ACTIVITY,
                    message = "Bluetooth discoverable prompt is unsupported on this device.",
                    throwable = exception,
                )
            } catch (exception: SecurityException) {
                appModule?.logger?.warn(
                    tag = AndroidAppConstants.TAG_MAIN_ACTIVITY,
                    message = "Missing permission to request Bluetooth discoverability.",
                    throwable = exception,
                )
            }
        }
    }

    override fun onDestroy() {
        shutdownOnce()
        super.onDestroy()
    }

    private fun shutdownOnce() {
        if (!released.compareAndSet(false, true)) {
            return
        }
        chatViewModel?.clear()
        chatViewModel = null
        discoveryViewModel?.clear()
        discoveryViewModel = null
        val module = appModule
        appModule = null
        if (module != null) {
            try {
                module.close()
            } catch (exception: IOException) {
                module.logger.warn(
                    tag = AndroidAppConstants.TAG_MAIN_ACTIVITY,
                    message = "Failed to close AndroidAppModule during activity shutdown.",
                    throwable = exception,
                )
            }
        }
    }

    @Suppress("InjectDispatcher")
    private fun createAppDispatchers(): CoroutineDispatchers =
        CoroutineDispatchers(
            main = Dispatchers.Main,
            io = Dispatchers.IO,
            default = Dispatchers.Default,
        )
}

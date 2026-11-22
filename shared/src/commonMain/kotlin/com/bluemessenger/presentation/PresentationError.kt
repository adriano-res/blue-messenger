package com.bluemessenger.presentation

import com.bluemessenger.domain.error.BluetoothAdapterDisabledError
import com.bluemessenger.domain.error.CorruptedFrameError
import com.bluemessenger.domain.error.DecryptionFailedError
import com.bluemessenger.domain.error.HandshakeTimeoutError
import com.bluemessenger.domain.error.InvalidInputError
import com.bluemessenger.domain.error.InvalidStateTransitionError
import com.bluemessenger.domain.error.MissingBluetoothPermissionError
import com.bluemessenger.domain.error.StorageError
import com.bluemessenger.domain.error.TransportDisconnectedError
import com.bluemessenger.domain.error.toDomainErrorOrNull

/**
 * Structured, UI-friendly error model mapped from domain failures for Compose screens.
 */
sealed interface PresentationError {
    val message: String

    /**
     * Operating system Bluetooth permission is missing and must be granted by the user.
     */
    data class MissingPermission(
        val permission: String,
        override val message: String,
    ) : PresentationError

    /**
     * Device Bluetooth adapter is powered off or unavailable.
     */
    data class AdapterDisabled(
        override val message: String,
    ) : PresentationError

    /**
     * User input failed domain validation rules.
     */
    data class Validation(
        override val message: String,
    ) : PresentationError

    /**
     * General transport, cryptographic, storage, or unexpected failure.
     */
    data class General(
        override val message: String,
    ) : PresentationError
}

private const val DEFAULT_ERROR_MESSAGE: String = "An unexpected error occurred."

/**
 * Maps a domain or platform [Throwable] into a structured [PresentationError].
 */
fun Throwable.toPresentationError(): PresentationError =
    when (val domainError = toDomainErrorOrNull()) {
        is MissingBluetoothPermissionError ->
            PresentationError.MissingPermission(
                permission = domainError.permission,
                message =
                    "Bluetooth permission required (${domainError.permission}). " +
                        "Grant access in system settings and try again.",
            )
        is BluetoothAdapterDisabledError ->
            PresentationError.AdapterDisabled(
                message = "Bluetooth is turned off or unavailable. Enable Bluetooth and retry.",
            )
        is InvalidInputError -> PresentationError.Validation(domainError.message)
        is TransportDisconnectedError,
        is HandshakeTimeoutError,
        is DecryptionFailedError,
        is CorruptedFrameError,
        is InvalidStateTransitionError,
        is StorageError,
        -> PresentationError.General(domainError.message)
        null ->
            PresentationError.General(
                message = message?.takeIf { it.isNotBlank() } ?: DEFAULT_ERROR_MESSAGE,
            )
    }

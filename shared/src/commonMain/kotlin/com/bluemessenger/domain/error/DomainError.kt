package com.bluemessenger.domain.error

/**
 * Typed domain failures that enforce explicit handling across layer boundaries.
 */
sealed interface DomainError {
    val message: String
}

/**
 * AEAD authentication or key derivation failure while decrypting an incoming payload.
 */
data class DecryptionFailedError(
    override val message: String,
) : DomainError

/**
 * Malformed length prefix, invalid protocol version, or CRC32 mismatch on a transport frame.
 */
data class CorruptedFrameError(
    override val message: String,
) : DomainError

/**
 * Required operating system Bluetooth permission was denied or not granted.
 */
data class MissingBluetoothPermissionError(
    val permission: String,
    override val message: String = "Missing required Bluetooth permission: $permission.",
) : DomainError

/**
 * Device Bluetooth adapter is powered off or unavailable.
 */
data class BluetoothAdapterDisabledError(
    override val message: String = "Bluetooth adapter is disabled or unavailable.",
) : DomainError

/**
 * Active Bluetooth RFCOMM/L2CAP link is not connected or dropped mid-operation.
 */
data class TransportDisconnectedError(
    override val message: String = "Bluetooth transport is not connected.",
) : DomainError

/**
 * Rejected state machine transition violating domain lifecycle invariants.
 */
data class InvalidStateTransitionError(
    val entity: String,
    val fromState: String,
    val toState: String,
    override val message: String = "Invalid $entity state transition from $fromState to $toState.",
) : DomainError

/**
 * Exception carrier bridging [DomainError] values with Kotlin's [Result] failure channel.
 */
class DomainException(
    val error: DomainError,
    cause: Throwable? = null,
) : Exception(error.message, cause)

/**
 * Wraps this [DomainError] in a failed [Result] carrying a [DomainException].
 */
fun <T> DomainError.asFailure(cause: Throwable? = null): Result<T> = Result.failure(DomainException(this, cause))

/**
 * Extracts the underlying [DomainError] when a [Throwable] is a [DomainException].
 */
fun Throwable.toDomainErrorOrNull(): DomainError? = (this as? DomainException)?.error

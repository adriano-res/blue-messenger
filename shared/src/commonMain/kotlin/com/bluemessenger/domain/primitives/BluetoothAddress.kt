package com.bluemessenger.domain.primitives

import kotlin.jvm.JvmInline

/**
 * Validated Bluetooth hardware MAC address in canonical colon-separated hexadecimal format.
 */
@JvmInline
value class BluetoothAddress(val value: String) {
    init {
        require(MAC_ADDRESS_REGEX.matches(value)) {
            "Invalid Bluetooth MAC address format: '$value'."
        }
    }

    companion object {
        private val MAC_ADDRESS_REGEX: Regex =
            Regex("^([0-9A-F]{2}:){5}[0-9A-F]{2}$")

        /**
         * Parses and normalizes a raw MAC address string to uppercase canonical format.
         */
        fun parse(raw: String): Result<BluetoothAddress> {
            val normalized = raw.trim().uppercase()
            return if (MAC_ADDRESS_REGEX.matches(normalized)) {
                Result.success(BluetoothAddress(normalized))
            } else {
                Result.failure(IllegalArgumentException("Invalid Bluetooth MAC address format: '$raw'."))
            }
        }
    }
}

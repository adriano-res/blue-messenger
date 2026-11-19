package com.bluemessenger.domain.primitives

import kotlin.jvm.JvmInline

/**
 * Represents a point in time measured in milliseconds since the Unix epoch.
 */
@JvmInline
value class Instant(val epochMillis: Long) : Comparable<Instant> {
    init {
        require(epochMillis >= 0L) {
            "Instant epochMillis must be non-negative, got $epochMillis."
        }
    }

    override fun compareTo(other: Instant): Int = epochMillis.compareTo(other.epochMillis)

    companion object {
        val EPOCH: Instant = Instant(0L)
    }
}

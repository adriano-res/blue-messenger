package com.bluemessenger.domain.concurrency

import kotlinx.coroutines.CoroutineDispatcher

/**
 * Injectable coroutine dispatcher bundle enabling deterministic unit testing.
 */
data class CoroutineDispatchers(
    val main: CoroutineDispatcher,
    val io: CoroutineDispatcher,
    val default: CoroutineDispatcher,
)

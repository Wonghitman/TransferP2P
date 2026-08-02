package com.oneturn.transfer.platform

import kotlin.time.TimeSource

private val processStart = TimeSource.Monotonic.markNow()

/** Monotonic nanoseconds since process start; safe for wasmJs (no System.nanoTime). */
internal fun monotonicNanos(): Long = processStart.elapsedNow().inWholeNanoseconds

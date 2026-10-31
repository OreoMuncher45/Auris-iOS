package com.music.auris.data

import com.music.auris.platform.nanoTime
import kotlin.concurrent.Volatile

/**
 * Milliseconds on a monotonic clock. The phone installs
 * `SystemClock.elapsedRealtime`, which keeps counting through deep sleep; the
 * desktop keeps the JVM's own.
 */
object MonotonicClock {
    private val origin = nanoTime()

    @Volatile
    var nowMs: () -> Long = { (nanoTime() - origin) / 1_000_000L }
}

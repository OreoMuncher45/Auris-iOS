package com.music.auris.ui.components

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The swipe-to-queue gesture must queue the track exactly once.
 *
 * Material 3 asks `confirmValueChange` on every drag frame past the halfway
 * point and again on settle, so the queue call used to fire five or six times
 * for one swipe.
 */
class SwipeCommitLatchTest {
    private val width = 400f

    @Test
    fun firesOnceWhenTheThresholdIsCrossed() {
        val latch = SwipeCommitLatch()
        assertFalse(latch.offer(100f, width))
        assertTrue(latch.offer(200f, width))
    }

    @Test
    fun doesNotFireAgainWhileTheGestureContinues() {
        val latch = SwipeCommitLatch()
        assertTrue(latch.offer(200f, width))
        // Every further frame of the same drag.
        assertFalse(latch.offer(220f, width))
        assertFalse(latch.offer(260f, width))
        assertFalse(latch.offer(400f, width))
    }

    @Test
    fun doesNotFireAgainOnSettle() {
        val latch = SwipeCommitLatch()
        assertTrue(latch.offer(200f, width))
        // The settle frame reports the same value.
        assertFalse(latch.offer(200f, width))
    }

    @Test
    fun firesAgainAfterTheRowReturnsToRest() {
        val latch = SwipeCommitLatch()
        assertTrue(latch.offer(200f, width))
        latch.release()
        assertTrue(latch.offer(200f, width))
    }

    @Test
    fun ignoresShortFlingBelowTheThreshold() {
        val latch = SwipeCommitLatch()
        assertFalse(latch.offer(100f, width))
        assertFalse(latch.offer(150f, width))
        assertFalse(latch.hasFired)
    }

    @Test
    fun ignoresBothDirectionsAlike() {
        val latch = SwipeCommitLatch()
        assertTrue(latch.offer(-200f, width))
        assertFalse(latch.offer(-220f, width))
    }

    @Test
    fun ignoresAnUnmeasuredRow() {
        val latch = SwipeCommitLatch()
        assertFalse(latch.offer(200f, 0f))
        assertFalse(latch.hasFired)
    }
}

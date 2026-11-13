package com.music.auris.ui.components

import kotlin.math.abs

/**
 * One swipe, one queue call.
 *
 * Material 3's `SwipeToDismissBox` asks `confirmValueChange` on *every* drag
 * frame once the finger is past the halfway point, and again when the row
 * settles back. Treating each of those as a fresh gesture queued the same
 * track five or six times for a single swipe.
 *
 * The latch fires once when the drag crosses the commit threshold, and
 * re-arms only when the row has come back to rest — so a second, deliberate
 * swipe queues again, but the frames of one swipe cannot.
 */
internal class SwipeCommitLatch {
    /** True once the current gesture has queued the track. */
    private var fired = false

    /**
     * Offers a drag offset to the latch.
     *
     * @param offset the row's current drag offset, in pixels.
     * @param boxWidth the row's width, in pixels.
     * @param commitAt the fraction of the width that commits the gesture.
     * @return true exactly once per gesture, on the frame the threshold is
     *   first crossed.
     */
    fun offer(offset: Float, boxWidth: Float, commitAt: Float = 0.45f): Boolean {
        if (boxWidth <= 0f) return false
        if (fired) return false
        if (abs(offset) < boxWidth * commitAt) return false
        fired = true
        return true
    }

    /**
     * Ends the gesture: the row is back at rest, so the next swipe may queue.
     */
    fun release() {
        fired = false
    }

    /** Whether the current gesture has already fired. */
    val hasFired: Boolean get() = fired
}

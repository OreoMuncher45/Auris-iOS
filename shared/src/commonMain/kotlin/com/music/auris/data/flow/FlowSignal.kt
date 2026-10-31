package com.music.auris.data.flow

import com.music.auris.data.model.Song

/**
 * The signals the mix learns from: how close the listener is to the end of
 * it, which track a tap left behind, and whether a play was quick.
 *
 * Pure rules, kept out of the controller, the player and the screen so every
 * side agrees on what "skipping" means — and so the rules can be tested
 * without a network or a device.
 */
object FlowSignal {

    /** How near the end of the mix the listener has to get for it to top up. */
    const val GROW_WINDOW = 5

    /** Whether the mix should top up now that [index] of [size] is playing. */
    fun shouldGrow(index: Int, size: Int): Boolean =
        size > 0 && index >= size - GROW_WINDOW

    /**
     * The track a tap skipped over: the one playing now, when it sits earlier
     * in [mix] than [tappedIndex]. Null when the tap was an ordinary move
     * forward in the list, or when nothing was playing.
     */
    fun skippedOver(current: Song?, mix: List<Song>, tappedIndex: Int): Song? {
        val playing = current ?: return null
        val at = mix.indexOfFirst { it.videoId == playing.videoId }
        return if (at in 0 until tappedIndex) playing else null
    }

    /**
     * Whether a play was too short to count as a listen: the track goes down
     * in the mix. A longer play forgives an earlier skip instead.
     */
    fun isQuickSkip(playedMs: Long, durationMs: Long): Boolean =
        playedMs < 30_000 || (durationMs > 0 && playedMs < durationMs * 0.4)
}

package com.music.auris.data.flow

import com.music.auris.data.model.Song
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The mix's learning signals, tested as pure rules.
 *
 * These decide when the mix tops up, which track a tap left behind, and
 * whether a play was long enough to count — the same rules the Android app
 * applies in its Flow screen.
 */
class FlowSignalTest {

    private fun song(id: String) = Song(videoId = id, title = "Track $id", artist = "Artist", thumbnailUrl = null)

    private fun mix(vararg ids: String) = ids.map { song(it) }

    // ---- Growing -----------------------------------------------------------

    @Test
    fun growingStartsInsideTheWindowAtTheEnd() {
        assertFalse(FlowSignal.shouldGrow(0, 20))
        assertFalse(FlowSignal.shouldGrow(14, 20))
        assertTrue(FlowSignal.shouldGrow(15, 20))
        assertTrue(FlowSignal.shouldGrow(19, 20))
    }

    @Test
    fun aShortMixGrowsFromTheStart() {
        assertTrue(FlowSignal.shouldGrow(0, 5))
        assertFalse(FlowSignal.shouldGrow(0, 0))
    }

    // ---- Skipped-over tracks ------------------------------------------------

    @Test
    fun tappingAheadNamesTheTrackLeftBehind() {
        val m = mix("a", "b", "c", "d")
        assertEquals("a", FlowSignal.skippedOver(song("a"), m, 3)?.videoId)
        assertEquals("b", FlowSignal.skippedOver(song("b"), m, 2)?.videoId)
    }

    @Test
    fun aTapBackwardOrToTheSameTrackSkipsNothing() {
        val m = mix("a", "b", "c")
        assertNull(FlowSignal.skippedOver(song("c"), m, 1))
        assertNull(FlowSignal.skippedOver(song("b"), m, 1))
    }

    @Test
    fun evenOneStepAheadLeavesTheCurrentTrackBehind() {
        // Same rule as Android: curIdx in 0 until index, so tapping the very
        // next track still counts the unfinished one as skipped.
        val m = mix("a", "b", "c")
        assertEquals("b", FlowSignal.skippedOver(song("b"), m, 2)?.videoId)
    }

    @Test
    fun nothingPlayingSkipsNothing() {
        assertNull(FlowSignal.skippedOver(null, mix("a", "b"), 1))
    }

    @Test
    fun aTrackOutsideTheMixSkipsNothing() {
        val m = mix("a", "b")
        assertNull(FlowSignal.skippedOver(song("zzz"), m, 1))
    }

    // ---- Quick skips --------------------------------------------------------

    @Test
    fun underThirtySecondsIsAQuickSkip() {
        assertTrue(FlowSignal.isQuickSkip(29_999, 200_000))
        // Either rule alone makes it a skip: 30s is under 40% of a 200s track.
        assertTrue(FlowSignal.isQuickSkip(30_000, 200_000))
        // ...but not of a 60s track, where 40% is 24s.
        assertFalse(FlowSignal.isQuickSkip(30_000, 60_000))
    }

    @Test
    fun underFortyPercentOfTheTrackIsAQuickSkip() {
        assertTrue(FlowSignal.isQuickSkip(79_000, 200_000))
        assertFalse(FlowSignal.isQuickSkip(80_000, 200_000))
    }

    @Test
    fun anUnknownDurationFallsBackToTheThirtySecondRule() {
        assertTrue(FlowSignal.isQuickSkip(10_000, 0))
        assertFalse(FlowSignal.isQuickSkip(60_000, 0))
    }
}

package com.music.auris.playback.audio

import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The simulated surround field, ported from Android's `SimulatedAtmosProcessor`.
 *
 * The three upstream assertions are here, plus the ones the port could have
 * broken: a multichannel buffer must leave its rear channels alone, and a
 * flushed processor must start from silence rather than from the last track's
 * delay line.
 */
class SimulatedAtmosProcessorTest {

    private fun stereo(vararg frames: Pair<Float, Float>): FloatArray {
        val out = FloatArray(frames.size * 2)
        frames.forEachIndexed { i, (l, r) ->
            out[i * 2] = l
            out[i * 2 + 1] = r
        }
        return out
    }

    @Test
    fun `disabled leaves the samples untouched`() {
        val p = SimulatedAtmosProcessor()
        p.configure(48000, 2)
        val s = stereo(0.5f to 0.4f, -0.2f to 0.3f, 0.1f to -0.6f)
        val before = s.copyOf()
        p.process(s, s.size)
        assertTrue(before.contentEquals(s), "a disabled processor must not touch a sample")
    }

    @Test
    fun `enabled changes a stereo buffer`() {
        val p = SimulatedAtmosProcessor()
        p.configure(48000, 2)
        p.enabled = true
        // A strongly centred signal: the mid/side effect has the least to do
        // here, and it must still move the samples.
        val s = stereo(0.3f to 0.3f, 0.7f to 0.7f, -0.4f to -0.4f)
        val before = s.copyOf()
        p.process(s, s.size)
        assertTrue(!s.contentEquals(before), "widening must alter the samples")
    }

    @Test
    fun `enabled widens an off-centre image`() {
        val p = SimulatedAtmosProcessor()
        p.configure(48000, 2)
        p.enabled = true
        // Feed a moving side component and confirm the side-to-mid ratio grows.
        val frames = 64
        val s = FloatArray(frames * 2)
        for (i in 0 until frames) {
            val side = sin(i * 0.3).toFloat() * 0.4f
            s[i * 2] = 0.5f + side
            s[i * 2 + 1] = 0.5f - side
        }
        val sideBefore = (0 until frames).sumOf { kotlin.math.abs((s[it * 2] - s[it * 2 + 1]).toDouble()) }
        p.process(s, s.size)
        val sideAfter = (0 until frames).sumOf { kotlin.math.abs((s[it * 2] - s[it * 2 + 1]).toDouble()) }
        assertTrue(sideAfter > sideBefore, "side energy should grow ($sideBefore -> $sideAfter)")
    }

    @Test
    fun `mono passes through untouched`() {
        val p = SimulatedAtmosProcessor()
        p.configure(48000, 1)
        p.enabled = true
        val s = FloatArray(4) { 0.4f }
        val before = s.copyOf()
        p.process(s, s.size)
        assertTrue(before.contentEquals(s), "a mono buffer has no front pair to widen")
    }

    @Test
    fun `only the front pair is touched`() {
        val p = SimulatedAtmosProcessor()
        p.configure(48000, 4)
        p.enabled = true
        val s = FloatArray(8) { 0.25f }
        val rearBefore = s.copyOfRange(2, 4).toList()
        p.process(s, s.size)
        val rearAfter = s.copyOfRange(2, 4).toList()
        assertEquals(rearBefore, rearAfter, "the rear channels must pass through")
    }

    @Test
    fun `flush drops the delay line`() {
        val p = SimulatedAtmosProcessor()
        p.configure(48000, 2)
        p.enabled = true
        val loud = stereo(0.9f to 0.1f, 0.9f to 0.1f, 0.9f to 0.1f)
        p.process(loud, loud.size)
        p.flush()
        // The crossfeed is fed from the delay line, so after a flush the first
        // block can only carry the widening — no tail of the loud block.
        val quiet = stereo(0.1f to 0.1f, 0.1f to 0.1f, 0.1f to 0.1f)
        p.process(quiet, quiet.size)
        assertTrue(quiet[0] < 0.5f && quiet[1] < 0.5f, "the crossfeed tail must not survive a flush")
    }
}

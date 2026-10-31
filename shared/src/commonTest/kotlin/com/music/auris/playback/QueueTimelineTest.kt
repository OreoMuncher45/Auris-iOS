package com.music.auris.playback

import com.music.auris.data.model.QueueTier
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The queue's list rules, tested as plain list edits.
 *
 * [QueueTimeline.contextAppendIndex] is where a grown context (Flow's endless
 * mix) joins a queue that is already playing.
 */
class QueueTimelineTest {

    private val C = QueueTier.CONTEXT
    private val U = QueueTier.USER_QUEUE
    private val A = QueueTier.AUTOPLAY

    @Test
    fun aGrownContextJoinsAfterTheLastContextTrack() {
        assertEquals(3, QueueTimeline.contextAppendIndex(listOf(C, C, C)))
    }

    @Test
    fun aGrownContextJoinsBeforeTheAutoplayTail() {
        assertEquals(3, QueueTimeline.contextAppendIndex(listOf(C, C, C, A, A)))
    }

    @Test
    fun aGrownContextJoinsAfterTheLastContextEvenWhenUserQueueSitsInBetween() {
        // Layout: context, the listener's queue, trailing context, AutoPlay.
        // The grown tracks join the context block, ahead of the AutoPlay tail.
        assertEquals(5, QueueTimeline.contextAppendIndex(listOf(C, C, U, U, C, A)))
        assertEquals(5, QueueTimeline.contextAppendIndex(listOf(C, C, U, U, C)))
    }

    @Test
    fun aQueueWithNoContextAppendsAtTheEnd() {
        assertEquals(2, QueueTimeline.contextAppendIndex(listOf(U, U)))
        assertEquals(0, QueueTimeline.contextAppendIndex(emptyList()))
    }
}

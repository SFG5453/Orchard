/*
 * Copyright (C) 2026 SFG545
 *
 * This file is part of Orchard.
 *
 * Orchard is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * Orchard is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
 * A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Orchard. If not, see <https://www.gnu.org/licenses/>.
 */

package dev.sfg.orchard.mobile.playback

import dev.sfg.orchard.mobile.model.Track
import org.junit.Assert.assertEquals
import org.junit.Test

class QueueEditorTest {
    private val a = Track("a", "A", "Artist")
    private val b = Track("b", "B", "Artist")
    private val c = Track("c", "C", "Artist")
    private val d = Track("d", "D", "Artist")

    @Test
    fun playNextInsertsAfterCurrentWithoutChangingCurrent() {
        val result = QueueEditor.playNext(listOf(a, b, c), 1, d)

        assertEquals(listOf(a, b, d, c), result.tracks)
        assertEquals(1, result.currentIndex)
    }

    @Test
    fun removingHistoryKeepsSameCurrentTrack() {
        val result = QueueEditor.remove(listOf(a, b, c), 1, 0)

        assertEquals(listOf(b, c), result.tracks)
        assertEquals(0, result.currentIndex)
        assertEquals(b, result.tracks[result.currentIndex])
    }

    @Test
    fun removingCurrentAtEndSelectsPreviousSurvivor() {
        val result = QueueEditor.remove(listOf(a, b), 1, 1)

        assertEquals(listOf(a), result.tracks)
        assertEquals(0, result.currentIndex)
    }

    @Test
    fun reorderFindsCurrentByStableIdentity() {
        val result = QueueEditor.move(listOf(a, b, c, d), 2, 2, 0)

        assertEquals(listOf(c, a, b, d), result.tracks)
        assertEquals(0, result.currentIndex)
        assertEquals(c, result.tracks[result.currentIndex])
    }

    @Test
    fun clearUpcomingPreservesHistoryAndCurrent() {
        val result = QueueEditor.clearUpcoming(listOf(a, b, c, d), 1)

        assertEquals(listOf(a, b), result.tracks)
        assertEquals(1, result.currentIndex)
    }

    @Test
    fun replaceDropsDuplicateAndInvalidTracks() {
        val result = QueueEditor.replaceAndPlay(listOf(a, a.copy(title = "Duplicate"), Track("", "Invalid", ""), b), 9)

        assertEquals(listOf(a, b), result.tracks)
        assertEquals(1, result.currentIndex)
    }

    @Test
    fun removingOnlyTrackProducesEmptySelection() {
        val result = QueueEditor.remove(listOf(a), 0, 0)

        assertEquals(emptyList<Track>(), result.tracks)
        assertEquals(-1, result.currentIndex)
    }

    @Test
    fun shuffleDropsDuplicateAndInvalidTracks() {
        val raw = listOf(a, a.copy(title = "Duplicate"), Track("", "Invalid", ""), b, c, d)
        val shuffled = QueueEditor.shuffle(raw)

        assertEquals(listOf(a, b, c, d).sortedBy { it.id }, shuffled.sortedBy { it.id })
    }

    @Test
    fun shuffleAllSetsCurrentIndexToZero() {
        val result = QueueEditor.shuffleAll(listOf(a, b, c, d), kotlin.random.Random(123))

        assertEquals(0, result.currentIndex)
        assertEquals(listOf(a, b, c, d).sortedBy { it.id }, result.tracks.sortedBy { it.id })
    }

    @Test
    fun shuffleUpcomingPreservesHistoryAndCurrentTrack() {
        val e = Track("e", "E", "Artist")
        val queue = listOf(a, b, c, d, e)
        val result = QueueEditor.shuffleUpcoming(queue, 1, kotlin.random.Random(999))

        assertEquals(1, result.currentIndex)
        assertEquals(listOf(a, b), result.tracks.take(2))
        assertEquals(listOf(c, d, e).sortedBy { it.id }, result.tracks.drop(2).sortedBy { it.id })
    }

    @Test
    fun shuffleQueueTracksCurrentTrackIdentity() {
        val queue = listOf(a, b, c, d)
        val result = QueueEditor.shuffleQueue(queue, 2, kotlin.random.Random(777))

        assertEquals(listOf(a, b, c, d).sortedBy { it.id }, result.tracks.sortedBy { it.id })
        assertEquals(c, result.tracks[result.currentIndex])
    }

    @Test
    fun replaceUpcomingPreservesHistoryAndCurrentTrack() {
        val e = Track("e", "E", "Artist")
        val queue = listOf(a, b, c, d, e)
        val result = QueueEditor.replaceUpcoming(queue, 1, listOf(e, c, d))

        assertEquals(1, result.currentIndex)
        assertEquals(listOf(a, b, e, c, d), result.tracks)
    }

    @Test
    fun restoreOrderPutsShuffledItemsBack() {
        val restored = QueueEditor.restoreOrder(listOf(d, b, c), listOf("a", "b", "c", "d"), Track::id)

        assertEquals(listOf(b, c, d), restored)
    }

    @Test
    fun restoreOrderKeepsItemsAddedWhileShuffledAtTheEnd() {
        val e = Track("e", "E", "Artist")
        val f = Track("f", "F", "Artist")
        val restored = QueueEditor.restoreOrder(listOf(f, c, e, b), listOf("a", "b", "c", "d"), Track::id)

        assertEquals(listOf(b, c, f, e), restored)
    }

    @Test
    fun restoreOrderLeavesAWhollyReplacedQueueAlone() {
        val restored = QueueEditor.restoreOrder(listOf(c, b, d), listOf("x", "y", "z"), Track::id)

        assertEquals(listOf(c, b, d), restored)
    }

    @Test
    fun restoreOrderWithoutARememberedOrderIsANoOp() {
        val restored = QueueEditor.restoreOrder(listOf(c, b, d), emptyList(), Track::id)

        assertEquals(listOf(c, b, d), restored)
    }
}

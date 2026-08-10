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

/**
 * Pure queue operations shared by the UI, service, and unit tests.
 *
 * The current track is preserved by identity across reorder/removal operations.
 * This mirrors the desktop queue invariant and prevents a list edit from
 * silently changing the song Media3 should consider current.
 */
object QueueEditor {
    data class Result(val tracks: List<Track>, val currentIndex: Int)

    fun playNext(queue: List<Track>, currentIndex: Int, track: Track): Result {
        val insertion = (currentIndex + 1).coerceIn(0, queue.size)
        val updated = queue.toMutableList().apply { add(insertion, track) }
        return Result(updated, normalizedIndex(updated, currentIndex))
    }

    fun add(queue: List<Track>, currentIndex: Int, track: Track): Result =
        Result(queue + track, normalizedIndex(queue + track, currentIndex))

    fun remove(queue: List<Track>, currentIndex: Int, index: Int): Result {
        if (index !in queue.indices) return Result(queue, normalizedIndex(queue, currentIndex))
        val updated = queue.toMutableList().apply { removeAt(index) }
        val nextIndex = when {
            updated.isEmpty() -> -1
            index < currentIndex -> currentIndex - 1
            currentIndex >= updated.size -> updated.lastIndex
            else -> currentIndex
        }
        return Result(updated, nextIndex)
    }

    fun move(queue: List<Track>, currentIndex: Int, from: Int, to: Int): Result {
        if (from !in queue.indices || to !in queue.indices || from == to) {
            return Result(queue, normalizedIndex(queue, currentIndex))
        }
        val currentId = queue.getOrNull(currentIndex)?.id
        val updated = queue.toMutableList()
        val moved = updated.removeAt(from)
        updated.add(to, moved)
        return Result(updated, updated.indexOfFirst { it.id == currentId }.coerceAtLeast(0))
    }

    fun clearUpcoming(queue: List<Track>, currentIndex: Int): Result {
        if (currentIndex !in queue.indices) return Result(emptyList(), -1)
        return Result(queue.take(currentIndex + 1), currentIndex)
    }

    fun replaceAndPlay(tracks: List<Track>, startIndex: Int = 0): Result {
        val playable = tracks.distinctBy { it.id }.filter { it.id.isNotBlank() }
        return Result(playable, normalizedIndex(playable, startIndex))
    }

    fun shuffle(tracks: List<Track>, random: kotlin.random.Random = kotlin.random.Random.Default): List<Track> {
        val playable = tracks.distinctBy { it.id }.filter { it.id.isNotBlank() }
        return FisherYates.shuffle(playable, random)
    }

    fun shuffleAll(tracks: List<Track>, random: kotlin.random.Random = kotlin.random.Random.Default): Result {
        val shuffled = shuffle(tracks, random)
        return Result(shuffled, if (shuffled.isEmpty()) -1 else 0)
    }

    fun replaceUpcoming(queue: List<Track>, currentIndex: Int, upcoming: List<Track>): Result {
        if (currentIndex !in queue.indices) return Result(queue, normalizedIndex(queue, currentIndex))
        val head = queue.take(currentIndex + 1)
        val updated = head + upcoming
        return Result(updated, currentIndex)
    }

    fun shuffleUpcoming(
        queue: List<Track>,
        currentIndex: Int,
        random: kotlin.random.Random = kotlin.random.Random.Default,
    ): Result {
        if (currentIndex !in queue.indices) return Result(queue, normalizedIndex(queue, currentIndex))
        val head = queue.take(currentIndex + 1)
        val upcoming = queue.drop(currentIndex + 1)
        val shuffledUpcoming = FisherYates.shuffle(upcoming, random)
        val updated = head + shuffledUpcoming
        return Result(updated, currentIndex)
    }

    /**
     * Puts [upcoming] back into [order], for turning shuffle off. Ids not in [order] (queued or
     * autoplayed since) keep their relative order at the end; a queue replaced wholesale shares no
     * ids and comes back untouched.
     */
    fun <T> restoreOrder(upcoming: List<T>, order: List<String>, id: (T) -> String): List<T> {
        if (order.isEmpty() || upcoming.isEmpty()) return upcoming
        val rank = order.withIndex().associate { (index, value) -> value to index }
        // sortedBy is stable, so unranked items hold the relative order they were appended in.
        return upcoming.sortedBy { rank[id(it)] ?: Int.MAX_VALUE }
    }

    fun shuffleQueue(
        queue: List<Track>,
        currentIndex: Int,
        random: kotlin.random.Random = kotlin.random.Random.Default,
    ): Result {
        if (queue.isEmpty()) return Result(emptyList(), -1)
        val currentId = queue.getOrNull(currentIndex)?.id
        val shuffled = FisherYates.shuffle(queue, random)
        val newIndex = if (currentId != null) {
            shuffled.indexOfFirst { it.id == currentId }.coerceAtLeast(0)
        } else {
            0
        }
        return Result(shuffled, newIndex)
    }

    private fun normalizedIndex(queue: List<Track>, index: Int): Int = when {
        queue.isEmpty() -> -1
        index < 0 -> 0
        else -> index.coerceAtMost(queue.lastIndex)
    }
}

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
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Orchard. If not, see <https://www.gnu.org/licenses/>.
 */

package dev.sfg.orchard.mobile.app

import dev.sfg.orchard.mobile.model.PlaybackSnapshot
import dev.sfg.orchard.mobile.model.Track

internal data class BestMixQueueRequest(
    private val currentTrackId: String?,
    private val upcomingTrackIds: List<String>,
) {
    /**
     * Applies a sorted copy of the captured tail to the latest queue.
     *
     * Autoplay is allowed to append while analysis runs. Requiring the complete tail to remain
     * byte-for-byte identical made Best Mix reliably discard its result when it was used with two
     * or three songs left -- exactly the point where Autoplay refills. A suffix is safe to retain;
     * a changed current item or any edit inside the captured prefix still invalidates the request.
     */
    fun reconcile(snapshot: PlaybackSnapshot, sortedUpcoming: List<Track>): List<Track>? {
        if (snapshot.currentTrack?.id != currentTrackId) return null
        if (sortedUpcoming.map(Track::id).toSet() != upcomingTrackIds.toSet()) return null

        val latest = snapshot.upcoming
        if (latest.size < upcomingTrackIds.size) return null
        if (latest.take(upcomingTrackIds.size).map(Track::id) != upcomingTrackIds) return null

        return sortedUpcoming + latest.drop(upcomingTrackIds.size)
    }

    companion object {
        fun capture(snapshot: PlaybackSnapshot): BestMixQueueRequest = BestMixQueueRequest(
            currentTrackId = snapshot.currentTrack?.id,
            upcomingTrackIds = snapshot.upcoming.map { it.id },
        )
    }
}

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

internal data class BestMixQueueRequest(
    private val currentTrackId: String?,
    private val upcomingTrackIds: List<String>,
) {
    fun matches(snapshot: PlaybackSnapshot): Boolean =
        snapshot.currentTrack?.id == currentTrackId &&
            snapshot.upcoming.map { it.id } == upcomingTrackIds

    companion object {
        fun capture(snapshot: PlaybackSnapshot): BestMixQueueRequest = BestMixQueueRequest(
            currentTrackId = snapshot.currentTrack?.id,
            upcomingTrackIds = snapshot.upcoming.map { it.id },
        )
    }
}

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

package dev.sfg.orchard.mobile.library

import dev.sfg.orchard.mobile.catalog.PlaylistActions
import dev.sfg.orchard.mobile.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Likes for the app and car; they go to Apple Music while its library is the one shown. */
internal class TrackLikes(
    private val library: LibraryRepository,
    private val apple: AppleLibraryRepository,
    private val actions: PlaylistActions,
) {
    fun isLiked(track: Track): Boolean =
        if (apple.active) apple.isLiked(track) else library.library.value.likedTracks.any { it.id == track.id }

    /** Flips the like optimistically; throws after reverting when the provider refuses. */
    suspend fun toggle(track: Track) {
        if (apple.active) {
            val loved = !apple.isLiked(track)
            withContext(Dispatchers.IO) { apple.setLiked(track, loved) }
            return
        }
        val liked = !isLiked(track)
        library.setLiked(track, liked)
        try {
            withContext(Dispatchers.IO) { actions.setLiked(track, liked) }
        } catch (error: Exception) {
            library.setLiked(track, !liked)
            throw error
        }
    }

    fun failureMessage(): String =
        if (apple.active) "Could not update your Apple Music library." else "Could not update liked music."
}

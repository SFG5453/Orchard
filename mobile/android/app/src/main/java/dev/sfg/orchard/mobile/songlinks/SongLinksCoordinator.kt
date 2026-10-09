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

package dev.sfg.orchard.mobile.songlinks

import dev.sfg.orchard.mobile.model.BrowseDetail
import dev.sfg.orchard.mobile.model.Track
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Keeps the share sheet and incoming public links in sync with desktop. */
class SongLinksCoordinator(private val repository: SongLinksRepository) {
    private val mutableShareState = MutableStateFlow<SongShareState?>(null)
    val shareState: StateFlow<SongShareState?> = mutableShareState.asStateFlow()

    fun shareTrack(track: Track) {
        val title = track.title.ifBlank { "Song" }
        val subtitle = track.artist
        val url = repository.trackUrl(track)
        mutableShareState.value = if (url != null) {
            SongShareState.Ready(title, subtitle, track.artworkUrl, track.explicit, url)
        } else {
            SongShareState.Error(title, subtitle, track.artworkUrl, track.explicit,
                "This song has no public YouTube link to share.")
        }
    }

    fun shareCollection(detail: BrowseDetail) {
        val title = detail.title.ifBlank { "Collection" }
        val url = repository.collectionUrl(detail)
        val explicit = detail.explicit || detail.tracks.any { it.explicit }
        mutableShareState.value = if (url != null) {
            SongShareState.Ready(title, detail.subtitle, detail.artworkUrl, explicit, url,
                isCollection = true)
        } else {
            // A browse-only album ID cannot be fixed by adding it to album.link.
            SongShareState.Error(title, detail.subtitle, detail.artworkUrl, explicit,
                "This collection has no public link to share.")
        }
    }

    fun dismissShare() {
        mutableShareState.value = null
    }

    fun resolveLink(rawInput: String): LinkResolution? = when (val target = repository.parseLink(rawInput)) {
        is SongLinkTarget.Browse -> LinkResolution.OpenCollection(target.browseId)
        is SongLinkTarget.Video -> LinkResolution.PlayTrack(
            Track(
                id = target.videoId,
                title = "YouTube Track",
                artist = "",
                artworkUrl = "https://i.ytimg.com/vi/${target.videoId}/hqdefault.jpg",
            ),
        )
        null -> null
    }
}

sealed interface LinkResolution {
    data class PlayTrack(val track: Track) : LinkResolution
    data class OpenCollection(val browseId: String) : LinkResolution
}

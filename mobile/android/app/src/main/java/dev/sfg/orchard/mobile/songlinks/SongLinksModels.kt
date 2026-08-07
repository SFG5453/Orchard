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

import dev.sfg.orchard.mobile.model.Track

/** Platform link representation returned by https://songlinks.sfg545.dev. */
data class PlatformLink(
    val platform: String,
    val label: String,
    val url: String,
    val confidence: Double = 1.0,
    val matchType: String = "direct",
    val source: String = "",
) {
    val isDirect: Boolean get() = matchType == "direct" || matchType == "api"
    val isSearch: Boolean get() = matchType == "search"
}

/** Resolved cross-platform song details and links. */
data class ResolvedSong(
    val id: String,
    val shareUrl: String,
    val title: String,
    val artist: String,
    val album: String = "",
    val isrc: String = "",
    val youtubeVideoId: String = "",
    val durationSeconds: Int = 0,
    val thumbnailUrl: String = "",
    val links: List<PlatformLink> = emptyList(),
)

/** Resolved collection details (album, playlist, artist, podcast) and tracks. */
data class ResolvedCollection(
    val id: String,
    val shareUrl: String,
    val kind: String,
    val title: String,
    val subtitle: String = "",
    val browseId: String = "",
    val thumbnailUrl: String = "",
    val itemCount: String = "",
    val orchardOnly: Boolean = false,
    val tracks: List<Track> = emptyList(),
    val links: List<PlatformLink> = emptyList(),
)

/** State of the active SongLinks share sheet. */
sealed interface SongShareState {
    val title: String
    val subtitle: String
    val artworkUrl: String
    val explicit: Boolean

    data class Loading(
        override val title: String,
        override val subtitle: String = "",
        override val artworkUrl: String = "",
        override val explicit: Boolean = false,
    ) : SongShareState

    data class Ready(
        override val title: String,
        override val subtitle: String = "",
        override val artworkUrl: String = "",
        override val explicit: Boolean = false,
        val shareUrl: String,
        val links: List<PlatformLink> = emptyList(),
        val isCollection: Boolean = false,
    ) : SongShareState

    data class Error(
        override val title: String,
        override val subtitle: String = "",
        override val artworkUrl: String = "",
        override val explicit: Boolean = false,
        val message: String,
        val fallbackShareUrl: String? = null,
    ) : SongShareState
}

/** Parsed target from a SongLinks or YouTube URL / deep link. */
sealed interface SongLinkTarget {
    data class Song(val id: String, val origin: String = "") : SongLinkTarget
    data class Collection(val id: String, val origin: String = "") : SongLinkTarget
    data class Browse(val kind: String, val browseId: String) : SongLinkTarget
    data class Video(val videoId: String) : SongLinkTarget
}

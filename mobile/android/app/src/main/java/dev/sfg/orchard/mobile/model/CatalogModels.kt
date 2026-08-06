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

package dev.sfg.orchard.mobile.model

/** The normalized content kinds understood by every Orchard data source. */
enum class CatalogKind { TRACK, ALBUM, ARTIST, PLAYLIST }

/**
 * A playable song independent of its remote provider.
 *
 * [id] is stable provider identity. [streamUrl] is deliberately absent: stream
 * URLs expire quickly and are resolved by the playback service immediately
 * before Media3 opens the item.
 */
data class Track(
    val id: String,
    val title: String,
    val artist: String,
    val album: String = "",
    val albumId: String = "",
    val artistId: String = "",
    val artworkUrl: String = "",
    val animatedArtworkUrl: String = "",
    val animatedArtworkVerticalUrl: String = "",
    val durationMs: Long = 0,
    val explicit: Boolean = false,
    /**
     * YouTube's own classification: ATV is the album audio, OMV an official music video, UGC a
     * user upload. Blank when the payload omitted it.
     */
    val musicVideoType: String = "",
) {
    /** True for album audio, which is the version a listener expects from an album or search. */
    val isAudioOnly: Boolean
        get() = musicVideoType == MUSIC_VIDEO_TYPE_ATV

    /** True for video-first uploads, which run longer than the album cut and skew lyric timing. */
    val isVideoUpload: Boolean
        get() = musicVideoType == MUSIC_VIDEO_TYPE_OMV || musicVideoType == MUSIC_VIDEO_TYPE_UGC
}

const val MUSIC_VIDEO_TYPE_ATV = "MUSIC_VIDEO_TYPE_ATV"
const val MUSIC_VIDEO_TYPE_OMV = "MUSIC_VIDEO_TYPE_OMV"
const val MUSIC_VIDEO_TYPE_UGC = "MUSIC_VIDEO_TYPE_UGC"

data class Album(
    val id: String,
    val title: String,
    val artist: String,
    val artworkUrl: String = "",
    val year: String = "",
    val tracks: List<Track> = emptyList(),
)

data class Artist(
    val id: String,
    val name: String,
    val artworkUrl: String = "",
    val subtitle: String = "",
)

data class Playlist(
    val id: String,
    val title: String,
    val author: String = "Orchard",
    val artworkUrl: String = "",
    val description: String = "",
    val tracks: List<Track> = emptyList(),
)

/** A heterogeneous catalog item used by home, search, and library shelves. */
sealed interface CatalogItem {
    val stableId: String
    val title: String
    val artworkUrl: String

    data class Song(val track: Track) : CatalogItem {
        override val stableId = track.id
        override val title = track.title
        override val artworkUrl = track.artworkUrl
    }

    data class Record(val album: Album) : CatalogItem {
        override val stableId = album.id
        override val title = album.title
        override val artworkUrl = album.artworkUrl
    }

    data class Performer(val artist: Artist) : CatalogItem {
        override val stableId = artist.id
        override val title = artist.name
        override val artworkUrl = artist.artworkUrl
    }

    data class Collection(val playlist: Playlist) : CatalogItem {
        override val stableId = playlist.id
        override val title = playlist.title
        override val artworkUrl = playlist.artworkUrl
    }
}

data class CatalogSection(
    val id: String,
    val title: String,
    val items: List<CatalogItem>,
    val browseId: String = "",
    val params: String = "",
)

data class BrowseDetail(
    val id: String,
    val kind: CatalogKind,
    val title: String,
    val subtitle: String = "",
    val description: String = "",
    val artworkUrl: String = "",
    val tracks: List<Track> = emptyList(),
    val related: List<CatalogItem> = emptyList(),
    val sections: List<CatalogSection> = emptyList(),
    val artist: String = "",
    val year: String = "",
)

data class SearchResults(
    val tracks: List<Track> = emptyList(),
    val albums: List<Album> = emptyList(),
    val artists: List<Artist> = emptyList(),
    val playlists: List<Playlist> = emptyList(),
) {
    val isEmpty: Boolean
        get() = tracks.isEmpty() && albums.isEmpty() && artists.isEmpty() && playlists.isEmpty()
}

data class LyricLine(
    val text: String,
    val startMs: Long? = null,
    val endMs: Long? = null,
    val words: List<LyricWord> = emptyList(),
    val adlibs: List<LyricWord> = emptyList(),
)

data class LyricWord(
    val text: String,
    val startMs: Long,
    val endMs: Long? = null,
)

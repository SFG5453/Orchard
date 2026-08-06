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

package dev.sfg.orchard.mobile.download

import dev.sfg.orchard.mobile.model.BrowseDetail
import dev.sfg.orchard.mobile.model.CatalogItem
import dev.sfg.orchard.mobile.model.CatalogKind
import dev.sfg.orchard.mobile.model.LibrarySnapshot

/**
 * Builds offline [BrowseDetail] models from downloaded tracks and local library metadata when
 * remote network endpoints (such as music.youtube.com) are unreachable.
 */
object OfflineDetailSynthesizer {

    /**
     * Synthesizes a [BrowseDetail] for the given [id] and optional [seed] using downloaded tracks.
     *
     * For artists, this includes ONLY the tracks that are downloaded.
     */
    fun synthesize(
        id: String,
        seed: CatalogItem?,
        downloadedItems: List<DownloadItem>,
        library: LibrarySnapshot,
    ): BrowseDetail? {
        val completedTracks =
            downloadedItems
                .filter { it.status == DownloadStatus.COMPLETED && it.filePath.isNotBlank() }
                .map { it.track }

        if (completedTracks.isEmpty()) return null

        // 1. Is it a saved playlist?
        val savedPlaylist = library.savedPlaylists.firstOrNull { it.id == id }
        if (savedPlaylist != null) {
            val playlistTrackIds = savedPlaylist.tracks.map { it.id }.toSet()
            val matchedTracks =
                if (playlistTrackIds.isNotEmpty()) {
                    completedTracks.filter { it.id in playlistTrackIds }
                } else {
                    completedTracks
                }
            if (matchedTracks.isNotEmpty()) {
                return BrowseDetail(
                    id = savedPlaylist.id,
                    title = savedPlaylist.title,
                    subtitle =
                        "${matchedTracks.size} downloaded ${if (matchedTracks.size == 1) "song" else "songs"}",
                    artworkUrl =
                        savedPlaylist.artworkUrl.ifBlank {
                            matchedTracks.firstOrNull()?.artworkUrl.orEmpty()
                        },
                    kind = CatalogKind.PLAYLIST,
                    tracks = matchedTracks,
                    description = savedPlaylist.description,
                )
            }
        }

        // 2. Liked songs or generic offline downloads playlist
        if (
            id == "FEmusic_liked_videos" ||
                id == "offline_downloads" ||
                id == "downloads" ||
                id == "local_downloads"
        ) {
            val title = if (id == "FEmusic_liked_videos") "Liked Songs" else "Downloaded Music"
            return BrowseDetail(
                id = id,
                title = title,
                subtitle =
                    "${completedTracks.size} downloaded ${if (completedTracks.size == 1) "song" else "songs"}",
                artworkUrl =
                    completedTracks
                        .firstOrNull { it.artworkUrl.isNotBlank() }
                        ?.artworkUrl
                        .orEmpty(),
                kind = CatalogKind.PLAYLIST,
                tracks = completedTracks,
            )
        }

        // 3. Artist: Matches artist ID, artist name, or Performer seed
        val candidateArtistName =
            when (seed) {
                is CatalogItem.Performer -> seed.artist.name
                else -> library.savedArtists.firstOrNull { it.id == id }?.name
            }

        val artistTracks = completedTracks.filter { track ->
            (track.artistId.isNotBlank() && track.artistId == id) ||
                (candidateArtistName != null &&
                    track.artist.equals(candidateArtistName, ignoreCase = true)) ||
                (track.artist.equals(id, ignoreCase = true))
        }

        if (
            artistTracks.isNotEmpty() ||
                (seed is CatalogItem.Performer && completedTracks.isNotEmpty())
        ) {
            val tracksToShow =
                if (artistTracks.isNotEmpty()) artistTracks
                else {
                    val name = seed?.title.orEmpty()
                    completedTracks
                        .filter { it.artist.contains(name, ignoreCase = true) }
                        .ifEmpty { completedTracks }
                }
            val finalArtistName =
                candidateArtistName ?: tracksToShow.firstOrNull()?.artist ?: seed?.title ?: "Artist"
            val artistArt =
                seed?.artworkUrl?.ifBlank { null }
                    ?: library.savedArtists
                        .firstOrNull { it.name.equals(finalArtistName, ignoreCase = true) }
                        ?.artworkUrl
                    ?: tracksToShow.firstOrNull { it.artworkUrl.isNotBlank() }?.artworkUrl.orEmpty()

            return BrowseDetail(
                id = id,
                title = finalArtistName,
                subtitle =
                    "${tracksToShow.size} downloaded ${if (tracksToShow.size == 1) "song" else "songs"}",
                artworkUrl = artistArt,
                kind = CatalogKind.ARTIST,
                tracks = tracksToShow, // ONLY the songs that are downloaded!
                artist = finalArtistName,
            )
        }

        // 4. Album: Matches album ID, album name, or Record seed
        val savedAlbum = library.savedAlbums.firstOrNull { it.id == id }
        val candidateAlbumTitle =
            savedAlbum?.title ?: (seed as? CatalogItem.Record)?.album?.title ?: seed?.title

        val albumTracks = completedTracks.filter { track ->
            (track.albumId.isNotBlank() && track.albumId == id) ||
                (candidateAlbumTitle != null &&
                    track.album.equals(candidateAlbumTitle, ignoreCase = true))
        }

        if (albumTracks.isNotEmpty()) {
            val finalAlbumTitle = candidateAlbumTitle ?: albumTracks.first().album
            val artistName = savedAlbum?.artist ?: albumTracks.first().artist
            val art =
                savedAlbum?.artworkUrl?.ifBlank { null }
                    ?: seed?.artworkUrl?.ifBlank { null }
                    ?: albumTracks.firstOrNull { it.artworkUrl.isNotBlank() }?.artworkUrl.orEmpty()

            return BrowseDetail(
                id = id,
                title = finalAlbumTitle,
                subtitle = artistName,
                artist = artistName,
                artworkUrl = art,
                kind = CatalogKind.ALBUM,
                tracks = albumTracks, // ONLY the songs that are downloaded
                year = savedAlbum?.year ?: "",
            )
        }

        // 5. Seed fallback: If seed exists, build detail with matching tracks or all downloaded
        // tracks
        if (seed != null) {
            val matchingSeedTracks =
                completedTracks
                    .filter { track ->
                        track.artist.contains(seed.title, ignoreCase = true) ||
                            track.album.contains(seed.title, ignoreCase = true) ||
                            track.title.contains(seed.title, ignoreCase = true)
                    }
                    .ifEmpty { completedTracks }

            val (kind, subtitle, artistName) =
                when (seed) {
                    is CatalogItem.Performer ->
                        Triple(CatalogKind.ARTIST, seed.artist.subtitle, seed.artist.name)
                    is CatalogItem.Record ->
                        Triple(CatalogKind.ALBUM, seed.album.artist, seed.album.artist)
                    is CatalogItem.Collection ->
                        Triple(CatalogKind.PLAYLIST, seed.playlist.author, seed.playlist.author)
                    is CatalogItem.Song ->
                        Triple(CatalogKind.PLAYLIST, seed.track.artist, seed.track.artist)
                }

            return BrowseDetail(
                id = id,
                title = seed.title,
                subtitle = subtitle.ifBlank { "${matchingSeedTracks.size} downloaded songs" },
                artworkUrl =
                    seed.artworkUrl.ifBlank {
                        matchingSeedTracks
                            .firstOrNull { it.artworkUrl.isNotBlank() }
                            ?.artworkUrl
                            .orEmpty()
                    },
                kind = kind,
                tracks = matchingSeedTracks,
                artist = artistName,
            )
        }

        // 6. Generic fallback: Return all downloaded tracks
        return BrowseDetail(
            id = id,
            title = "Downloaded Collection",
            subtitle = "${completedTracks.size} downloaded songs",
            artworkUrl =
                completedTracks.firstOrNull { it.artworkUrl.isNotBlank() }?.artworkUrl.orEmpty(),
            kind = CatalogKind.PLAYLIST,
            tracks = completedTracks,
        )
    }
}

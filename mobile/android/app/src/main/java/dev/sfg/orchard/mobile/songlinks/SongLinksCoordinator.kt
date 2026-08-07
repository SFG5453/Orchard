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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Coordinates sharing operations and deep link resolution for Orchard SongLinks. */
class SongLinksCoordinator(
    private val repository: SongLinksRepository,
    private val scope: CoroutineScope,
) {
    private val mutableShareState = MutableStateFlow<SongShareState?>(null)
    val shareState: StateFlow<SongShareState?> = mutableShareState.asStateFlow()

    fun shareTrack(track: Track, albumContext: String? = null, artistContext: String? = null) {
        val title = track.title.ifBlank { "Song" }
        val subtitle = artistContext?.takeIf(String::isNotBlank) ?: track.artist
        val artwork = track.artworkUrl
        val explicit = track.explicit

        mutableShareState.value = SongShareState.Loading(title = title, subtitle = subtitle, artworkUrl = artwork, explicit = explicit)

        scope.launch {
            try {
                val resolved = repository.resolveTrack(track, albumContext, artistContext)
                mutableShareState.value = SongShareState.Ready(
                    title = resolved.title.ifBlank { title },
                    subtitle = resolved.artist.ifBlank { subtitle },
                    artworkUrl = resolved.thumbnailUrl.ifBlank { artwork },
                    explicit = explicit,
                    shareUrl = resolved.shareUrl,
                    links = resolved.links,
                    isCollection = false,
                )
            } catch (e: Exception) {
                val fallbackUrl = if (track.id.isNotBlank()) "https://songlinks.sfg545.dev/s/${track.id}" else null
                mutableShareState.value = SongShareState.Error(
                    title = title,
                    subtitle = subtitle,
                    artworkUrl = artwork,
                    explicit = explicit,
                    message = e.message ?: "Could not resolve cross-platform song link.",
                    fallbackShareUrl = fallbackUrl,
                )
            }
        }
    }

    fun shareCollection(detail: BrowseDetail) {
        val title = detail.title.ifBlank { "Collection" }
        val subtitle = detail.subtitle
        val artwork = detail.artworkUrl
        val explicit = detail.tracks.any { it.explicit }

        mutableShareState.value = SongShareState.Loading(title = title, subtitle = subtitle, artworkUrl = artwork, explicit = explicit)

        scope.launch {
            try {
                val resolved = repository.resolveCollection(detail)
                mutableShareState.value = SongShareState.Ready(
                    title = resolved.title.ifBlank { title },
                    subtitle = resolved.subtitle.ifBlank { subtitle },
                    artworkUrl = resolved.thumbnailUrl.ifBlank { artwork },
                    explicit = explicit,
                    shareUrl = resolved.shareUrl,
                    links = resolved.links,
                    isCollection = true,
                )
            } catch (e: Exception) {
                val fallbackUrl = if (detail.id.isNotBlank()) "https://songlinks.sfg545.dev/c/${detail.id}" else null
                mutableShareState.value = SongShareState.Error(
                    title = title,
                    subtitle = subtitle,
                    artworkUrl = artwork,
                    explicit = explicit,
                    message = e.message ?: "Could not resolve collection share link.",
                    fallbackShareUrl = fallbackUrl,
                )
            }
        }
    }

    fun dismissShare() {
        mutableShareState.value = null
    }

    suspend fun resolveLink(rawInput: String): LinkResolution? {
        val target = repository.parseLink(rawInput) ?: return null
        return when (target) {
            is SongLinkTarget.Song -> {
                val song = repository.loadSong(target.id, target.origin.ifBlank { SongLinksRepository.DEFAULT_ORIGIN })
                if (song != null && song.youtubeVideoId.isNotBlank()) {
                    LinkResolution.PlayTrack(
                        Track(
                            id = song.youtubeVideoId,
                            title = song.title,
                            artist = song.artist,
                            album = song.album,
                            artworkUrl = song.thumbnailUrl,
                        ),
                    )
                } else null
            }
            is SongLinkTarget.Collection -> {
                val col = repository.loadCollection(target.id, target.origin.ifBlank { SongLinksRepository.DEFAULT_ORIGIN })
                if (col != null && col.browseId.isNotBlank()) {
                    LinkResolution.OpenCollection(col.browseId)
                } else if (col != null && col.tracks.isNotEmpty()) {
                    LinkResolution.PlayCollectionTracks(col.title, col.tracks)
                } else null
            }
            is SongLinkTarget.Browse -> LinkResolution.OpenCollection(target.browseId)
            is SongLinkTarget.Video -> LinkResolution.PlayTrack(
                Track(
                    id = target.videoId,
                    title = "YouTube Track",
                    artist = "",
                    artworkUrl = "https://i.ytimg.com/vi/${target.videoId}/hqdefault.jpg",
                ),
            )
        }
    }
}

sealed interface LinkResolution {
    data class PlayTrack(val track: Track) : LinkResolution
    data class OpenCollection(val browseId: String) : LinkResolution
    data class PlayCollectionTracks(val title: String, val tracks: List<Track>) : LinkResolution
}

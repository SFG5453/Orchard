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

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import dev.sfg.orchard.mobile.OrchardGraph
import dev.sfg.orchard.mobile.model.Playlist
import dev.sfg.orchard.mobile.model.Track

/**
 * The browse tree served to Android Auto and any other media browser.
 *
 * Browsers hand back only a media id when the driver taps a row, so every list served here is
 * retained: [queueFor] turns that id back into the list it came from, and a tap plays the whole
 * playlist from that point instead of one orphaned song.
 */
@UnstableApi
class OrchardMediaLibrary(private val graph: OrchardGraph) {
    // Parent id -> the tracks last served for it, and the reverse lookup for expansion. Both are
    // read from the session callback and written from the loader coroutine.
    private val servedLists = java.util.concurrent.ConcurrentHashMap<String, List<Track>>()
    private val parentOfTrack = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun root(): MediaItem = browsable(ROOT, "Orchard")

    suspend fun children(parentId: String): List<MediaItem> = when (parentId) {
        ROOT -> listOf(
            browsable(LIKED, "Liked songs"),
            browsable(PLAYLISTS, "Playlists"),
            browsable(RECENT, "Recently played"),
        )
        PLAYLISTS -> playlists().map { playlist ->
            browsable(playlistId(playlist.id), playlist.title, playlist.author, playlist.artworkUrl)
        }
        LIKED -> tracks(LIKED) {
            graph.library.library.value.likedTracks.ifEmpty {
                runCatching { graph.catalog.likedSongs().tracks }.getOrDefault(emptyList())
            }
        }
        RECENT -> tracks(RECENT) { graph.library.library.value.recentlyPlayed }
        else -> {
            val playlist = parentId.removePrefix(PLAYLIST_PREFIX).takeIf { it != parentId }
            if (playlist.isNullOrBlank()) emptyList()
            else tracks(parentId) { runCatching { graph.catalog.browse(playlist).tracks }.getOrDefault(emptyList()) }
        }
    }

    /**
     * Tracks for a spoken or typed query, retained like any other list so the result can be played
     * as a queue. A blank query is a request for music with no preference.
     */
    suspend fun search(query: String): List<MediaItem> = tracks(searchId(query)) {
        if (query.isBlank()) {
            graph.library.library.value.likedTracks
        } else {
            runCatching { graph.catalog.search(query).tracks }.getOrDefault(emptyList())
        }
    }

    /** Resolves a media id already served through [children]; browsers re-request items by id. */
    fun item(mediaId: String): MediaItem? {
        trackFor(mediaId)?.let { return MediaItemMapper.toMediaItem(it) }
        return when {
            mediaId == ROOT -> root()
            mediaId == LIKED -> browsable(LIKED, "Liked songs")
            mediaId == PLAYLISTS -> browsable(PLAYLISTS, "Playlists")
            mediaId == RECENT -> browsable(RECENT, "Recently played")
            mediaId.startsWith(PLAYLIST_PREFIX) -> {
                val id = mediaId.removePrefix(PLAYLIST_PREFIX)
                graph.library.library.value.savedPlaylists.firstOrNull { it.id == id }
                    ?.let { browsable(mediaId, it.title, it.author, it.artworkUrl) }
            }
            else -> null
        }
    }

    /**
     * The list [mediaId] was served in, paired with its position, so a tapped row starts the whole
     * list. Null when the id is not a track from a browse result.
     */
    fun queueFor(mediaId: String): Pair<List<MediaItem>, Int>? {
        val parent = parentOfTrack[mediaId] ?: return null
        val list = servedLists[parent] ?: return null
        val index = list.indexOfFirst { it.id == mediaId }
        if (index < 0) return null
        return list.map(MediaItemMapper::toMediaItem) to index
    }

    private fun trackFor(mediaId: String): Track? =
        parentOfTrack[mediaId]?.let { servedLists[it] }?.firstOrNull { it.id == mediaId }

    private suspend fun playlists(): List<Playlist> {
        val saved = graph.library.library.value.savedPlaylists
        if (saved.isNotEmpty()) return saved
        // The car may be the first surface opened after a cold start, with nothing cached yet.
        graph.library.refreshSignedInLibrary()
        return graph.library.library.value.savedPlaylists
    }

    private suspend fun tracks(parentId: String, load: suspend () -> List<Track>): List<MediaItem> {
        val loaded = load()
        servedLists[parentId] = loaded
        loaded.forEach { parentOfTrack[it.id] = parentId }
        return loaded.map(MediaItemMapper::toMediaItem)
    }

    private fun browsable(
        id: String,
        title: String,
        subtitle: String = "",
        artworkUrl: String = "",
    ): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(title)
            .setSubtitle(subtitle.takeIf(String::isNotBlank))
            .setArtworkUri(artworkUrl.takeIf(String::isNotBlank)?.let(Uri::parse))
            .setIsBrowsable(true)
            .setIsPlayable(false)
            .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
            .build()
        return MediaItem.Builder().setMediaId(id).setMediaMetadata(metadata).build()
    }

    private fun playlistId(id: String): String = "$PLAYLIST_PREFIX$id"

    private fun searchId(query: String): String = "$SEARCH_PREFIX$query"

    companion object {
        const val ROOT = "orchard://root"
        const val LIKED = "orchard://liked"
        const val PLAYLISTS = "orchard://playlists"
        const val RECENT = "orchard://recent"
        const val PLAYLIST_PREFIX = "orchard://playlist/"
        const val SEARCH_PREFIX = "orchard://search/"

        /**
         * Android Auto reads these off the root to pick its layout: playlists as a grid of covers,
         * songs as a list. Without them every level falls back to the same dense list.
         */
        fun rootExtras(): Bundle = Bundle().apply {
            putInt(CONTENT_STYLE_BROWSABLE_HINT, CONTENT_STYLE_GRID)
            putInt(CONTENT_STYLE_PLAYABLE_HINT, CONTENT_STYLE_LIST)
            putBoolean(CONTENT_STYLE_SUPPORTED, true)
        }

        private const val CONTENT_STYLE_SUPPORTED = "android.media.browse.CONTENT_STYLE_SUPPORTED"
        private const val CONTENT_STYLE_BROWSABLE_HINT =
            "android.media.browse.CONTENT_STYLE_BROWSABLE_HINT"
        private const val CONTENT_STYLE_PLAYABLE_HINT =
            "android.media.browse.CONTENT_STYLE_PLAYABLE_HINT"
        private const val CONTENT_STYLE_LIST = 1
        private const val CONTENT_STYLE_GRID = 2
    }
}

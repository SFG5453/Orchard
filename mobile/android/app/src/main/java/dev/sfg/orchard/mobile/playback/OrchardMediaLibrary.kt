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
import androidx.media3.session.MediaConstants
import dev.sfg.orchard.mobile.OrchardGraph
import dev.sfg.orchard.mobile.catalog.isAppleMusicId
import dev.sfg.orchard.mobile.library.withHistory
import dev.sfg.orchard.mobile.model.APPLE_MUSIC_SOURCE
import dev.sfg.orchard.mobile.model.LibrarySnapshot
import dev.sfg.orchard.mobile.model.Playlist
import dev.sfg.orchard.mobile.model.SearchResults
import dev.sfg.orchard.mobile.model.subtitle
import dev.sfg.orchard.mobile.model.Track
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The browse tree served to Android Auto and any other media browser.
 *
 * Browsers hand back only a media id when the driver taps a row, so every list served here is
 * retained: [queueFor] turns that id back into the list it came from, and a tap plays the whole
 * playlist from that point instead of one orphaned song.
 *
 * While Apple Music is the music source the tree serves the Apple library, as the Library tab does.
 */
@UnstableApi
class OrchardMediaLibrary(private val graph: OrchardGraph) {
    // Parent id -> the tracks last served for it, and the reverse lookup for expansion. Both are
    // read from the session callback and written from the loader coroutine.
    private val servedLists = java.util.concurrent.ConcurrentHashMap<String, List<Track>>()
    private val parentOfTrack = java.util.concurrent.ConcurrentHashMap<String, String>()
    // Album and playlist folders from search, which browsers may re-request by id.
    private val servedFolders = java.util.concurrent.ConcurrentHashMap<String, MediaItem>()

    fun root(): MediaItem = browsable(ROOT, "Orchard")

    suspend fun children(parentId: String): List<MediaItem> {
        if (parentId in LIBRARY_LISTS && appleSource() && !graph.appleLibrary.active) {
            throw SignInRequired("Connect Apple Music in Orchard to see your library.")
        }
        return listing(parentId)
    }

    private suspend fun listing(parentId: String): List<MediaItem> = when (parentId) {
        ROOT -> listOf(
            browsable(LIKED, "Liked songs"),
            browsable(PLAYLISTS, "Playlists"),
            browsable(RECENT, "Recently played"),
        )
        PLAYLISTS -> playlists().map { playlist ->
            browsable(playlistId(playlist.id), playlist.title, playlist.subtitle, playlist.artworkUrl)
        }
        LIKED -> tracks(LIKED) {
            val snapshot = snapshot()
            snapshot.likedTracks.ifEmpty {
                if (appleSource()) emptyList()
                else runCatching { graph.catalog.likedSongs().tracks }.getOrDefault(emptyList())
            }
        }
        RECENT -> tracks(RECENT) { snapshot().recentlyPlayed }
        else -> {
            val playlist = parentId.removePrefix(PLAYLIST_PREFIX).takeIf { it != parentId }
            if (playlist.isNullOrBlank()) emptyList()
            else tracks(parentId) { runCatching { browse(playlist).tracks }.getOrDefault(emptyList()) }
        }
    }

    /**
     * Tracks for a spoken or typed query, retained like any other list so the result can be played
     * as a queue. A blank query is a request for music with no preference.
     */
    suspend fun search(query: String): List<MediaItem> = tracks(searchId(query)) {
        if (query.isBlank()) {
            snapshot().likedTracks
        } else {
            runCatching { graph.catalogRouter.search(query).tracks }.getOrDefault(emptyList())
        }
    }

    /** Typed car search: songs, then albums and playlists as folders, each under its own heading. */
    suspend fun searchResults(query: String): List<MediaItem> {
        val results = runCatching { graph.catalogRouter.search(query) }.getOrDefault(SearchResults())
        val songs = tracks(searchId(query)) { results.tracks }.map { it.inGroup("Songs") }
        val albums = results.albums.map {
            browsable(playlistId(it.id), it.title, it.artist, it.artworkUrl, MediaMetadata.MEDIA_TYPE_ALBUM).inGroup("Albums")
        }
        val playlists = results.playlists.map {
            browsable(playlistId(it.id), it.title, it.subtitle, it.artworkUrl, MediaMetadata.MEDIA_TYPE_PLAYLIST)
                .inGroup("Playlists")
        }
        (albums + playlists).forEach { servedFolders[it.mediaId] = it }
        return songs + albums + playlists
    }

    /** Resolves a media id already served through [children]; browsers re-request items by id. */
    fun item(mediaId: String): MediaItem? {
        trackFor(mediaId)?.let { return MediaItemMapper.toMediaItem(it) }
        servedFolders[mediaId]?.let { return it }
        return when {
            mediaId == ROOT -> root()
            mediaId == LIKED -> browsable(LIKED, "Liked songs")
            mediaId == PLAYLISTS -> browsable(PLAYLISTS, "Playlists")
            mediaId == RECENT -> browsable(RECENT, "Recently played")
            mediaId.startsWith(PLAYLIST_PREFIX) -> {
                val id = mediaId.removePrefix(PLAYLIST_PREFIX)
                shownLibrary().savedPlaylists.firstOrNull { it.id == id }
                    ?.let { browsable(mediaId, it.title, it.subtitle, it.artworkUrl) }
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
        if (appleSource()) return snapshot().savedPlaylists
        val saved = graph.library.library.value.savedPlaylists
        if (saved.isNotEmpty()) return saved
        // The car may be the first surface opened after a cold start, with nothing cached yet.
        graph.library.refreshSignedInLibrary()
        return graph.library.library.value.savedPlaylists
    }

    private suspend fun browse(id: String) = if (isAppleMusicId(id)) graph.catalogRouter.browse(id) else graph.catalog.browse(id)

    private suspend fun appleSource(): Boolean = graph.settings.loadedSettings.first().homeSource == APPLE_MUSIC_SOURCE

    /** The library the Library tab shows; never the YouTube Music one while Apple Music is the source. */
    private suspend fun snapshot(): LibrarySnapshot {
        val own = graph.library.library.value
        if (!appleSource()) return own
        // The car may connect before the Apple library finishes its first load.
        val apple = if (!graph.appleLibrary.active) null
            else withTimeoutOrNull(APPLE_LOAD_TIMEOUT_MS) { graph.appleLibrary.library.filterNotNull().first() }
        return (apple ?: LibrarySnapshot()).withHistory(own)
    }

    /** The library as shown right now, without waiting for a load. */
    fun shownLibrary(): LibrarySnapshot =
        if (graph.settings.settings.value.homeSource != APPLE_MUSIC_SOURCE) graph.library.library.value
        else (graph.appleLibrary.library.value ?: LibrarySnapshot()).withHistory(graph.library.library.value)

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
        type: Int = MediaMetadata.MEDIA_TYPE_FOLDER_MIXED,
    ): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(title)
            .setSubtitle(subtitle.takeIf(String::isNotBlank))
            .setArtworkUri(artworkUrl.takeIf(String::isNotBlank)?.let(Uri::parse))
            .setIsBrowsable(true)
            .setIsPlayable(false)
            .setMediaType(type)
            .build()
        return MediaItem.Builder().setMediaId(id).setMediaMetadata(metadata).build()
    }

    private fun MediaItem.inGroup(title: String): MediaItem {
        val extras = Bundle(mediaMetadata.extras ?: Bundle()).apply {
            putString(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_GROUP_TITLE, title)
        }
        return buildUpon().setMediaMetadata(mediaMetadata.buildUpon().setExtras(extras).build()).build()
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
        const val ROOT_CHILDREN = 3
        private val LIBRARY_LISTS = setOf(LIKED, PLAYLISTS, RECENT)
        private const val APPLE_LOAD_TIMEOUT_MS = 15_000L

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

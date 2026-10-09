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

package dev.sfg.orchard.mobile.catalog

import dev.sfg.orchard.mobile.auth.YouTubeSessionProvider
import dev.sfg.orchard.mobile.model.Artist
import dev.sfg.orchard.mobile.model.BrowseDetail
import dev.sfg.orchard.mobile.model.CatalogItem
import dev.sfg.orchard.mobile.model.CatalogKind
import dev.sfg.orchard.mobile.model.CatalogSection
import dev.sfg.orchard.mobile.model.HomeFeed
import dev.sfg.orchard.mobile.model.SearchResults
import dev.sfg.orchard.mobile.model.Track
import dev.sfg.orchard.mobile.youtube.YouTubeProvider
import dev.sfg.orchard.mobile.youtube.mapObjects
import dev.sfg.orchard.mobile.youtube.providerDetail
import dev.sfg.orchard.mobile.youtube.providerItem
import dev.sfg.orchard.mobile.youtube.providerJson
import dev.sfg.orchard.mobile.youtube.providerSearch
import dev.sfg.orchard.mobile.youtube.providerSections
import dev.sfg.orchard.mobile.youtube.providerTrack
import dev.sfg.orchard.mobile.youtube.text
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.last
import org.json.JSONObject

/** The desktop YouTube provider's catalog, mapped onto app models for state holders. */
class CatalogRepository(
    private val provider: YouTubeProvider,
    private val sessions: YouTubeSessionProvider,
) {
    private fun payload() = JSONObject().put("session", sessions.session().providerJson())

    suspend fun home(): HomeFeed {
        val result = provider.invoke("catalog.home", payload())
        return HomeFeed(
            sections = result.optJSONArray("sections").providerSections("home"),
            artists = result.optJSONArray("artists").mapObjects { it.providerItem() }
                .filterIsInstance<CatalogItem.Performer>().map { it.artist },
        )
    }

    /** [filter] is one of all, songs, videos, albums, artists or playlists. */
    suspend fun search(query: String, filter: String = "all"): SearchResults {
        if (query.isBlank()) return SearchResults()
        return provider.invoke("catalog.search", payload().put("query", query.trim()).put("filter", filter))
            .providerSearch()
    }

    suspend fun browse(id: String): BrowseDetail = browsePages(id).last()

    /** A collection as it fills in: the first page at once, then a fuller copy per continuation. */
    fun browsePages(id: String): Flow<BrowseDetail> = flow {
        val browseId = id.substringBefore(":")
        val params = id.substringAfter(":", "")
        when (val kind = kindOf(browseId)) {
            CatalogKind.ALBUM, CatalogKind.ARTIST -> {
                val method = if (kind == CatalogKind.ALBUM) "catalog.album" else "catalog.artist"
                emit(provider.invoke(method, payload().put("browseId", browseId)).providerDetail(id, kind))
            }
            CatalogKind.PLAYLIST -> if (params.isEmpty() && isPlaylist(browseId)) {
                emitAll(playlistPages(id, browseId))
            } else {
                val page = provider.invoke("catalog.browse", payload().put("browseId", browseId).put("params", params))
                emit(page.providerDetail(id, CatalogKind.PLAYLIST))
            }
            CatalogKind.TRACK -> error("Tracks are not browsable")
        }
    }

    private fun playlistPages(id: String, browseId: String): Flow<BrowseDetail> = flow {
        val first = provider.invoke("catalog.playlist", payload().put("browseId", browseId))
        val detail = first.providerDetail(id, CatalogKind.PLAYLIST)
        emit(detail)
        val tracks = detail.tracks.toMutableList()
        var continuation = first.text("continuation")
        var pages = 0
        val budget = pageBudget(browseId)
        while (continuation.isNotEmpty() && pages < budget) {
            // A failed page is not worth losing the rows already shown.
            val page = runCatching {
                provider.invoke("catalog.playlist.more", payload()
                    .put("continuation", continuation).put("startIndex", tracks.size))
            }.getOrNull() ?: break
            page.optJSONArray("tracks").mapObjects { it.providerTrack() }
                .mapTo(tracks) { if (it.artworkUrl.isEmpty()) it.copy(artworkUrl = detail.artworkUrl) else it }
            emit(detail.copy(tracks = tracks.toList()))
            val next = page.text("continuation")
            if (next == continuation) break
            continuation = next
            pages++
        }
    }

    /** Radio continuation for a seed track, used to keep the queue from running dry. */
    suspend fun upNext(videoId: String): List<Track> {
        if (videoId.isBlank()) return emptyList()
        return provider.invokeArray("catalog.upNext", payload().put("videoId", videoId))
            .mapObjects { it.providerTrack() }
    }

    /** Repairs artist credits missing from old or restored queue entries. */
    suspend fun trackArtists(videoId: String): List<Artist> {
        if (videoId.isBlank()) return emptyList()
        return provider.invoke("catalog.track", payload().put("videoId", videoId)).providerTrack()?.artists.orEmpty()
    }

    suspend fun setArtistSubscription(channelId: String, subscribed: Boolean) {
        provider.invoke("library.artist.subscribe", payload().put("channelId", channelId).put("subscribed", subscribed))
    }

    suspend fun likedSongs(): BrowseDetail = browse(LIKED_MUSIC)

    /** Every item behind a shelf's "more" link, continuations included. */
    suspend fun sectionItems(browseId: String, params: String = ""): List<CatalogItem> {
        if (browseId.isBlank()) return emptyList()
        return provider.invoke("catalog.browse", payload().put("browseId", browseId).put("params", params))
            .optJSONArray("sections").providerSections(browseId)
            .flatMap(CatalogSection::items)
            .distinctBy(CatalogItem::stableId)
    }

    /** Saved artists, albums, songs and playlists, keyed like desktop's library view. */
    suspend fun library(): List<CatalogSection> =
        provider.invoke("catalog.library", payload()).optJSONArray("sections").mapObjects { section ->
            val key = section.text("key")
            CatalogSection(
                id = "library-$key",
                title = key.replaceFirstChar(Char::titlecase),
                items = section.optJSONArray("items").mapObjects { it.providerItem() },
            )
        }

    /** The listener's saved and created playlists. */
    suspend fun playlists(): List<CatalogItem> =
        provider.invokeArray("catalog.playlists", payload()).mapObjects { it.providerItem() }

    internal companion object {
        const val LIKED_MUSIC = "VLLM"
        /** 100 rows per page, so this covers playlists up to 5000 tracks. */
        const val MAX_TRACK_PAGES = 50
        /** Mixes never run out of continuations; enough to play for hours. */
        const val MAX_MIX_PAGES = 3

        internal fun pageBudget(id: String): Int =
            if (id.removePrefix("VL").startsWith("RD")) MAX_MIX_PAGES else MAX_TRACK_PAGES

        internal fun kindOf(browseId: String): CatalogKind = when {
            browseId.startsWith("UC") || browseId.startsWith("FEmusic_library_privately_owned_artist") -> CatalogKind.ARTIST
            browseId.startsWith("MPRE") || browseId.startsWith("FEmusic_library_privately_owned_release") -> CatalogKind.ALBUM
            else -> CatalogKind.PLAYLIST
        }

        private fun isPlaylist(browseId: String): Boolean =
            listOf("VL", "PL", "RD", "OLAK", "LM").any(browseId::startsWith)
    }
}

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

import dev.sfg.orchard.mobile.model.BrowseDetail
import dev.sfg.orchard.mobile.model.CatalogKind
import dev.sfg.orchard.mobile.model.CatalogSection
import dev.sfg.orchard.mobile.model.SearchResults
import dev.sfg.orchard.mobile.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.withContext

/** Coroutine-friendly data boundary used by screen state holders. */
class CatalogRepository(private val client: InnerTubeClient) {
    suspend fun home(): List<CatalogSection> = withContext(Dispatchers.IO) {
        val root = client.browse("FEmusic_home")
        val sections = CatalogParser.home(root).toMutableList()
        var token = CatalogParser.homeContinuationToken(root)
        var pages = 1

        while (token.isNotBlank() && pages < MAX_HOME_PAGES) {
            // Home is useful even if a later recommendation page expires or fails.
            val page = runCatching { client.browseContinuation(token) }.getOrNull() ?: break
            sections += CatalogParser.home(page)
            val next = CatalogParser.homeContinuationToken(page)
            if (next == token) break
            token = next
            pages++
        }

        sections.mapIndexed { index, section ->
            section.copy(id = "home-$index-${section.title}")
        }
    }

    suspend fun search(query: String): SearchResults = withContext(Dispatchers.IO) {
        if (query.isBlank()) SearchResults() else CatalogParser.search(client.search(query.trim()))
    }

    suspend fun browse(id: String): BrowseDetail = browsePages(id).last()

    /**
     * The collection as it fills in: the first page as soon as it parses, then a fuller copy after
     * each continuation.
     *
     * Paging used to complete before anything reached the screen, which is fine for a saved
     * playlist that arrives whole but not for an endless mix, where the loop only stops at
     * [MAX_TRACK_PAGES] and the listener waits out every round trip staring at a spinner.
     */
    fun browsePages(id: String): Flow<BrowseDetail> = flow {
        val (browseId, params) = if (id.contains(":")) {
            id.substringBefore(":") to id.substringAfter(":")
        } else {
            id to ""
        }
        val root = if (params.isNotBlank()) client.browsePayload(browseId, params) else client.browse(browseId)
        val detail = CatalogParser.detail(id, root)
        val tracks = detail.tracks.toMutableList()
        emit(detail)

        // Playlists keep duplicate rows (see CatalogParser.collapseDuplicates); everything else
        // pulls one list from several shelves and must not repeat itself across pages.
        val seen = if (detail.kind == CatalogKind.PLAYLIST) null else tracks.mapTo(mutableSetOf(), Track::id)
        val maxPages = pageBudget(id)
        var token = CatalogParser.continuationToken(root)
        var pages = 0
        while (token.isNotBlank() && pages < maxPages) {
            // A failed page is not worth losing the rows already parsed, so stop rather than throw.
            val page = runCatching { client.browseContinuation(token) }.getOrNull() ?: break
            CatalogParser.continuationTracks(detail, page).filterTo(tracks) { seen == null || seen.add(it.id) }
            emit(detail.copy(tracks = tracks.toList()))
            val next = CatalogParser.continuationToken(page)
            if (next == token) break
            token = next
            pages++
        }
    }.flowOn(Dispatchers.IO)


    /** Radio continuation for a seed track, used to keep the queue from running dry. */
    suspend fun upNext(videoId: String): List<Track> = withContext(Dispatchers.IO) {
        if (videoId.isBlank()) emptyList() else CatalogParser.upNext(client.upNext(videoId))
    }

    suspend fun likedSongs(): BrowseDetail = browse("FEmusic_liked_videos")

    suspend fun sectionItems(browseId: String, params: String = ""): List<dev.sfg.orchard.mobile.model.CatalogItem> = withContext(Dispatchers.IO) {
        if (browseId.isBlank()) return@withContext emptyList()
        val root = runCatching { client.browsePayload(browseId, params) }.getOrNull() ?: return@withContext emptyList()
        var items = CatalogParser.sectionItems(root)
        var token = CatalogParser.continuationToken(root)
        var pages = 0
        while (token.isNotBlank() && pages < MAX_SECTION_PAGES) {
            val page = runCatching { client.browseContinuation(token) }.getOrNull() ?: break
            val newItems = CatalogParser.sectionItems(page)
            if (newItems.isEmpty()) break
            items = (items + newItems).distinctBy(dev.sfg.orchard.mobile.model.CatalogItem::stableId)
            val next = CatalogParser.continuationToken(page)
            if (next == token) break
            token = next
            pages++
        }
        items
    }

    suspend fun library(): List<CatalogSection> = withContext(Dispatchers.IO) {
        CatalogParser.home(client.browse("FEmusic_library_landing"))
    }

    internal companion object {
        /** Enough pages to match the long, personalized Music home without making it unbounded. */
        const val MAX_HOME_PAGES = 12
        /** Library grids are 25 items per page; this covers unusually large saved collections. */
        const val MAX_SECTION_PAGES = 40
        /** 100 rows per page, so this covers playlists up to 5000 tracks. */
        const val MAX_TRACK_PAGES = 50
        /**
         * A mix never runs out of continuations, so this is a queue-depth choice rather than a
         * safety limit: enough to play for hours, few enough that the pages stop arriving early.
         */
        const val MAX_MIX_PAGES = 3

        /**
         * How many continuations a collection is worth.
         *
         * Radio and mix ids (`RD…`, optionally behind the `VL` playlist prefix) are the endless
         * ones: "Mixed for you" entries continue forever, so a finite playlist's budget would have
         * them page until the cap every single time they are opened.
         */
        internal fun pageBudget(id: String): Int =
            if (id.removePrefix("VL").startsWith("RD")) MAX_MIX_PAGES else MAX_TRACK_PAGES
    }
}

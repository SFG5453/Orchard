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
import kotlinx.coroutines.withContext

/** Coroutine-friendly data boundary used by screen state holders. */
class CatalogRepository(private val client: InnerTubeClient) {
    suspend fun home(): List<CatalogSection> = withContext(Dispatchers.IO) {
        CatalogParser.home(client.browse("FEmusic_home"))
    }

    suspend fun search(query: String): SearchResults = withContext(Dispatchers.IO) {
        if (query.isBlank()) SearchResults() else CatalogParser.search(client.search(query.trim()))
    }

    suspend fun browse(id: String): BrowseDetail = withContext(Dispatchers.IO) {
        val root = client.browse(id)
        val detail = CatalogParser.detail(id, root)
        val tracks = detail.tracks.toMutableList()
        // Playlists keep duplicate rows (see CatalogParser.collapseDuplicates); everything else
        // pulls one list from several shelves and must not repeat itself across pages.
        val seen = if (detail.kind == CatalogKind.PLAYLIST) null else tracks.mapTo(mutableSetOf(), Track::id)
        var token = CatalogParser.continuationToken(root)
        var pages = 0
        while (token.isNotBlank() && pages < MAX_TRACK_PAGES) {
            // A failed page is not worth losing the rows already parsed, so stop rather than throw.
            val page = runCatching { client.browseContinuation(token) }.getOrNull() ?: break
            CatalogParser.continuationTracks(detail, page).filterTo(tracks) { seen == null || seen.add(it.id) }
            val next = CatalogParser.continuationToken(page)
            if (next == token) break
            token = next
            pages++
        }
        detail.copy(tracks = tracks)
    }

    suspend fun likedSongs(): BrowseDetail = browse("FEmusic_liked_videos")

    suspend fun library(): List<CatalogSection> = withContext(Dispatchers.IO) {
        CatalogParser.home(client.browse("FEmusic_library_landing"))
    }

    private companion object {
        /** 100 rows per page, so this covers playlists up to 5000 tracks. */
        const val MAX_TRACK_PAGES = 50
    }
}

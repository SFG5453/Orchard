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

import dev.sfg.orchard.mobile.applemusic.AppleMusicCatalog
import dev.sfg.orchard.mobile.model.BrowseDetail
import dev.sfg.orchard.mobile.model.CatalogItem
import dev.sfg.orchard.mobile.model.CatalogKind
import dev.sfg.orchard.mobile.model.SearchResults
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.last

internal fun isAppleMusicId(id: String): Boolean = id.startsWith("am.")

/** Home, search and detail pages share the listener's catalog selection. */
internal class CatalogRouter(
    private val youtube: Source,
    private val apple: Source,
    private val preferred: () -> String,
) {
    internal interface Source {
        suspend fun search(query: String): SearchResults
        fun pages(id: String): Flow<BrowseDetail>
        suspend fun sectionItems(id: String, params: String): List<CatalogItem>
        suspend fun artist(name: String): BrowseDetail? = null
    }

    constructor(youtube: CatalogRepository, apple: AppleMusicCatalog, preferred: () -> String) : this(
        object : Source {
            override suspend fun search(query: String) = youtube.search(query)
            override fun pages(id: String) = youtube.browsePages(id)
            override suspend fun sectionItems(id: String, params: String) = youtube.sectionItems(id, params)
        },
        object : Source {
            override suspend fun search(query: String) = apple.search(query)
            override fun pages(id: String) = apple.detailPages(id)
            override suspend fun sectionItems(id: String, params: String) = apple.artistView(id, params)
            override suspend fun artist(name: String) = apple.matchArtist(name)?.let { apple.detail(it) }
        },
        preferred,
    )

    private val appleSelected: Boolean get() = preferred() == "apple_music"

    suspend fun search(query: String): SearchResults =
        (if (appleSelected) apple else youtube).search(query)

    fun pages(id: String, seed: CatalogItem? = null): Flow<BrowseDetail> = flow {
        if (!appleSelected) {
            check(!isAppleMusicId(id)) { "This is from Apple Music. Switch to Apple Music in Settings to open it." }
            emitAll(youtube.pages(id))
        } else if (isAppleMusicId(id)) {
            emitAll(apple.pages(id))
        } else {
            check(CatalogRepository.kindOf(id.substringBefore(":")) == CatalogKind.ARTIST) {
                "This is from YouTube Music. Switch to YouTube Music in Settings to open it."
            }
            val name = (seed as? CatalogItem.Performer)?.artist?.name?.takeIf { it.isNotBlank() }
                ?: youtube.pages(id).first().title
            emit(apple.artist(name) ?: error("Apple Music has no artist named $name."))
        }
    }

    suspend fun browse(id: String): BrowseDetail = pages(id).last()

    suspend fun sectionItems(browseId: String, params: String): List<CatalogItem> {
        check(isAppleMusicId(browseId) == appleSelected) { "Switch catalogs in Settings to open this section." }
        return (if (appleSelected) apple else youtube).sectionItems(browseId, params)
    }
}

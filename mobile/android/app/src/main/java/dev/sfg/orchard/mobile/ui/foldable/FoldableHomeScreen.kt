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


package dev.sfg.orchard.mobile.ui.foldable

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.sfg.orchard.mobile.model.BuiltInHomeSection
import dev.sfg.orchard.mobile.model.CatalogItem
import dev.sfg.orchard.mobile.model.LoadState
import dev.sfg.orchard.mobile.ui.components.HomeSectionShimmer
import dev.sfg.orchard.mobile.ui.screens.HomeData
import dev.sfg.orchard.mobile.ui.screens.HomeSectionSheet
import dev.sfg.orchard.mobile.ui.scroll.OrchardLazyColumn as LazyColumn

internal fun catalogItemSubtitle(item: CatalogItem): String =
    when (item) {
        is CatalogItem.Song -> item.track.artist
        is CatalogItem.Collection -> item.playlist.author
        is CatalogItem.Record -> item.album.artist
        is CatalogItem.Performer -> item.artist.subtitle
        is CatalogItem.Category -> item.title
    }

private const val MAX_PICKS = 6

private fun isLikedMusic(item: CatalogItem): Boolean =
    item is CatalogItem.Collection && item.playlist.id.removePrefix("VL") == "LM"

/** First album or playlist of each shelf; library playlists fill in when the shelves run short. */
private fun topPicks(data: HomeData): List<TopPick> {
    fun lead(items: List<CatalogItem>) =
        items.firstOrNull {
            (it is CatalogItem.Record || it is CatalogItem.Collection) && it.artworkUrl.isNotBlank() && !isLikedMusic(it)
        }
    val picks = data.sections.mapNotNull { section -> lead(section.items)?.let { TopPick(section.title, it) } }
    val library = data.playlists.filter { it.artworkUrl.isNotBlank() && !isLikedMusic(it) }.map { TopPick("Your Playlists", it) }
    return (picks + library).distinctBy { it.item.stableId }.take(MAX_PICKS)
}

// Foldable and tablet Home: title, Top Picks, then shelves laid out by content type.
@Composable
fun FoldableHomeScreen(data: HomeData) {
    HomeSectionSheet(data)

    val actions = data.actions
    val offline = data.offline
    val openCatalogItem: (CatalogItem) -> Unit = data::open
    val picks = remember(data.sections, data.playlists) { topPicks(data) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val available = maxWidth
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = FoldableChromeHeight),
        ) {
            item { FoldableHomeHeader(auth = data.auth, onProfile = actions.openProfile) }

            if (!offline && picks.size >= 2) {
                item {
                    FoldableTopPicks(picks, available, openCatalogItem)
                    Spacer(Modifier.height(36.dp))
                }
            }

            if (offline) {
                item {
                    FoldableOfflineBanner(data.downloadedTracks.size)
                    Spacer(Modifier.height(16.dp))
                }
            }

            if (data.state is LoadState.Loading) {
                item { HomeSectionShimmer(title = "Loading...") }
            }

            data.layout.forEach { config ->
                if (!config.enabled) return@forEach

                when (config.section) {
                    BuiltInHomeSection.YOUR_PLAYLISTS -> {
                        if (offline) return@forEach
                        val items = data.playlists.filter { (it as? CatalogItem.Collection)?.playlist?.trackCount != 0 }
                        if (items.isNotEmpty()) {
                            foldableRail("Your Playlists", items, data.seeAll("Your Playlists", items), openCatalogItem)
                        }
                    }

                    BuiltInHomeSection.SUBSCRIBED_ARTISTS -> {
                        if (offline) return@forEach
                        val items = data.artists
                        if (items.isNotEmpty()) {
                            foldableRail("Keep listening", items, data.seeAll("Keep listening", items), openCatalogItem)
                        }
                    }

                    BuiltInHomeSection.TOP_SONGS -> {
                        if (offline) return@forEach
                        val items = data.topSongs.map { CatalogItem.Song(it) }
                        if (items.isNotEmpty()) {
                            foldableSongShelf("Top Songs", items, available, data.seeAll("Top Songs", items), openCatalogItem)
                        }
                    }

                    BuiltInHomeSection.RECOMMENDATIONS -> {
                        if (offline) return@forEach
                        data.sections.forEach { section ->
                            if (section.items.isEmpty()) return@forEach
                            val seeAll = data.seeAll(section.title, section.items, section.browseId, section.params)
                            val songs = section.items.filterIsInstance<CatalogItem.Song>()
                            if (songs.size == section.items.size) {
                                foldableSongShelf(section.title, songs, available, seeAll, openCatalogItem, sectionKey = section.id)
                            } else {
                                foldableRail(section.title, section.items, seeAll, openCatalogItem, sectionKey = section.id)
                            }
                        }
                    }

                    BuiltInHomeSection.DOWNLOADED_PLAYLISTS -> {
                        val items = data.offlinePlaylists
                        if (items.isNotEmpty()) {
                            foldableRail("Downloaded Playlists", items, data.seeAll("Downloaded Playlists", items), openCatalogItem)
                        }
                    }

                    BuiltInHomeSection.DOWNLOADED_ARTISTS -> {
                        val items = data.offlineArtists
                        if (items.isNotEmpty()) {
                            foldableRail("Downloaded Artists", items, data.seeAll("Downloaded Artists", items), openCatalogItem)
                        }
                    }

                    BuiltInHomeSection.DOWNLOADED_ALBUMS -> {
                        val items = data.offlineAlbums
                        if (items.isNotEmpty()) {
                            foldableRail("Downloaded Albums", items, data.seeAll("Downloaded Albums", items), openCatalogItem)
                        }
                    }

                    BuiltInHomeSection.DOWNLOADED_SONGS -> {
                        if (data.downloadedTracks.isNotEmpty()) {
                            val items = data.downloadedTracks.map { CatalogItem.Song(it) }
                            foldableSongShelf("Downloaded Songs", items, available, data.seeAll("Downloaded Songs", items), openCatalogItem)
                        }
                    }
                }
            }
        }
    }
}

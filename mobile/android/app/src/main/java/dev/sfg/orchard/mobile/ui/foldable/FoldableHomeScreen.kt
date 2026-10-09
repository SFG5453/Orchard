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

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import dev.sfg.orchard.mobile.ui.scroll.OrchardLazyColumn as LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.sfg.orchard.mobile.auth.AuthState
import dev.sfg.orchard.mobile.download.DownloadItem
import dev.sfg.orchard.mobile.download.DownloadStatus
import dev.sfg.orchard.mobile.model.BuiltInHomeSection
import dev.sfg.orchard.mobile.model.CatalogItem
import dev.sfg.orchard.mobile.model.CatalogSection
import dev.sfg.orchard.mobile.model.LibraryFilter
import dev.sfg.orchard.mobile.model.LibrarySnapshot
import dev.sfg.orchard.mobile.model.LoadState
import dev.sfg.orchard.mobile.model.OrchardSettings
import dev.sfg.orchard.mobile.model.Track
import dev.sfg.orchard.mobile.ui.components.CatalogSectionBottomSheet
import dev.sfg.orchard.mobile.ui.components.HomeSectionShimmer

data class FoldableSheetState(
    val title: String,
    val initialItems: List<CatalogItem>,
    val browseId: String = "",
    val params: String = "",
)

internal fun catalogItemSubtitle(item: CatalogItem): String =
    when (item) {
        is CatalogItem.Song -> item.track.artist
        is CatalogItem.Collection -> item.playlist.author
        is CatalogItem.Record -> item.album.artist
        is CatalogItem.Performer -> item.artist.subtitle
        is CatalogItem.Category -> item.title
    }

private inline fun <reified T : CatalogItem> extractItemsOfKind(
    state: LoadState<List<CatalogSection>>
): List<T> {
    if (state !is LoadState.Content) return emptyList()
    return state.value.flatMap { it.items }.filterIsInstance<T>()
}

// Foldable-optimized Home screen interface with full-bleed cinematic hero presentation.
@Composable
fun FoldableHomeScreen(
    settings: OrchardSettings,
    state: LoadState<List<CatalogSection>>,
    library: LibrarySnapshot,
    auth: AuthState = AuthState.SignedOut,
    downloads: List<DownloadItem> = emptyList(),
    downloadedTrackIds: Set<String> = emptySet(),
    isOffline: Boolean = false,
    onRefresh: () -> Unit,
    onSearch: () -> Unit,
    onLibrary: (LibraryFilter) -> Unit,
    onPlay: (Track) -> Unit,
    onOpenDetail: (String) -> Unit,
    onEditLayout: () -> Unit = {},
    onToggleLike: (Track) -> Unit,
    onPlayNext: ((Track) -> Unit)? = null,
    onAddToQueue: ((Track) -> Unit)? = null,
    onAddToPlaylist: ((Track) -> Unit)? = null,
    onShare: ((Track) -> Unit)? = null,
    onOpenProfile: () -> Unit = {},
    onFetchSectionItems: (suspend (String, String) -> List<CatalogItem>)? = null,
    onPlayItem: ((CatalogItem) -> Unit)? = null,
    onPlayCollection: ((String, String) -> Unit)? = null,
) {
    var activeSectionSheet by remember { mutableStateOf<FoldableSheetState?>(null) }
    var selectedCategory by remember { mutableStateOf("All") }
    val categories = remember { listOf("All", "Playlists", "Artists", "Albums", "Songs") }

    val playCatalogItem: (CatalogItem) -> Unit = { item ->
        if (onPlayItem != null) {
            onPlayItem(item)
        } else if (onPlayCollection != null) {
            when (item) {
                is CatalogItem.Song -> onPlay(item.track)
                is CatalogItem.Collection -> onPlayCollection(item.playlist.id, item.title)
                is CatalogItem.Record -> onPlayCollection(item.album.id, item.title)
                is CatalogItem.Performer -> onPlayCollection(item.artist.id, item.title)
                is CatalogItem.Category -> onOpenDetail(item.stableId)
            }
        } else {
            when (item) {
                is CatalogItem.Song -> onPlay(item.track)
                else -> onOpenDetail(item.stableId)
            }
        }
    }

    val openCatalogItem: (CatalogItem) -> Unit = { item ->
        when (item) {
            is CatalogItem.Song -> onPlay(item.track)
            else -> onOpenDetail(item.stableId)
        }
    }

    activeSectionSheet?.let { sheet ->
        CatalogSectionBottomSheet(
            title = sheet.title,
            initialItems = sheet.initialItems,
            browseId = sheet.browseId,
            params = sheet.params,
            onFetchFullItems = onFetchSectionItems,
            onPlay = onPlay,
            onPlayItem = playCatalogItem,
            onOpen = onOpenDetail,
            onDismiss = { activeSectionSheet = null },
        )
    }

    val completedDownloads =
        remember(downloads) {
            downloads.filter { it.status == DownloadStatus.COMPLETED && it.filePath.isNotBlank() }
        }
    val downloadedTracks: List<Track> =
        remember(completedDownloads) { completedDownloads.map { it.track } }
    val effectiveOffline = isOffline || state is LoadState.Error

    // Featured hero items: high-impact showcase items from catalog, playlists, albums, and tracks
    val featuredHeroItems =
        remember(state, library) {
            val fromState =
                if (state is LoadState.Content) {
                    state.value
                        .flatMap { it.items }
                        .filter { it.artworkUrl.isNotBlank() }
                        .distinctBy { it.stableId }
                } else emptyList()
            val fromPlaylists = library.savedPlaylists.map { CatalogItem.Collection(it) }
            val fromAlbums = library.savedAlbums.map { CatalogItem.Record(it) }
            val fromTracks = library.likedTracks.map { CatalogItem.Song(it) }
            (fromState + fromPlaylists + fromAlbums + fromTracks).distinctBy { it.stableId }.take(6)
        }

    fun seeAll(title: String, items: List<CatalogItem>, browseId: String = "", params: String = ""): () -> Unit =
        { activeSectionSheet = FoldableSheetState(title, items, browseId, params) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = FoldableChromeHeight),
    ) {
        // Full Bleed Hero Banner
        if (featuredHeroItems.isNotEmpty()) {
            item {
                FoldableFullBleedHero(
                    items = featuredHeroItems,
                    auth = auth,
                    categories = categories,
                    selectedCategory = selectedCategory,
                    onSelectCategory = {
                        selectedCategory = if (selectedCategory == it) "All" else it
                    },
                    onSearch = onSearch,
                    onProfile = onOpenProfile,
                    onPlay = playCatalogItem,
                    onClick = openCatalogItem,
                )
            }
        } else {
            // Fallback Masthead if library/state is entirely empty
            item {
                FoldableMasthead(
                    auth = auth,
                    categories = categories,
                    selectedCategory = selectedCategory,
                    onSelectCategory = {
                        selectedCategory = if (selectedCategory == it) "All" else it
                    },
                    onSearch = onSearch,
                    onProfile = onOpenProfile,
                )
            }
        }

        // Offline Mode Banner
        if (effectiveOffline) {
            item {
                FoldableOfflineBanner(downloadedTracks.size)
                Spacer(Modifier.height(16.dp))
            }
        }

        // Loading shimmer state
        if (state is LoadState.Loading) {
            item { HomeSectionShimmer(title = "Loading...") }
        }

        // Content Sections filtered dynamically by selected category
        val layoutConfig =
            if (effectiveOffline) settings.homeLayoutOffline else settings.homeLayoutOnline

        layoutConfig.forEach { config ->
            if (!config.enabled) return@forEach

            when (config.section) {
                BuiltInHomeSection.YOUR_PLAYLISTS -> {
                    if (effectiveOffline) return@forEach
                    if (selectedCategory !in listOf("All", "Playlists")) return@forEach
                    val playlistItems =
                        library.savedPlaylists
                            .map { CatalogItem.Collection(it) }
                            .ifEmpty { extractItemsOfKind<CatalogItem.Collection>(state) }
                            .distinctBy { it.stableId }

                    if (playlistItems.isNotEmpty()) {
                        foldableRail("Your Playlists", playlistItems, seeAll("Your Playlists", playlistItems), openCatalogItem)
                    }
                }

                BuiltInHomeSection.SUBSCRIBED_ARTISTS -> {
                    if (effectiveOffline) return@forEach
                    if (selectedCategory !in listOf("All", "Artists")) return@forEach
                    val artistItems =
                        library.savedArtists
                            .map { CatalogItem.Performer(it) }
                            .ifEmpty { extractItemsOfKind<CatalogItem.Performer>(state) }
                            .distinctBy { it.stableId }

                    if (artistItems.isNotEmpty()) {
                        foldableRail("Keep listening", artistItems, seeAll("Keep listening", artistItems), openCatalogItem, cardWidth = 150.dp)
                    }
                }

                BuiltInHomeSection.TOP_SONGS -> {
                    if (effectiveOffline) return@forEach
                    if (selectedCategory !in listOf("All", "Songs")) return@forEach
                    val songItems =
                        library.mostPlayed
                            .ifEmpty { library.likedTracks }
                            .ifEmpty { library.recentlyPlayed }
                            .distinctBy { it.id }
                            .map { CatalogItem.Song(it) }

                    if (songItems.isNotEmpty()) {
                        foldableRail("Top Songs", songItems, seeAll("Top Songs", songItems), openCatalogItem)
                    }
                }

                BuiltInHomeSection.RECOMMENDATIONS -> {
                    if (effectiveOffline) return@forEach
                    if (selectedCategory != "All") return@forEach
                    if (state is LoadState.Content) {
                        state.value.forEach { section ->
                            if (section.items.isNotEmpty()) {
                                foldableRail(
                                    section.title, section.items,
                                    seeAll(section.title, section.items, section.browseId, section.params),
                                    openCatalogItem, sectionKey = section.id,
                                )
                            }
                        }
                    }
                }

                BuiltInHomeSection.DOWNLOADED_PLAYLISTS -> {
                    if (selectedCategory !in listOf("All", "Playlists")) return@forEach
                    val downloadedPlaylists =
                        library.savedPlaylists
                            .filter { playlist ->
                                playlist.tracks.any { it.id in downloadedTrackIds }
                            }
                            .map { CatalogItem.Collection(it) }

                    if (downloadedPlaylists.isNotEmpty()) {
                        foldableRail("Downloaded Playlists", downloadedPlaylists, seeAll("Downloaded Playlists", downloadedPlaylists), openCatalogItem)
                    }
                }

                BuiltInHomeSection.DOWNLOADED_ARTISTS -> {
                    if (selectedCategory !in listOf("All", "Artists")) return@forEach
                    val downloadedArtists =
                        library.savedArtists
                            .filter { artist ->
                                downloadedTracks.any {
                                    it.artist.equals(artist.name, ignoreCase = true)
                                }
                            }
                            .map { CatalogItem.Performer(it) }

                    if (downloadedArtists.isNotEmpty()) {
                        foldableRail("Downloaded Artists", downloadedArtists, seeAll("Downloaded Artists", downloadedArtists), openCatalogItem, cardWidth = 150.dp)
                    }
                }

                BuiltInHomeSection.DOWNLOADED_ALBUMS -> {
                    if (selectedCategory !in listOf("All", "Albums")) return@forEach
                    val downloadedAlbums =
                        library.savedAlbums
                            .filter { album ->
                                downloadedTracks.any {
                                    it.albumId == album.id ||
                                        it.album.equals(album.title, ignoreCase = true)
                                }
                            }
                            .map { CatalogItem.Record(it) }

                    if (downloadedAlbums.isNotEmpty()) {
                        foldableRail("Downloaded Albums", downloadedAlbums, seeAll("Downloaded Albums", downloadedAlbums), openCatalogItem)
                    }
                }

                BuiltInHomeSection.DOWNLOADED_SONGS -> {
                    if (selectedCategory !in listOf("All", "Songs")) return@forEach
                    if (downloadedTracks.isNotEmpty()) {
                        val downloadedSongItems = downloadedTracks.map { CatalogItem.Song(it) }
                        foldableRail("Downloaded Songs", downloadedSongItems, seeAll("Downloaded Songs", downloadedSongItems), openCatalogItem)
                    }
                }
            }
        }
    }
}

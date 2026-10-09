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

package dev.sfg.orchard.mobile.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import dev.sfg.orchard.mobile.ui.scroll.OrchardLazyColumn as LazyColumn
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import dev.sfg.orchard.mobile.auth.AuthState
import dev.sfg.orchard.mobile.download.DownloadItem
import dev.sfg.orchard.mobile.download.DownloadStatus
import dev.sfg.orchard.mobile.model.Artist
import dev.sfg.orchard.mobile.model.BuiltInHomeSection
import dev.sfg.orchard.mobile.model.CatalogItem
import dev.sfg.orchard.mobile.model.CatalogSection
import dev.sfg.orchard.mobile.model.LibraryFilter
import dev.sfg.orchard.mobile.model.LibrarySnapshot
import dev.sfg.orchard.mobile.model.LoadState
import dev.sfg.orchard.mobile.model.OrchardSettings
import dev.sfg.orchard.mobile.model.Playlist
import dev.sfg.orchard.mobile.model.Track
import dev.sfg.orchard.mobile.ui.components.CatalogSectionBottomSheet
import dev.sfg.orchard.mobile.ui.components.HomeSectionShimmer
import dev.sfg.orchard.mobile.ui.components.MessagePanel
import dev.sfg.orchard.mobile.ui.components.OrchardChromeHeight
import dev.sfg.orchard.mobile.ui.theme.CanopyColors

internal data class QuickGridItem(
    val id: String,
    val title: String,
    val artworkUrl: String,
    val icon: ImageVector? = null,
    val gradient: Brush? = null,
    val onClick: () -> Unit,
    val onPlay: () -> Unit,
)

data class HomeSectionSheetState(
    val title: String,
    val initialItems: List<CatalogItem>,
    val browseId: String = "",
    val params: String = "",
)

@Composable
fun HomeScreen(
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
    if (dev.sfg.orchard.mobile.ui.foldable.isFoldableOrWideLayout()) {
        dev.sfg.orchard.mobile.ui.foldable.FoldableHomeScreen(
            settings = settings,
            state = state,
            library = library,
            auth = auth,
            downloads = downloads,
            downloadedTrackIds = downloadedTrackIds,
            isOffline = isOffline,
            onRefresh = onRefresh,
            onSearch = onSearch,
            onLibrary = onLibrary,
            onPlay = onPlay,
            onOpenDetail = onOpenDetail,
            onEditLayout = onEditLayout,
            onToggleLike = onToggleLike,
            onPlayNext = onPlayNext,
            onAddToQueue = onAddToQueue,
            onAddToPlaylist = onAddToPlaylist,
            onShare = onShare,
            onOpenProfile = onOpenProfile,
            onFetchSectionItems = onFetchSectionItems,
            onPlayItem = onPlayItem,
            onPlayCollection = onPlayCollection,
        )
        return
    }

    var activeSectionSheet by remember { mutableStateOf<HomeSectionSheetState?>(null) }

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

    val offlinePlaylistItems = remember(downloadedTracks, downloadedTrackIds, library.savedPlaylists) {
        offlinePlaylistItems(downloadedTracks, downloadedTrackIds, library.savedPlaylists)
    }
    val offlineArtistItems = remember(downloadedTracks, library.savedArtists) {
        offlineArtistItems(downloadedTracks, library.savedArtists)
    }
    val offlineAlbumItems = remember(downloadedTracks) { offlineAlbumItems(downloadedTracks) }
    val spotlight = remember(state, library) { discoverEntries(state, library) }
    val quickGridItems = remember(library, state) {
        quickGridItems(library, state, onOpenDetail, openCatalogItem, playCatalogItem)
    }

    fun seeAll(title: String, items: List<CatalogItem>, browseId: String = "", params: String = ""): () -> Unit =
        { activeSectionSheet = HomeSectionSheetState(title, items, browseId, params) }

    // Card style shared by several rails.
    val collectionCard: @Composable (CatalogItem) -> Unit = { item ->
        GlassRecentlyPlayedCard(item = item, onClick = { openCatalogItem(item) })
    }
    val artistCard: @Composable (CatalogItem) -> Unit = { item ->
        CircularArtistCard(item = item, onClick = { openCatalogItem(item) })
    }
    val rankedRow: @Composable (Int, Track) -> Unit = { index, track ->
        RankedSongRow(
            rank = index + 1,
            track = track,
            liked = library.likedTracks.any { it.id == track.id },
            onPlay = { onPlay(track) },
            onToggleLike = { onToggleLike(track) },
            onPlayNext = onPlayNext,
            onAddToQueue = onAddToQueue,
            onAddToPlaylist = onAddToPlaylist,
            onShare = onShare,
        )
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = OrchardChromeHeight),
        ) {
            // Masthead. Greets you, judges your 3am listening silently.
            item {
                GlassHomeHeader(auth = auth, onSearch = onSearch, onProfile = onOpenProfile)
            }

            if (quickGridItems.isNotEmpty() && !effectiveOffline) {
                item {
                    SpotifyQuickGrid(items = quickGridItems)
                    Spacer(Modifier.height(24.dp))
                }
            }

            if (!effectiveOffline && spotlight.isNotEmpty()) {
                item {
                    DiscoverCarousel(
                        title = "Your daily discover",
                        entries = spotlight,
                        onPlayAll = { playCatalogItem(spotlight.first().item) },
                        onClick = openCatalogItem,
                    )
                }
            }

            if (effectiveOffline) {
                item {
                    OfflineModeBanner(downloadedTracks.size)
                    Spacer(Modifier.height(14.dp))
                }
            }

            val layoutConfig =
                if (effectiveOffline) settings.homeLayoutOffline else settings.homeLayoutOnline

            layoutConfig.forEach { config ->
                if (!config.enabled) return@forEach

                when (config.section) {
                    BuiltInHomeSection.YOUR_PLAYLISTS -> {
                        if (effectiveOffline) return@forEach
                        val playlistItems =
                            library.savedPlaylists
                                .map { CatalogItem.Collection(it) }
                                .ifEmpty { extractItemsOfKind<CatalogItem.Collection>(state) }
                                .distinctBy { it.stableId }
                        if (playlistItems.isNotEmpty()) {
                            val openAll = seeAll("Your Playlists", playlistItems)
                            homeRail(
                                "Your Playlists", playlistItems, "pl", openAll,
                                header = { AccountSectionTitle("Your playlists", auth, openAll) },
                                card = collectionCard,
                            )
                        }
                    }
                    BuiltInHomeSection.SUBSCRIBED_ARTISTS -> {
                        if (effectiveOffline) return@forEach
                        val artistItems =
                            library.savedArtists
                                .map { CatalogItem.Performer(it) }
                                .ifEmpty { extractItemsOfKind<CatalogItem.Performer>(state) }
                                .distinctBy { it.stableId }
                        if (artistItems.isNotEmpty()) {
                            homeRail(
                                "Keep listening", artistItems, "art", seeAll("Keep listening", artistItems),
                                subtitle = "Artists you follow and love", spacing = 16.dp, card = artistCard,
                            )
                        }
                    }
                    BuiltInHomeSection.TOP_SONGS -> {
                        if (effectiveOffline) return@forEach
                        val songTracks =
                            library.mostPlayed
                                .ifEmpty { library.likedTracks }
                                .ifEmpty { library.recentlyPlayed }
                                .ifEmpty {
                                    extractItemsOfKind<CatalogItem.Song>(state).map { it.track }
                                }
                                .distinctBy { it.id }
                        if (songTracks.isNotEmpty()) {
                            homeTrackSection(
                                "Latest Songs", songTracks.take(5), "top",
                                seeAll("Latest Songs", songTracks.map { CatalogItem.Song(it) }),
                                subtitle = "Top picks and recent favorites", row = rankedRow,
                            )
                        }
                    }
                    BuiltInHomeSection.RECOMMENDATIONS -> {
                        if (effectiveOffline) return@forEach
                        when (state) {
                            is LoadState.Content -> {
                                state.value.forEachIndexed { sectionIndex, section ->
                                    homeRail(
                                        section.title, section.items, section.id,
                                        seeAll(section.title, section.items, section.browseId, section.params),
                                        sectionKey = section.id,
                                    ) { item ->
                                        // Alternate card styles so consecutive rails read differently.
                                        if (sectionIndex % 2 == 1) {
                                            GlassSquircleCard(item = item, onClick = { openCatalogItem(item) }, onPlay = { playCatalogItem(item) })
                                        } else {
                                            GlassRecentlyPlayedCard(item = item, onClick = { openCatalogItem(item) })
                                        }
                                    }
                                }
                            }
                            LoadState.Loading -> {
                                item { HomeSectionShimmer("Recommendations") }
                            }
                            is LoadState.Empty -> {
                                item {
                                    MessagePanel("A quiet orchard", state.message, "Refresh", onRefresh)
                                }
                            }
                            else -> Unit
                        }
                    }
                    BuiltInHomeSection.DOWNLOADED_PLAYLISTS -> {
                        if (!effectiveOffline) return@forEach
                        if (offlinePlaylistItems.isNotEmpty()) {
                            homeRail(
                                "Downloaded Playlists", offlinePlaylistItems, "off_pl",
                                seeAll("Downloaded Playlists", offlinePlaylistItems), card = collectionCard,
                            )
                        }
                    }
                    BuiltInHomeSection.DOWNLOADED_ARTISTS -> {
                        if (!effectiveOffline) return@forEach
                        if (offlineArtistItems.isNotEmpty()) {
                            homeRail(
                                "Downloaded Artists", offlineArtistItems, "off_art",
                                seeAll("Downloaded Artists", offlineArtistItems), spacing = 16.dp, card = artistCard,
                            )
                        }
                    }
                    BuiltInHomeSection.DOWNLOADED_ALBUMS -> {
                        if (!effectiveOffline) return@forEach
                        if (offlineAlbumItems.isNotEmpty()) {
                            homeRail(
                                "Downloaded Albums", offlineAlbumItems, "off_alb",
                                seeAll("Downloaded Albums", offlineAlbumItems),
                            ) { item ->
                                GlassRecentlyPlayedCard(item = item, onClick = { openCatalogItem(item) })
                            }
                        }
                    }
                    BuiltInHomeSection.DOWNLOADED_SONGS -> {
                        if (!effectiveOffline) return@forEach
                        if (downloadedTracks.isNotEmpty()) {
                            homeTrackSection(
                                "Downloaded Songs", downloadedTracks, "off_trk",
                                seeAll("Downloaded Songs", downloadedTracks.map { CatalogItem.Song(it) }),
                                row = rankedRow,
                            )
                        } else {
                            item {
                                MessagePanel(
                                    title = "No downloaded music",
                                    message =
                                        "You are currently offline. Connect to the internet to stream music, or download tracks to listen offline.",
                                    actionLabel = "Try reconnecting",
                                    onAction = onRefresh,
                                )
                            }
                        }
                    }
                }
            }

            item {
                Box(
                    Modifier.fillMaxWidth().padding(vertical = 24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    TextButton(onClick = onEditLayout) {
                        Text("Edit home layout", color = CanopyColors.Muted)
                    }
                }
            }
        }
        StatusBarScrim()
    }
}

/** Fades rails out under the status bar so the clock stays legible. */
@Composable
private fun StatusBarScrim() {
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    Box(
        Modifier
            .fillMaxWidth()
            .height(top + 24.dp)
            .background(
                Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = 0.85f),
                    0.6f to Color.Black.copy(alpha = 0.7f),
                    1f to Color.Transparent,
                )
            )
    )
}

private inline fun <reified T : CatalogItem> extractItemsOfKind(
    state: LoadState<List<CatalogSection>>
): List<T> {
    if (state !is LoadState.Content) return emptyList()
    return state.value.flatMap { it.items }.filterIsInstance<T>()
}

internal fun catalogItemBadge(item: CatalogItem): String =
    when (item) {
        is CatalogItem.Song -> "SONG"
        is CatalogItem.Record -> "ALBUM"
        is CatalogItem.Performer -> "ARTIST"
        is CatalogItem.Collection -> {
            if (item.playlist.title.contains("mix", ignoreCase = true) ||
                item.playlist.title.contains("radio", ignoreCase = true)
            ) {
                "MIX"
            } else {
                "PLAYLIST"
            }
        }
        is CatalogItem.Category -> "CATEGORY"
    }

internal fun catalogSubtitle(item: CatalogItem): String =
    when (item) {
        is CatalogItem.Song -> item.track.artist
        is CatalogItem.Record -> item.album.artist
        is CatalogItem.Performer -> item.artist.subtitle.ifBlank { "Artist" }
        is CatalogItem.Collection -> item.playlist.author.ifBlank { "Playlist" }
        is CatalogItem.Category -> ""
    }

private fun openItem(item: CatalogItem, play: (Track) -> Unit, detail: (String) -> Unit) {
    when (item) {
        is CatalogItem.Song -> play(item.track)
        else -> detail(item.stableId)
    }
}

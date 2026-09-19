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

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import dev.sfg.orchard.mobile.ui.components.ArtworkTile
import dev.sfg.orchard.mobile.ui.components.CatalogCard
import dev.sfg.orchard.mobile.ui.components.CatalogSectionBottomSheet
import dev.sfg.orchard.mobile.ui.components.HomeSectionShimmer
import dev.sfg.orchard.mobile.ui.components.RemoteArtwork
import dev.sfg.orchard.mobile.ui.theme.CanopyColors
import dev.sfg.orchard.mobile.ui.theme.LocalAccent
import java.util.Calendar

data class FoldableSheetState(
    val title: String,
    val initialItems: List<CatalogItem>,
    val browseId: String = "",
    val params: String = "",
)

private fun catalogItemSubtitle(item: CatalogItem): String =
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
    onDevices: () -> Unit,
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
                Surface(
                    modifier =
                        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
                    shape = RoundedCornerShape(16.dp),
                    color = CanopyColors.Surface,
                    border = BorderStroke(1.dp, LocalAccent.current.copy(alpha = 0.35f)),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Rounded.CloudOff,
                            contentDescription = null,
                            tint = LocalAccent.current,
                            modifier = Modifier.size(24.dp),
                        )
                        Spacer(Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Offline Mode",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = CanopyColors.Text,
                            )
                            Text(
                                if (downloadedTracks.isNotEmpty())
                                    "Showing downloaded music (${downloadedTracks.size} ${if (downloadedTracks.size == 1) "song" else "songs"})"
                                else "No internet connection detected",
                                style = MaterialTheme.typography.bodySmall,
                                color = CanopyColors.Muted,
                            )
                        }
                    }
                }
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
                        item {
                            FoldableSectionHeader(
                                title = "Your Playlists",
                                onSeeAll = {
                                    activeSectionSheet =
                                        FoldableSheetState(
                                            title = "Your Playlists",
                                            initialItems = playlistItems,
                                        )
                                },
                            )
                        }
                        item {
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 24.dp),
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                            ) {
                                items(playlistItems, key = { it.stableId }) { item ->
                                    CatalogCard(
                                        item = item,
                                        onClick = { openCatalogItem(item) },
                                        modifier = Modifier.width(160.dp),
                                    )
                                }
                            }
                            Spacer(Modifier.height(24.dp))
                        }
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
                        item {
                            FoldableSectionHeader(
                                title = "Keep listening",
                                onSeeAll = {
                                    activeSectionSheet =
                                        FoldableSheetState(
                                            title = "Keep listening",
                                            initialItems = artistItems,
                                        )
                                },
                            )
                        }
                        item {
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 24.dp),
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                            ) {
                                items(artistItems, key = { it.stableId }) { item ->
                                    CatalogCard(
                                        item = item,
                                        onClick = { openCatalogItem(item) },
                                        modifier = Modifier.width(150.dp),
                                    )
                                }
                            }
                            Spacer(Modifier.height(24.dp))
                        }
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
                        item {
                            FoldableSectionHeader(
                                title = "Top Songs",
                                onSeeAll = {
                                    activeSectionSheet =
                                        FoldableSheetState(
                                            title = "Top Songs",
                                            initialItems = songItems,
                                        )
                                },
                            )
                        }
                        item {
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 24.dp),
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                            ) {
                                items(songItems, key = { it.stableId }) { item ->
                                    CatalogCard(
                                        item = item,
                                        onClick = { openCatalogItem(item) },
                                        modifier = Modifier.width(160.dp),
                                    )
                                }
                            }
                            Spacer(Modifier.height(24.dp))
                        }
                    }
                }

                BuiltInHomeSection.RECOMMENDATIONS -> {
                    if (effectiveOffline) return@forEach
                    if (selectedCategory != "All") return@forEach
                    if (state is LoadState.Content) {
                        state.value.forEach { section ->
                            if (section.items.isNotEmpty()) {
                                item(key = "fold_head_${section.id}") {
                                    FoldableSectionHeader(
                                        title = section.title,
                                        onSeeAll = {
                                            activeSectionSheet =
                                                FoldableSheetState(
                                                    title = section.title,
                                                    initialItems = section.items,
                                                    browseId = section.browseId,
                                                    params = section.params,
                                                )
                                        },
                                    )
                                }
                                item(key = "fold_rail_${section.id}") {
                                    LazyRow(
                                        contentPadding = PaddingValues(horizontal = 24.dp),
                                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                                    ) {
                                        items(section.items, key = { it.stableId }) { item ->
                                            CatalogCard(
                                                item = item,
                                                onClick = { openCatalogItem(item) },
                                                modifier = Modifier.width(160.dp),
                                            )
                                        }
                                    }
                                    Spacer(Modifier.height(24.dp))
                                }
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
                        item {
                            FoldableSectionHeader(
                                title = "Downloaded Playlists",
                                onSeeAll = {
                                    activeSectionSheet =
                                        FoldableSheetState(
                                            title = "Downloaded Playlists",
                                            initialItems = downloadedPlaylists,
                                        )
                                },
                            )
                        }
                        item {
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 24.dp),
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                            ) {
                                items(downloadedPlaylists, key = { it.stableId }) { item ->
                                    CatalogCard(
                                        item = item,
                                        onClick = { openCatalogItem(item) },
                                        modifier = Modifier.width(160.dp),
                                    )
                                }
                            }
                            Spacer(Modifier.height(24.dp))
                        }
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
                        item {
                            FoldableSectionHeader(
                                title = "Downloaded Artists",
                                onSeeAll = {
                                    activeSectionSheet =
                                        FoldableSheetState(
                                            title = "Downloaded Artists",
                                            initialItems = downloadedArtists,
                                        )
                                },
                            )
                        }
                        item {
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 24.dp),
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                            ) {
                                items(downloadedArtists, key = { it.stableId }) { item ->
                                    CatalogCard(
                                        item = item,
                                        onClick = { openCatalogItem(item) },
                                        modifier = Modifier.width(150.dp),
                                    )
                                }
                            }
                            Spacer(Modifier.height(24.dp))
                        }
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
                        item {
                            FoldableSectionHeader(
                                title = "Downloaded Albums",
                                onSeeAll = {
                                    activeSectionSheet =
                                        FoldableSheetState(
                                            title = "Downloaded Albums",
                                            initialItems = downloadedAlbums,
                                        )
                                },
                            )
                        }
                        item {
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 24.dp),
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                            ) {
                                items(downloadedAlbums, key = { it.stableId }) { item ->
                                    CatalogCard(
                                        item = item,
                                        onClick = { openCatalogItem(item) },
                                        modifier = Modifier.width(160.dp),
                                    )
                                }
                            }
                            Spacer(Modifier.height(24.dp))
                        }
                    }
                }

                BuiltInHomeSection.DOWNLOADED_SONGS -> {
                    if (selectedCategory !in listOf("All", "Songs")) return@forEach
                    if (downloadedTracks.isNotEmpty()) {
                        val downloadedSongItems = downloadedTracks.map { CatalogItem.Song(it) }
                        item {
                            FoldableSectionHeader(
                                title = "Downloaded Songs",
                                onSeeAll = {
                                    activeSectionSheet =
                                        FoldableSheetState(
                                            title = "Downloaded Songs",
                                            initialItems = downloadedSongItems,
                                        )
                                },
                            )
                        }
                        item {
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 24.dp),
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                            ) {
                                items(downloadedSongItems, key = { it.stableId }) { item ->
                                    CatalogCard(
                                        item = item,
                                        onClick = { openCatalogItem(item) },
                                        modifier = Modifier.width(160.dp),
                                    )
                                }
                            }
                            Spacer(Modifier.height(24.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FoldableFullBleedHero(
    items: List<CatalogItem>,
    auth: AuthState,
    categories: List<String>,
    selectedCategory: String,
    onSelectCategory: (String) -> Unit,
    onSearch: () -> Unit,
    onProfile: () -> Unit,
    onPlay: (CatalogItem) -> Unit,
    onClick: (CatalogItem) -> Unit,
) {
    if (items.isEmpty()) return
    val pagerState = rememberPagerState(pageCount = { items.size })
    val greeting = remember {
        when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
            in 5..11 -> "Good morning"
            in 12..17 -> "Good afternoon"
            else -> "Good evening"
        }
    }
    val displayName =
        when (auth) {
            is AuthState.SignedIn -> auth.displayName.ifBlank { "Listener" }
            else -> "Guest"
        }
    val avatarUrl =
        when (auth) {
            is AuthState.SignedIn -> auth.avatarUrl
            else -> ""
        }

    Box(modifier = Modifier.fillMaxWidth().height(410.dp)) {
        // 1. Full-bleed background pager
        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
            val item = items[page]
            Box(modifier = Modifier.fillMaxSize().clickable { onClick(item) }) {
                RemoteArtwork(
                    url = item.artworkUrl,
                    description = item.title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )

                // Top scrim for status bar, greeting, and category chip readability
                Box(
                    modifier =
                        Modifier.fillMaxWidth()
                            .height(180.dp)
                            .align(Alignment.TopCenter)
                            .background(
                                Brush.verticalGradient(
                                    0f to Color.Black.copy(alpha = 0.86f),
                                    0.50f to Color.Black.copy(alpha = 0.48f),
                                    1f to Color.Transparent,
                                )
                            )
                )

                // Bottom scrim dissolving smoothly into CanopyColors.Chrome
                Box(
                    modifier =
                        Modifier.fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    0f to Color.Transparent,
                                    0.35f to Color.Transparent,
                                    0.65f to Color.Black.copy(alpha = 0.55f),
                                    0.86f to CanopyColors.Chrome.copy(alpha = 0.92f),
                                    1f to CanopyColors.Chrome,
                                )
                            )
                )
            }
        }

        // 2. Overlaid Floating Content
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            // Top: Greeting + Search + Avatar + Category Filter Pills
            Column(modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(top = 8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "$greeting, $displayName",
                        style =
                            MaterialTheme.typography.headlineMedium.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 26.sp,
                            ),
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )

                    Spacer(Modifier.width(16.dp))

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        // Frosted Search Pill
                        Surface(
                            onClick = onSearch,
                            color = Color.Black.copy(alpha = 0.40f),
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.18f)),
                            shape = CircleShape,
                            modifier = Modifier.width(220.dp).height(40.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    Icons.Rounded.Search,
                                    contentDescription = "Search",
                                    tint = Color.White.copy(alpha = 0.75f),
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = "Search music, albums...",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Color.White.copy(alpha = 0.70f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }

                        // User Avatar
                        Surface(
                            onClick = onProfile,
                            shape = CircleShape,
                            color = Color.Black.copy(alpha = 0.40f),
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.22f)),
                            modifier = Modifier.size(40.dp),
                        ) {
                            if (avatarUrl.isNotBlank()) {
                                ArtworkTile(
                                    url = avatarUrl,
                                    description = displayName,
                                    modifier = Modifier.fillMaxSize(),
                                    radius = 999,
                                )
                            } else {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        Icons.Rounded.Person,
                                        contentDescription = "User Avatar",
                                        tint = Color.White,
                                        modifier = Modifier.size(22.dp),
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))

                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(categories) { category ->
                        val isSelected = selectedCategory == category
                        Surface(
                            onClick = { onSelectCategory(category) },
                            shape = CircleShape,
                            color =
                                if (isSelected) Color.White else Color.Black.copy(alpha = 0.40f),
                            border =
                                if (isSelected) null
                                else BorderStroke(1.dp, Color.White.copy(alpha = 0.18f)),
                            modifier = Modifier.height(34.dp),
                        ) {
                            Box(
                                modifier = Modifier.padding(horizontal = 16.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = category,
                                    style =
                                        MaterialTheme.typography.labelMedium.copy(
                                            fontWeight =
                                                if (isSelected) FontWeight.Bold
                                                else FontWeight.Medium,
                                            fontSize = 13.sp,
                                        ),
                                    color =
                                        if (isSelected) Color.Black
                                        else Color.White.copy(alpha = 0.90f),
                                )
                            }
                        }
                    }
                }
            }

            // Bottom Hero Meta and Action Controls
            val currentItem = items.getOrNull(pagerState.currentPage) ?: items.first()
            val badge =
                when (currentItem) {
                    is CatalogItem.Song -> "SONG"
                    is CatalogItem.Collection -> "PLAYLIST"
                    is CatalogItem.Record -> "ALBUM"
                    is CatalogItem.Performer -> "ARTIST"
                    is CatalogItem.Category -> "FEATURED"
                }
            val subtitle = catalogItemSubtitle(currentItem)

            Column(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                Surface(
                    color = CanopyColors.Surface.copy(alpha = 0.85f),
                    shape = CircleShape,
                ) {
                    Text(
                        text = badge,
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                }

                Spacer(Modifier.height(6.dp))

                Text(
                    text = currentItem.title,
                    style =
                        TextStyle(
                            fontFamily = FontFamily.Serif,
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Bold,
                        ),
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                Spacer(Modifier.height(2.dp))

                Text(
                    text = subtitle.ifBlank { "Featured Collection" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.82f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                Spacer(Modifier.height(14.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Surface(
                            onClick = { onPlay(currentItem) },
                            shape = CircleShape,
                            color = Color.White,
                            shadowElevation = 6.dp,
                            modifier = Modifier.height(42.dp),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 22.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    Icons.Rounded.PlayArrow,
                                    contentDescription = "Play",
                                    tint = Color.Black,
                                    modifier = Modifier.size(24.dp),
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = "Play",
                                    style =
                                        MaterialTheme.typography.labelLarge.copy(
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 15.sp,
                                        ),
                                    color = Color.Black,
                                )
                            }
                        }

                        // Secondary Details Button
                        Surface(
                            onClick = { onClick(currentItem) },
                            shape = CircleShape,
                            color = Color.Black.copy(alpha = 0.40f),
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.25f)),
                            modifier = Modifier.height(42.dp),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 18.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    Icons.Rounded.Info,
                                    contentDescription = "Details",
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = "Details",
                                    style =
                                        MaterialTheme.typography.labelLarge.copy(
                                            fontWeight = FontWeight.SemiBold,
                                            fontSize = 14.sp,
                                        ),
                                    color = Color.White,
                                )
                            }
                        }
                    }

                    // Pager Indicators
                    if (items.size > 1) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            repeat(items.size) { idx ->
                                val isSelected = pagerState.currentPage == idx
                                Box(
                                    modifier =
                                        Modifier.height(5.dp)
                                            .width(if (isSelected) 18.dp else 5.dp)
                                            .clip(CircleShape)
                                            .background(
                                                if (isSelected) Color.White
                                                else Color.White.copy(alpha = 0.35f)
                                            )
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Fallback masthead for foldable layout when library/content is completely empty. */
@Composable
private fun FoldableMasthead(
    auth: AuthState,
    categories: List<String> = emptyList(),
    selectedCategory: String = "All",
    onSelectCategory: (String) -> Unit = {},
    onSearch: () -> Unit,
    onProfile: () -> Unit,
) {
    val greeting = remember {
        when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
            in 5..11 -> "Good morning"
            in 12..17 -> "Good afternoon"
            else -> "Good evening"
        }
    }

    val displayName =
        when (auth) {
            is AuthState.SignedIn -> auth.displayName.ifBlank { "Listener" }
            else -> "Guest"
        }
    val avatarUrl =
        when (auth) {
            is AuthState.SignedIn -> auth.avatarUrl
            else -> ""
        }

    Column(
        modifier =
            Modifier.fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 24.dp, vertical = 18.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "$greeting, $displayName",
                style =
                    MaterialTheme.typography.headlineMedium.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 28.sp,
                    ),
                color = CanopyColors.Text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Surface(
                    onClick = onSearch,
                    color = CanopyColors.Surface,
                    shape = CircleShape,
                    modifier = Modifier.width(260.dp).height(44.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Rounded.Search,
                            contentDescription = "Search",
                            tint = CanopyColors.Muted,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = "Search music, albums...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = CanopyColors.Muted,
                            maxLines = 1,
                        )
                    }
                }

                Surface(
                    onClick = onProfile,
                    shape = CircleShape,
                    color = CanopyColors.Surface,
                    modifier = Modifier.size(44.dp),
                ) {
                    if (avatarUrl.isNotBlank()) {
                        ArtworkTile(
                            url = avatarUrl,
                            description = displayName,
                            modifier = Modifier.fillMaxSize(),
                            radius = 999,
                        )
                    } else {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Rounded.Person,
                                contentDescription = "User Avatar",
                                tint = LocalAccent.current,
                                modifier = Modifier.size(24.dp),
                            )
                        }
                    }
                }
            }
        }

        if (categories.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(categories) { category ->
                    val isSelected = selectedCategory == category
                    Surface(
                        onClick = { onSelectCategory(category) },
                        shape = CircleShape,
                        color = if (isSelected) Color.White else CanopyColors.Surface,
                        border = if (isSelected) null else BorderStroke(1.dp, CanopyColors.Rule),
                        modifier = Modifier.height(34.dp),
                    ) {
                        Box(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = category,
                                style =
                                    MaterialTheme.typography.labelMedium.copy(
                                        fontWeight =
                                            if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                        fontSize = 13.sp,
                                    ),
                                color = if (isSelected) Color.Black else CanopyColors.Text,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FoldableSectionHeader(title: String, onSeeAll: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style =
                MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp,
                ),
            color = CanopyColors.Text,
        )
        Row(
            modifier =
                Modifier.clip(CircleShape)
                    .clickable(onClick = onSeeAll)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "See all",
                style = MaterialTheme.typography.labelLarge,
                color = CanopyColors.Muted,
            )
            Spacer(Modifier.width(4.dp))
            Icon(
                Icons.AutoMirrored.Rounded.ArrowForward,
                contentDescription = null,
                tint = CanopyColors.Muted,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

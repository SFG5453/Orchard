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

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sfg.orchard.mobile.auth.AuthState
import dev.sfg.orchard.mobile.download.DownloadItem
import dev.sfg.orchard.mobile.download.DownloadStatus
import dev.sfg.orchard.mobile.model.Album
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
import dev.sfg.orchard.mobile.ui.components.ArtworkTile
import dev.sfg.orchard.mobile.ui.components.CatalogSectionBottomSheet
import dev.sfg.orchard.mobile.ui.components.ExplicitBadge
import dev.sfg.orchard.mobile.ui.components.HomeSectionShimmer
import dev.sfg.orchard.mobile.ui.components.MessagePanel
import dev.sfg.orchard.mobile.ui.components.OrchardChromeHeight
import dev.sfg.orchard.mobile.ui.components.OrchardMark
import dev.sfg.orchard.mobile.ui.glass.GlassTone
import dev.sfg.orchard.mobile.ui.glass.LocalGlass
import dev.sfg.orchard.mobile.ui.glass.glassFill
import dev.sfg.orchard.mobile.ui.glass.glassPane
import dev.sfg.orchard.mobile.ui.theme.CanopyColors
import dev.sfg.orchard.mobile.ui.theme.LocalAccent
import java.util.Calendar

private data class QuickGridItem(
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
    val glass = LocalGlass.current.enabled
    var selectedMood by remember { mutableStateOf("All") }
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

    val offlinePlaylistItems: List<CatalogItem.Collection> =
        remember(downloadedTracks, downloadedTrackIds, library.savedPlaylists) {
            buildList {
                if (downloadedTracks.isNotEmpty()) {
                    add(
                        CatalogItem.Collection(
                            Playlist(
                                id = "offline_downloads",
                                title = "Downloaded Music",
                                author = "Orchard",
                                artworkUrl =
                                    downloadedTracks
                                        .firstOrNull { it.artworkUrl.isNotBlank() }
                                        ?.artworkUrl
                                        .orEmpty(),
                                description = "All offline tracks on this device",
                                tracks = downloadedTracks,
                            )
                        )
                    )
                }
                library.savedPlaylists.forEach { playlist ->
                    val matching = playlist.tracks.filter { it.id in downloadedTrackIds }
                    if (matching.isNotEmpty()) {
                        add(CatalogItem.Collection(playlist.copy(tracks = matching)))
                    }
                }
            }
        }

    val offlineArtistItems: List<CatalogItem.Performer> =
        remember(downloadedTracks, library.savedArtists) {
            val grouped = mutableMapOf<String, MutableList<Track>>()
            downloadedTracks.forEach { track ->
                if (track.artist.isNotBlank()) {
                    grouped.getOrPut(track.artist) { mutableListOf() }.add(track)
                }
            }
            grouped.map { (artistName, artistTracks) ->
                val artistId =
                    artistTracks.firstOrNull { it.artistId.isNotBlank() }?.artistId ?: artistName
                val artworkUrl =
                    library.savedArtists
                        .firstOrNull { it.name.equals(artistName, ignoreCase = true) }
                        ?.artworkUrl
                        ?: artistTracks
                            .firstOrNull { it.artworkUrl.isNotBlank() }
                            ?.artworkUrl
                            .orEmpty()
                CatalogItem.Performer(
                    Artist(
                        id = artistId,
                        name = artistName,
                        artworkUrl = artworkUrl,
                        subtitle =
                            "${artistTracks.size} downloaded ${if (artistTracks.size == 1) "song" else "songs"}",
                    )
                )
            }
        }

    val offlineAlbumItems: List<CatalogItem.Record> =
        remember(downloadedTracks) {
            val grouped = mutableMapOf<String, MutableList<Track>>()
            downloadedTracks.forEach { track ->
                if (track.album.isNotBlank()) {
                    grouped.getOrPut(track.album) { mutableListOf() }.add(track)
                }
            }
            grouped.map { (albumTitle, albumTracks) ->
                val albumId =
                    albumTracks.firstOrNull { it.albumId.isNotBlank() }?.albumId ?: albumTitle
                val artist = albumTracks.firstOrNull()?.artist.orEmpty()
                val artworkUrl =
                    albumTracks.firstOrNull { it.artworkUrl.isNotBlank() }?.artworkUrl.orEmpty()
                CatalogItem.Record(
                    Album(
                        id = albumId,
                        title = albumTitle,
                        artist = artist,
                        artworkUrl = artworkUrl,
                        year = "",
                        tracks = albumTracks,
                    )
                )
            }
        }

    // Hero items for the featured carousel (Apple Music / ArchiveTune spotlight)
    val featuredHeroItems = remember(state, library) {
        val fromState = if (state is LoadState.Content) {
            state.value.flatMap { it.items }.filter { it.artworkUrl.isNotBlank() }.distinctBy { it.stableId }
        } else emptyList()
        val fromPlaylists = library.savedPlaylists.map { CatalogItem.Collection(it) }
        val combined = (fromState + fromPlaylists).distinctBy { it.stableId }.take(5)
        combined
    }

    val activeSections = remember(state, selectedMood) {
        if (state !is LoadState.Content) emptyList()
        else if (selectedMood == "All") state.value
        else {
            val query = selectedMood.lowercase()
            val matched = state.value.filter {
                it.title.lowercase().contains(query) ||
                it.items.any { item ->
                    item.title.lowercase().contains(query) ||
                    catalogSubtitle(item).lowercase().contains(query)
                }
            }
            matched.ifEmpty { state.value }
        }
    }

    // Spotify-style 6-grid quick access items
    val quickGridItems = remember(library, state) {
        buildList {
            // 1. Liked Music
            if (library.likedTracks.isNotEmpty()) {
                add(
                    QuickGridItem(
                        id = "liked_music",
                        title = "Liked Music",
                        artworkUrl = library.likedTracks.firstOrNull { it.artworkUrl.isNotBlank() }?.artworkUrl.orEmpty(),
                        icon = Icons.Filled.Favorite,
                        gradient = Brush.linearGradient(
                            listOf(Color(0xFF8E2DE2), Color(0xFF4A00E0)),
                        ),
                        onClick = { onLibrary(LibraryFilter.SONGS) },
                        onPlay = { library.likedTracks.firstOrNull()?.let(onPlay) },
                    )
                )
            }
            // 2. Playlists
            library.savedPlaylists.take(4).forEach { playlist ->
                add(
                    QuickGridItem(
                        id = "pl_${playlist.id}",
                        title = playlist.title,
                        artworkUrl = playlist.artworkUrl,
                        onClick = { onOpenDetail(playlist.id) },
                        onPlay = { playCatalogItem(CatalogItem.Collection(playlist)) },
                    )
                )
            }
            // 3. Saved Albums
            if (size < 6) {
                library.savedAlbums.take(6 - size).forEach { album ->
                    add(
                        QuickGridItem(
                            id = "alb_${album.id}",
                            title = album.title,
                            artworkUrl = album.artworkUrl,
                            onClick = { onOpenDetail(album.id) },
                            onPlay = { playCatalogItem(CatalogItem.Record(album)) },
                        )
                    )
                }
            }
            // 4. Catalog items fallback
            if (size < 6 && state is LoadState.Content) {
                val stateItems = state.value.flatMap { it.items }.filter { it.artworkUrl.isNotBlank() }
                stateItems.distinctBy { it.stableId }.take(6 - size).forEach { item ->
                    add(
                        QuickGridItem(
                            id = "st_${item.stableId}",
                            title = item.title,
                            artworkUrl = item.artworkUrl,
                            onClick = { openCatalogItem(item) },
                            onPlay = { playCatalogItem(item) },
                        )
                    )
                }
            }
        }.take(6)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = OrchardChromeHeight),
    ) {
        // Top Masthead (Spotify greeting + Orchard Logo + frosted action buttons)
        item {
            if (glass) {
                GlassHomeHeader(auth = auth, onSearch = onSearch, onProfile = onOpenProfile)
            } else {
                HomeHeader(auth = auth, onSearch = onSearch)
            }
        }

        // Mood & Activity Filter Chips (ArchiveTune / YouTube Music)
        if (!effectiveOffline) {
            item {
                MoodFilterChipsRow(
                    selectedMood = selectedMood,
                    onSelectMood = { selectedMood = it },
                )
                Spacer(Modifier.height(10.dp))
            }
        }

        // Spotify 2x3 Quick-Access Grid (Liked Music & Top Playlists)
        if (selectedMood == "All" && quickGridItems.isNotEmpty() && !effectiveOffline) {
            item {
                SpotifyQuickGrid(
                    items = quickGridItems,
                )
                Spacer(Modifier.height(18.dp))
            }
        }

        // Featured Hero Carousel Banner (Apple Music / ArchiveTune spotlight)
        if (!effectiveOffline && featuredHeroItems.isNotEmpty()) {
            item {
                FeaturedHeroCarousel(
                    items = featuredHeroItems,
                    onPlay = { item -> playCatalogItem(item) },
                    onClick = { item -> openCatalogItem(item) },
                )
                Spacer(Modifier.height(18.dp))
            }
        }

        // Offline Mode Banner
        if (effectiveOffline) {
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
                        .then(if (glass) Modifier.glassPane(RoundedCornerShape(14.dp)) else Modifier),
                    shape = RoundedCornerShape(14.dp),
                    color = glassFill(CanopyColors.Surface),
                    border = BorderStroke(1.dp, LocalAccent.current.copy(alpha = 0.35f)),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Rounded.CloudOff,
                            contentDescription = null,
                            tint = LocalAccent.current,
                            modifier = Modifier.size(22.dp),
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Offline Mode",
                                style = MaterialTheme.typography.titleSmall,
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
                        item {
                            HomeSectionHeader(
                                title = "Your Playlists",
                                onSeeAll = {
                                    activeSectionSheet = HomeSectionSheetState(
                                        title = "Your Playlists",
                                        initialItems = playlistItems,
                                    )
                                },
                            )
                        }
                        item {
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                            ) {
                                itemsIndexed(
                                    playlistItems,
                                    key = { index, item -> "pl_${item.stableId}_$index" },
                                ) { _, item ->
                                    if (glass) {
                                        GlassJumpBackInCard(
                                            item = item,
                                            onClick = { openCatalogItem(item) },
                                            onPlay = { playCatalogItem(item) },
                                        )
                                    } else {
                                        PlaylistCard(
                                            item = item,
                                            onClick = { openCatalogItem(item) },
                                        )
                                    }
                                }
                            }
                        }
                        item { Spacer(Modifier.height(20.dp)) }
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
                        item {
                            HomeSectionHeader(
                                title = "Keep listening",
                                subtitle = "Artists you follow and love",
                                onSeeAll = {
                                    activeSectionSheet = HomeSectionSheetState(
                                        title = "Keep listening",
                                        initialItems = artistItems,
                                    )
                                },
                            )
                        }
                        item {
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                            ) {
                                itemsIndexed(
                                    artistItems,
                                    key = { index, item -> "art_${item.stableId}_$index" },
                                ) { _, item ->
                                    CircularArtistCard(
                                        item = item,
                                        onClick = { openCatalogItem(item) },
                                    )
                                }
                            }
                        }
                        item { Spacer(Modifier.height(20.dp)) }
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
                        item {
                            HomeSectionHeader(
                                title = "Latest Songs",
                                subtitle = "Top picks and recent favorites",
                                onSeeAll = {
                                    activeSectionSheet = HomeSectionSheetState(
                                        title = "Latest Songs",
                                        initialItems = songTracks.map { CatalogItem.Song(it) },
                                    )
                                },
                            )
                        }
                        itemsIndexed(
                            songTracks.take(5),
                            key = { index, track -> "top_${track.id}_$index" },
                        ) { index, track ->
                            val liked = library.likedTracks.any { it.id == track.id }
                            RankedSongRow(
                                rank = index + 1,
                                track = track,
                                liked = liked,
                                onPlay = { onPlay(track) },
                                onToggleLike = { onToggleLike(track) },
                                onPlayNext = onPlayNext,
                                onAddToQueue = onAddToQueue,
                                onAddToPlaylist = onAddToPlaylist,
                                onShare = onShare,
                            )
                        }
                        item { Spacer(Modifier.height(20.dp)) }
                    }
                }
                BuiltInHomeSection.RECOMMENDATIONS -> {
                    if (effectiveOffline) return@forEach
                    when (state) {
                        is LoadState.Content -> {
                            activeSections.forEachIndexed { sectionIndex, section ->
                                item(key = "head:${section.id}") {
                                    HomeSectionHeader(
                                        title = section.title,
                                        onSeeAll = {
                                            activeSectionSheet = HomeSectionSheetState(
                                                title = section.title,
                                                initialItems = section.items,
                                                browseId = section.browseId,
                                                params = section.params,
                                            )
                                        },
                                    )
                                }
                                item(key = "rail:${section.id}") {
                                    LazyRow(
                                        contentPadding = PaddingValues(horizontal = 16.dp),
                                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                                    ) {
                                        itemsIndexed(
                                            section.items,
                                            key = { index, item ->
                                                "${section.id}_${item.stableId}_$index"
                                            },
                                        ) { _, item ->
                                            if (glass) {
                                                when (sectionIndex % 3) {
                                                    0 -> GlassSquircleCard(
                                                        item = item,
                                                        onClick = { openCatalogItem(item) },
                                                        onPlay = { playCatalogItem(item) },
                                                    )
                                                    1 -> GlassJumpBackInCard(
                                                        item = item,
                                                        onClick = { openCatalogItem(item) },
                                                        onPlay = { playCatalogItem(item) },
                                                    )
                                                    else -> GlassRecentlyPlayedCard(
                                                        item = item,
                                                        onClick = { openCatalogItem(item) },
                                                    )
                                                }
                                            } else {
                                                PlaylistCard(
                                                    item = item,
                                                    onClick = {
                                                        openCatalogItem(item)
                                                    },
                                                )
                                            }
                                        }
                                    }
                                }
                                item(key = "gap:${section.id}") {
                                    Spacer(Modifier.height(20.dp))
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
                        item {
                            HomeSectionHeader(
                                title = "Downloaded Playlists",
                                onSeeAll = {
                                    activeSectionSheet = HomeSectionSheetState(
                                        title = "Downloaded Playlists",
                                        initialItems = offlinePlaylistItems,
                                    )
                                },
                            )
                        }
                        item {
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                            ) {
                                itemsIndexed(
                                    offlinePlaylistItems,
                                    key = { index, item -> "off_pl_${item.stableId}_$index" },
                                ) { _, item ->
                                    if (glass) {
                                        GlassJumpBackInCard(
                                            item = item,
                                            onClick = { openCatalogItem(item) },
                                            onPlay = { playCatalogItem(item) },
                                        )
                                    } else {
                                        PlaylistCard(
                                            item = item,
                                            onClick = { openCatalogItem(item) },
                                        )
                                    }
                                }
                            }
                        }
                        item { Spacer(Modifier.height(20.dp)) }
                    }
                }
                BuiltInHomeSection.DOWNLOADED_ARTISTS -> {
                    if (!effectiveOffline) return@forEach
                    if (offlineArtistItems.isNotEmpty()) {
                        item {
                            HomeSectionHeader(
                                title = "Downloaded Artists",
                                onSeeAll = {
                                    activeSectionSheet = HomeSectionSheetState(
                                        title = "Downloaded Artists",
                                        initialItems = offlineArtistItems,
                                    )
                                },
                            )
                        }
                        item {
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                            ) {
                                itemsIndexed(
                                    offlineArtistItems,
                                    key = { index, item -> "off_art_${item.stableId}_$index" },
                                ) { _, item ->
                                    CircularArtistCard(
                                        item = item,
                                        onClick = { openCatalogItem(item) },
                                    )
                                }
                            }
                        }
                        item { Spacer(Modifier.height(20.dp)) }
                    }
                }
                BuiltInHomeSection.DOWNLOADED_ALBUMS -> {
                    if (!effectiveOffline) return@forEach
                    if (offlineAlbumItems.isNotEmpty()) {
                        item {
                            HomeSectionHeader(
                                title = "Downloaded Albums",
                                onSeeAll = {
                                    activeSectionSheet = HomeSectionSheetState(
                                        title = "Downloaded Albums",
                                        initialItems = offlineAlbumItems,
                                    )
                                },
                            )
                        }
                        item {
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                            ) {
                                itemsIndexed(
                                    offlineAlbumItems,
                                    key = { index, item -> "off_alb_${item.stableId}_$index" },
                                ) { _, item ->
                                    if (glass) {
                                        GlassRecentlyPlayedCard(
                                            item = item,
                                            onClick = { openCatalogItem(item) },
                                        )
                                    } else {
                                        PlaylistCard(
                                            item = item,
                                            onClick = { openCatalogItem(item) },
                                        )
                                    }
                                }
                            }
                        }
                        item { Spacer(Modifier.height(20.dp)) }
                    }
                }
                BuiltInHomeSection.DOWNLOADED_SONGS -> {
                    if (!effectiveOffline) return@forEach
                    if (downloadedTracks.isNotEmpty()) {
                        item {
                            HomeSectionHeader(
                                title = "Downloaded Songs",
                                onSeeAll = {
                                    activeSectionSheet = HomeSectionSheetState(
                                        title = "Downloaded Songs",
                                        initialItems = downloadedTracks.map { CatalogItem.Song(it) },
                                    )
                                },
                            )
                        }
                        itemsIndexed(
                            downloadedTracks,
                            key = { index, track -> "off_trk_${track.id}_$index" },
                        ) { index, track ->
                            val liked = library.likedTracks.any { it.id == track.id }
                            RankedSongRow(
                                rank = index + 1,
                                track = track,
                                liked = liked,
                                onPlay = { onPlay(track) },
                                onToggleLike = { onToggleLike(track) },
                                onPlayNext = onPlayNext,
                                onAddToQueue = onAddToQueue,
                                onAddToPlaylist = onAddToPlaylist,
                                onShare = onShare,
                            )
                        }
                        item { Spacer(Modifier.height(20.dp)) }
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
                OutlinedButton(onClick = onEditLayout) {
                    Text("Edit Home Layout", color = CanopyColors.Text)
                }
            }
        }
    }
}

/**
 * Editorial frosted glass masthead with Orchard logo, profile, search, and dynamic greeting.
 */
@Composable
private fun GlassHomeHeader(
    auth: AuthState,
    onSearch: () -> Unit,
    onProfile: () -> Unit,
) {
    val greeting = remember {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        when {
            hour < 12 -> "Good morning"
            hour < 17 -> "Good afternoon"
            else -> "Good evening"
        }
    }
    val firstName = remember(auth) {
        (auth as? AuthState.SignedIn)?.displayName?.trim()?.split(" ")?.firstOrNull()?.ifBlank { "Listener" }
            ?: "Listener"
    }
    val avatarUrl = (auth as? AuthState.SignedIn)?.avatarUrl.orEmpty()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 10.dp)
    ) {
        // Top Row: Orchard Logo & Frosted Action Buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            OrchardMark(
                modifier = Modifier.size(36.dp),
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // Search circular frosted button
                Surface(
                    onClick = onSearch,
                    color = glassFill(CanopyColors.Surface),
                    shape = CircleShape,
                    modifier = Modifier
                        .size(40.dp)
                        .glassPane(CircleShape, GlassTone.CONTROL),
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        Icon(
                            Icons.Rounded.Search,
                            contentDescription = "Search",
                            tint = CanopyColors.Text.copy(alpha = 0.85f),
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }

                // Profile avatar circular button with glowing border
                Surface(
                    onClick = onProfile,
                    color = glassFill(CanopyColors.Surface),
                    shape = CircleShape,
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.28f)),
                    modifier = Modifier
                        .size(40.dp)
                        .glassPane(CircleShape, GlassTone.CONTROL),
                ) {
                    if (avatarUrl.isNotBlank()) {
                        ArtworkTile(
                            url = avatarUrl,
                            description = firstName,
                            modifier = Modifier.fillMaxSize(),
                            radius = 999,
                        )
                    } else {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                            Icon(
                                Icons.Rounded.Person,
                                contentDescription = "Profile",
                                tint = LocalAccent.current,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // Dynamic Greeting (Spotify & Apple Music style)
        Text(
            text = "$greeting, $firstName",
            style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = FontWeight.SemiBold,
                fontSize = 19.sp,
                letterSpacing = (-0.3).sp,
            ),
            color = CanopyColors.Text.copy(alpha = 0.95f),
        )
    }
}

/**
 * Mood and activity filter pills row (YouTube Music & ArchiveTune).
 */
@Composable
private fun MoodFilterChipsRow(
    selectedMood: String,
    onSelectMood: (String) -> Unit,
) {
    val moods = remember {
        listOf("All", "Energize", "Relax", "Workout", "Commute", "Focus", "Feel good", "Party")
    }
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        items(moods) { mood ->
            val isSelected = selectedMood == mood
            val shape = CircleShape
            val animatedBackground by animateColorAsState(
                targetValue = if (isSelected) Color.White.copy(alpha = 0.92f) else glassFill(CanopyColors.Surface),
                animationSpec = tween(200),
                label = "MoodBg",
            )
            val animatedTextColor by animateColorAsState(
                targetValue = if (isSelected) Color(0xFF101318) else CanopyColors.Text.copy(alpha = 0.85f),
                animationSpec = tween(200),
                label = "MoodText",
            )

            Surface(
                onClick = {
                    onSelectMood(if (isSelected && mood != "All") "All" else mood)
                },
                shape = shape,
                color = animatedBackground,
                modifier = Modifier
                    .height(36.dp)
                    .then(
                        if (!isSelected) Modifier.glassPane(shape, GlassTone.CONTROL)
                        else Modifier
                    ),
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                ) {
                    Text(
                        text = mood,
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            fontSize = 13.sp,
                        ),
                        color = animatedTextColor,
                    )
                }
            }
        }
    }
}

/**
 * Spotify-style 2-column x 3-row compact grid for quick access favorites.
 */
@Composable
private fun SpotifyQuickGrid(
    items: List<QuickGridItem>,
) {
    val rows = remember(items) { items.chunked(2) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        rows.forEach { rowItems ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                rowItems.forEach { item ->
                    Box(modifier = Modifier.weight(1f)) {
                        QuickGridCard(
                            item = item,
                        )
                    }
                }
                if (rowItems.size == 1) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

/**
 * Individual Spotify-style quick grid item card.
 */
@Composable
private fun QuickGridCard(
    item: QuickGridItem,
) {
    val shape = RoundedCornerShape(12.dp)
    Surface(
        onClick = item.onClick,
        shape = shape,
        color = glassFill(CanopyColors.Surface),
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .glassPane(shape, GlassTone.PANEL),
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp))
                    .then(
                        if (item.gradient != null) Modifier.background(item.gradient)
                        else Modifier
                    )
            ) {
                if (item.artworkUrl.isNotBlank()) {
                    ArtworkTile(item.artworkUrl, item.title, Modifier.fillMaxSize(), 0)
                } else if (item.icon != null) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        Icon(
                            item.icon,
                            contentDescription = item.title,
                            tint = Color.White,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.width(10.dp))
            Text(
                text = item.title,
                style = MaterialTheme.typography.labelLarge.copy(
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                    lineHeight = 16.sp,
                ),
                color = CanopyColors.Text,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(end = 8.dp),
            )
        }
    }
}

/**
 * Standard Home Header when frosted glass is disabled.
 */
@Composable
private fun HomeHeader(auth: AuthState, onSearch: () -> Unit) {
    val displayName =
        (auth as? AuthState.SignedIn)?.displayName?.ifBlank { "Listener" } ?: "Listener"
    val avatarUrl = (auth as? AuthState.SignedIn)?.avatarUrl.orEmpty()
    Column(
        modifier =
            Modifier.fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = CircleShape,
                    color = CanopyColors.Surface,
                    modifier = Modifier.size(42.dp),
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
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        text = "Welcome Back!",
                        style = MaterialTheme.typography.labelMedium,
                        color = CanopyColors.Muted,
                    )
                    Text(
                        text = displayName,
                        style =
                            MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        color = CanopyColors.Text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // Search Bar Input Pill
        Surface(
            onClick = onSearch,
            color = glassFill(CanopyColors.Surface),
            shape = CircleShape,
            modifier = Modifier.fillMaxWidth().height(48.dp)
                .glassPane(CircleShape, GlassTone.CONTROL),
        ) {
            Row(
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Rounded.Search,
                    contentDescription = "Search",
                    tint = CanopyColors.Muted,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = "What do you want to listen to?",
                    style = MaterialTheme.typography.bodyMedium,
                    color = CanopyColors.Muted,
                )
            }
        }
    }
}

/**
 * Featured Mix Hero Carousel banner with Apple Music editorial tags and ArchiveTune visual style.
 */
@Composable
private fun FeaturedHeroCarousel(
    items: List<CatalogItem>,
    onPlay: (CatalogItem) -> Unit,
    onClick: (CatalogItem) -> Unit,
) {
    if (items.isEmpty()) return
    val pageCount = items.size.coerceAtMost(5)
    val pagerState = rememberPagerState(pageCount = { pageCount })
    val shape = RoundedCornerShape(26.dp)

    Column(modifier = Modifier.fillMaxWidth()) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxWidth()
                .height(255.dp),
            contentPadding = PaddingValues(horizontal = 16.dp),
            pageSpacing = 12.dp,
        ) { page ->
            val item = items[page]
            val subtitle = catalogSubtitle(item).ifBlank { "A curated mix crafted for you." }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(shape)
                    .glassPane(shape, GlassTone.PANEL)
                    .clickable { onClick(item) }
            ) {
                // Background Artwork
                ArtworkTile(
                    url = item.artworkUrl,
                    description = item.title,
                    modifier = Modifier.fillMaxSize(),
                    radius = 0,
                )

                // Dark gradient scrim
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                0f to Color.Black.copy(alpha = 0.20f),
                                0.35f to Color.Black.copy(alpha = 0.40f),
                                1f to Color.Black.copy(alpha = 0.88f),
                            )
                        )
                )

                // Hero Content
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(20.dp),
                    verticalArrangement = Arrangement.SpaceBetween,
                ) {
                    // Top: Frosted pill badge with dynamic editorial kind (Apple Music style)
                    Surface(
                        color = glassFill(CanopyColors.Surface),
                        shape = CircleShape,
                        modifier = Modifier.glassPane(CircleShape, GlassTone.CONTROL),
                    ) {
                        Text(
                            text = catalogItemBadge(item),
                            color = Color.White.copy(alpha = 0.90f),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        )
                    }

                    // Bottom info and play button
                    Column {
                        Text(
                            text = item.title,
                            style = TextStyle(
                                fontFamily = FontFamily.Serif,
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Bold,
                            ),
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.80f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(12.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            // Circular glowing play button matching playerbar styling
                            Surface(
                                onClick = { onPlay(item) },
                                shape = CircleShape,
                                color = Color.White.copy(alpha = 0.94f),
                                shadowElevation = 4.dp,
                                modifier = Modifier.size(48.dp),
                            ) {
                                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                    Icon(
                                        Icons.Rounded.PlayArrow,
                                        contentDescription = "Play",
                                        tint = Color(0xFF101318),
                                        modifier = Modifier.size(28.dp),
                                    )
                                }
                            }

                            // Center Pager Dots
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                modifier = Modifier.padding(end = 8.dp),
                            ) {
                                repeat(pageCount) { index ->
                                    val isSelected = pagerState.currentPage == index
                                    Box(
                                        modifier = Modifier
                                            .height(5.dp)
                                            .width(if (isSelected) 16.dp else 5.dp)
                                            .clip(CircleShape)
                                            .background(
                                                if (isSelected) Color.White else Color.White.copy(alpha = 0.35f)
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
}

/**
 * Squircle frosted card for "Made for you" section with bottom-right floating play button.
 */
@Composable
private fun GlassSquircleCard(
    item: CatalogItem,
    onClick: () -> Unit,
    onPlay: () -> Unit,
) {
    val shape = RoundedCornerShape(22.dp)
    Box(
        modifier = Modifier
            .width(140.dp)
            .aspectRatio(1f)
            .clip(shape)
            .glassPane(shape, GlassTone.PANEL)
            .clickable(onClick = onClick)
    ) {
        ArtworkTile(item.artworkUrl, item.title, Modifier.fillMaxSize(), 0)

        // Gradient scrim at bottom
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.40f to Color.Black.copy(alpha = 0.30f),
                        1f to Color.Black.copy(alpha = 0.85f),
                    )
                )
        )

        Row(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                    lineHeight = 16.sp,
                ),
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(end = 6.dp),
            )

            // Mini circular play button
            Surface(
                onClick = onPlay,
                shape = CircleShape,
                color = Color.White.copy(alpha = 0.92f),
                shadowElevation = 3.dp,
                modifier = Modifier.size(32.dp),
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Icon(
                        Icons.Rounded.PlayArrow,
                        contentDescription = "Play ${item.title}",
                        tint = Color(0xFF101318),
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

/**
 * Wide frosted glass pill card for "Jump back in" section.
 */
@Composable
private fun GlassJumpBackInCard(
    item: CatalogItem,
    onClick: () -> Unit,
    onPlay: () -> Unit,
) {
    val shape = RoundedCornerShape(20.dp)
    Surface(
        onClick = onClick,
        shape = shape,
        color = glassFill(CanopyColors.Surface),
        modifier = Modifier
            .width(220.dp)
            .height(68.dp)
            .glassPane(shape, GlassTone.PANEL),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(14.dp))
            ) {
                ArtworkTile(item.artworkUrl, item.title, Modifier.fillMaxSize(), 14)
            }
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp,
                    ),
                    color = CanopyColors.Text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = catalogSubtitle(item),
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp),
                    color = CanopyColors.Muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(4.dp))
            Surface(
                onClick = onPlay,
                shape = CircleShape,
                color = Color.White.copy(alpha = 0.92f),
                shadowElevation = 2.dp,
                modifier = Modifier.size(32.dp),
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Icon(
                        Icons.Rounded.PlayArrow,
                        contentDescription = "Play",
                        tint = Color(0xFF101318),
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

/**
 * Vertical rounded card for "Recently played" section.
 */
@Composable
private fun GlassRecentlyPlayedCard(
    item: CatalogItem,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .width(120.dp)
            .clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(20.dp))
        ) {
            ArtworkTile(item.artworkUrl, item.title, Modifier.fillMaxSize(), 20)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = item.title,
            style = MaterialTheme.typography.labelLarge.copy(
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.5.sp,
            ),
            color = CanopyColors.Text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = catalogSubtitle(item),
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
            color = CanopyColors.Muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Section Header matching Apple Music & Spotify aesthetic with optional subtitle and see-all button.
 */
@Composable
private fun HomeSectionHeader(
    title: String,
    subtitle: String? = null,
    onSeeAll: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f, fill = false)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp,
                    letterSpacing = (-0.3).sp,
                ),
                color = CanopyColors.Text,
            )
            if (!subtitle.isNullOrBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                    color = CanopyColors.Muted,
                )
            }
        }
        if (onSeeAll != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable(onClick = onSeeAll)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text(
                    text = "See all",
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                    ),
                    color = CanopyColors.Muted,
                )
                Spacer(Modifier.width(2.dp))
                Icon(
                    Icons.AutoMirrored.Rounded.ArrowForward,
                    contentDescription = null,
                    tint = CanopyColors.Muted,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}

@Composable
private fun PlaylistCard(item: CatalogItem, onClick: () -> Unit) {
    Column(modifier = Modifier.width(140.dp).clickable(onClick = onClick)) {
        Box(modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(16.dp))) {
            ArtworkTile(item.artworkUrl, item.title, Modifier.fillMaxSize(), 16)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = item.title,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = CanopyColors.Text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = catalogSubtitle(item),
            style = MaterialTheme.typography.bodySmall,
            color = CanopyColors.Muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Circular artist avatar card matching ArchiveTune "Keep listening" section.
 */
@Composable
private fun CircularArtistCard(item: CatalogItem, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .width(88.dp)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(80.dp)
                .clip(CircleShape)
        ) {
            ArtworkTile(item.artworkUrl, item.title, Modifier.fillMaxSize(), 999)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = item.title,
            style = MaterialTheme.typography.labelMedium.copy(
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
            ),
            color = CanopyColors.Text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = catalogSubtitle(item).ifBlank { "Artist" },
            style = MaterialTheme.typography.bodySmall.copy(
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Medium,
            ),
            color = CanopyColors.Muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * Apple Music style ranked / latest song row with explicit badge, heart like, and options menu.
 */
@Composable
private fun RankedSongRow(
    rank: Int? = null,
    track: Track,
    liked: Boolean,
    onPlay: () -> Unit,
    onToggleLike: () -> Unit,
    onPlayNext: ((Track) -> Unit)?,
    onAddToQueue: ((Track) -> Unit)?,
    onAddToPlaylist: ((Track) -> Unit)?,
    onShare: ((Track) -> Unit)?,
) {
    var popupOpen by remember { mutableStateOf(false) }

    if (popupOpen) {
        dev.sfg.orchard.mobile.ui.components.TrackActionsPopup(
            track = track,
            onDismiss = { popupOpen = false },
            onPlay = onPlay,
            onPlayNext = onPlayNext?.let { { it(track) } },
            onAddToQueue = onAddToQueue?.let { { it(track) } },
            onAddToPlaylist = onAddToPlaylist?.let { { it(track) } },
            onShare = onShare?.let { { it(track) } },
        )
    }

    Surface(
        onClick = onPlay,
        color = Color.Transparent,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (rank != null) {
                Text(
                    text = "$rank",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                    ),
                    color = CanopyColors.Muted,
                    modifier = Modifier.width(22.dp),
                )
                Spacer(Modifier.width(6.dp))
            }
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(12.dp))
            ) {
                ArtworkTile(track.artworkUrl, track.title, Modifier.fillMaxSize(), 12)
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = track.title,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.5.sp,
                        ),
                        color = CanopyColors.Text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (track.explicit) {
                        Spacer(Modifier.width(6.dp))
                        ExplicitBadge()
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    text = track.artist.ifBlank { "Unknown Artist" },
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                    color = CanopyColors.Muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(
                onClick = onToggleLike,
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    if (liked) Icons.Filled.Favorite else Icons.Rounded.FavoriteBorder,
                    contentDescription = if (liked) "Unlike" else "Like",
                    tint = if (liked) CanopyColors.Favorite else CanopyColors.Muted,
                    modifier = Modifier.size(18.dp),
                )
            }
            IconButton(
                onClick = { popupOpen = true },
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    Icons.Rounded.MoreHoriz,
                    contentDescription = "Options",
                    tint = CanopyColors.Muted,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

private inline fun <reified T : CatalogItem> extractItemsOfKind(
    state: LoadState<List<CatalogSection>>
): List<T> {
    if (state !is LoadState.Content) return emptyList()
    return state.value.flatMap { it.items }.filterIsInstance<T>()
}

private fun catalogItemBadge(item: CatalogItem): String =
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

private fun catalogSubtitle(item: CatalogItem): String =
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

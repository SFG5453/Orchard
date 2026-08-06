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

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sfg.orchard.mobile.auth.AuthState
import dev.sfg.orchard.mobile.model.Album
import dev.sfg.orchard.mobile.model.Artist
import dev.sfg.orchard.mobile.model.CatalogItem
import dev.sfg.orchard.mobile.model.CatalogSection
import dev.sfg.orchard.mobile.download.DownloadItem
import dev.sfg.orchard.mobile.download.DownloadStatus
import dev.sfg.orchard.mobile.model.LibraryFilter
import dev.sfg.orchard.mobile.model.LibrarySnapshot
import dev.sfg.orchard.mobile.model.LoadState
import dev.sfg.orchard.mobile.model.Playlist
import dev.sfg.orchard.mobile.model.Track
import dev.sfg.orchard.mobile.ui.components.ArtworkTile
import dev.sfg.orchard.mobile.ui.components.HomeSectionShimmer
import dev.sfg.orchard.mobile.ui.components.MessagePanel
import dev.sfg.orchard.mobile.ui.components.OrchardChromeHeight
import dev.sfg.orchard.mobile.ui.theme.CanopyColors
import dev.sfg.orchard.mobile.ui.theme.LocalAccent

@Composable
fun HomeScreen(
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
) {
        val completedDownloads = remember(downloads) {
                downloads.filter { it.status == DownloadStatus.COMPLETED && it.filePath.isNotBlank() }
        }
        val downloadedTracks: List<Track> = remember(completedDownloads) { completedDownloads.map { it.track } }
        val effectiveOffline = isOffline || state is LoadState.Error

        val offlinePlaylistItems: List<CatalogItem.Collection> = remember(downloadedTracks, downloadedTrackIds, library.savedPlaylists) {
                buildList {
                        if (downloadedTracks.isNotEmpty()) {
                                add(
                                        CatalogItem.Collection(
                                                Playlist(
                                                        id = "offline_downloads",
                                                        title = "Downloaded Music",
                                                        author = "Orchard",
                                                        artworkUrl = downloadedTracks.firstOrNull { it.artworkUrl.isNotBlank() }?.artworkUrl.orEmpty(),
                                                        description = "All offline tracks on this device",
                                                        tracks = downloadedTracks,
                                                )
                                        )
                                )
                        }
                        library.savedPlaylists.forEach { playlist ->
                                val matching = playlist.tracks.filter { it.id in downloadedTrackIds }
                                if (matching.isNotEmpty()) {
                                        add(
                                                CatalogItem.Collection(
                                                        playlist.copy(
                                                                tracks = matching,
                                                        )
                                                )
                                        )
                                }
                        }
                }
        }

        val offlineArtistItems: List<CatalogItem.Performer> = remember(downloadedTracks, library.savedArtists) {
                val grouped = mutableMapOf<String, MutableList<Track>>()
                downloadedTracks.forEach { track ->
                        if (track.artist.isNotBlank()) {
                                grouped.getOrPut(track.artist) { mutableListOf() }.add(track)
                        }
                }
                grouped.map { (artistName, artistTracks) ->
                        val artistId = artistTracks.firstOrNull { it.artistId.isNotBlank() }?.artistId ?: artistName
                        val artworkUrl = library.savedArtists.firstOrNull { it.name.equals(artistName, ignoreCase = true) }?.artworkUrl
                                ?: artistTracks.firstOrNull { it.artworkUrl.isNotBlank() }?.artworkUrl.orEmpty()
                        CatalogItem.Performer(
                                Artist(
                                        id = artistId,
                                        name = artistName,
                                        artworkUrl = artworkUrl,
                                        subtitle = "${artistTracks.size} downloaded ${if (artistTracks.size == 1) "song" else "songs"}",
                                )
                        )
                }
        }

        val offlineAlbumItems: List<CatalogItem.Record> = remember(downloadedTracks) {
                val grouped = mutableMapOf<String, MutableList<Track>>()
                downloadedTracks.forEach { track ->
                        if (track.album.isNotBlank()) {
                                grouped.getOrPut(track.album) { mutableListOf() }.add(track)
                        }
                }
                grouped.map { (albumTitle, albumTracks) ->
                        val albumId = albumTracks.firstOrNull { it.albumId.isNotBlank() }?.albumId ?: albumTitle
                        val artist = albumTracks.firstOrNull()?.artist.orEmpty()
                        val artworkUrl = albumTracks.firstOrNull { it.artworkUrl.isNotBlank() }?.artworkUrl.orEmpty()
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

        LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = OrchardChromeHeight),
        ) {
                // Masthead with Welcome Back user header & search bar
                item { HomeHeader(auth = auth, onSearch = onSearch) }

                // Offline Mode Banner
                if (effectiveOffline) {
                        item {
                                Surface(
                                        modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 16.dp, vertical = 6.dp),
                                        shape = RoundedCornerShape(14.dp),
                                        color = CanopyColors.Surface,
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

                if (effectiveOffline) {
                        // OFFLINE MODE: ONLY show downloaded playlists, downloaded artists (with only downloaded songs), and downloaded songs

                        // 1. Downloaded Playlists
                        if (offlinePlaylistItems.isNotEmpty()) {
                                item {
                                        HomeSectionHeader(
                                                title = "Downloaded Playlists",
                                                onSeeAll = { onLibrary(LibraryFilter.PLAYLISTS) },
                                        )
                                }
                                item {
                                        LazyRow(
                                                contentPadding = PaddingValues(horizontal = 16.dp),
                                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                                        ) {
                                                itemsIndexed(offlinePlaylistItems, key = { index, item -> "off_pl_${item.stableId}_$index" }) { _, item ->
                                                        PlaylistCard(
                                                                item = item,
                                                                onClick = { openItem(item, onPlay, onOpenDetail) }
                                                        )
                                                }
                                        }
                                }
                                item { Spacer(Modifier.height(20.dp)) }
                        }

                        // 2. Downloaded Artists (ONLY the songs that are downloaded)
                        if (offlineArtistItems.isNotEmpty()) {
                                item {
                                        HomeSectionHeader(
                                                title = "Downloaded Artists",
                                                onSeeAll = { onLibrary(LibraryFilter.ARTISTS) },
                                        )
                                }
                                item {
                                        LazyRow(
                                                contentPadding = PaddingValues(horizontal = 16.dp),
                                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                                        ) {
                                                itemsIndexed(offlineArtistItems, key = { index, item -> "off_art_${item.stableId}_$index" }) { _, item ->
                                                        CircularArtistCard(
                                                                item = item,
                                                                onClick = { openItem(item, onPlay, onOpenDetail) }
                                                        )
                                                }
                                        }
                                }
                                item { Spacer(Modifier.height(20.dp)) }
                        }

                        // 3. Downloaded Albums (if any)
                        if (offlineAlbumItems.isNotEmpty()) {
                                item {
                                        HomeSectionHeader(
                                                title = "Downloaded Albums",
                                                onSeeAll = { onLibrary(LibraryFilter.ALBUMS) },
                                        )
                                }
                                item {
                                        LazyRow(
                                                contentPadding = PaddingValues(horizontal = 16.dp),
                                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                                        ) {
                                                itemsIndexed(offlineAlbumItems, key = { index, item -> "off_alb_${item.stableId}_$index" }) { _, item ->
                                                        PlaylistCard(
                                                                item = item,
                                                                onClick = { openItem(item, onPlay, onOpenDetail) }
                                                        )
                                                }
                                        }
                                }
                                item { Spacer(Modifier.height(20.dp)) }
                        }

                        // 4. Downloaded Songs
                        if (downloadedTracks.isNotEmpty()) {
                                item {
                                        HomeSectionHeader(
                                                title = "Downloaded Songs",
                                                onSeeAll = { onLibrary(LibraryFilter.DOWNLOADS) },
                                        )
                                }
                                itemsIndexed(downloadedTracks, key = { index, track -> "off_trk_${track.id}_$index" }) { index, track ->
                                        RankedSongRow(
                                                rank = index + 1,
                                                track = track,
                                                onPlay = { onPlay(track) },
                                        )
                                }
                                item { Spacer(Modifier.height(20.dp)) }
                        } else {
                                item {
                                        MessagePanel(
                                                title = "No downloaded music",
                                                message = "You are currently offline. Connect to the internet to stream music, or download tracks to listen offline.",
                                                actionLabel = "Try reconnecting",
                                                onAction = onRefresh,
                                        )
                                }
                        }
                } else {
                        // ONLINE MODE: Standard recommendations, playlists, subscribed artists, and top songs
                        // Section 1: Your Playlists
                        val playlistItems =
                                library.savedPlaylists.map { CatalogItem.Collection(it) }.ifEmpty {
                                        extractItemsOfKind<CatalogItem.Collection>(state)
                                }.distinctBy { it.stableId }
                        if (playlistItems.isNotEmpty()) {
                                item {
                                        HomeSectionHeader(
                                                title = "Your Playlists",
                                                onSeeAll = { onLibrary(LibraryFilter.PLAYLISTS) },
                                        )
                                }
                                item {
                                        LazyRow(
                                                contentPadding = PaddingValues(horizontal = 16.dp),
                                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                                        ) {
                                                itemsIndexed(playlistItems, key = { index, item -> "pl_${item.stableId}_$index" }) { _, item ->
                                                        PlaylistCard(
                                                                item = item,
                                                                onClick = {
                                                                        openItem(item, onPlay, onOpenDetail)
                                                                }
                                                        )
                                                }
                                        }
                                }
                                item { Spacer(Modifier.height(20.dp)) }
                        }

                        // Section 2: Subscribed Artists
                        val artistItems =
                                library.savedArtists.map { CatalogItem.Performer(it) }.ifEmpty {
                                        extractItemsOfKind<CatalogItem.Performer>(state)
                                }.distinctBy { it.stableId }
                        if (artistItems.isNotEmpty()) {
                                item {
                                        HomeSectionHeader(
                                                title = "Subscribed Artists",
                                                onSeeAll = { onLibrary(LibraryFilter.ARTISTS) },
                                        )
                                }
                                item {
                                        LazyRow(
                                                contentPadding = PaddingValues(horizontal = 16.dp),
                                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                                        ) {
                                                itemsIndexed(artistItems, key = { index, item -> "art_${item.stableId}_$index" }) { _, item ->
                                                        CircularArtistCard(
                                                                item = item,
                                                                onClick = {
                                                                        openItem(item, onPlay, onOpenDetail)
                                                                }
                                                        )
                                                }
                                        }
                                }
                                item { Spacer(Modifier.height(20.dp)) }
                        }

                        // Section 3: Top Songs (Most Listened To Songs)
                        val songTracks =
                                library.mostPlayed
                                        .ifEmpty { library.likedTracks }
                                        .ifEmpty { library.recentlyPlayed }
                                        .ifEmpty {
                                                extractItemsOfKind<CatalogItem.Song>(state).map { it.track }
                                        }.distinctBy { it.id }
                        if (songTracks.isNotEmpty()) {
                                item {
                                        HomeSectionHeader(
                                                title = "Top Songs",
                                                onSeeAll = { onLibrary(LibraryFilter.SONGS) },
                                        )
                                }
                                itemsIndexed(songTracks.take(5), key = { index, track -> "top_${track.id}_$index" }) {
                                        index,
                                        track ->
                                        RankedSongRow(
                                                rank = index + 1,
                                                track = track,
                                                onPlay = { onPlay(track) },
                                        )
                                }
                                item { Spacer(Modifier.height(20.dp)) }
                        }

                        // Additional catalog sections if available
                        when (state) {
                                is LoadState.Content ->
                                        state.value.forEach { section ->
                                                item(key = "head:${section.id}") {
                                                        HomeSectionHeader(
                                                                title = section.title,
                                                                onSeeAll = { onSearch() }
                                                        )
                                                }
                                                item(key = "rail:${section.id}") {
                                                        LazyRow(
                                                                contentPadding =
                                                                        PaddingValues(horizontal = 16.dp),
                                                                horizontalArrangement =
                                                                        Arrangement.spacedBy(14.dp),
                                                        ) {
                                                                itemsIndexed(
                                                                        section.items,
                                                                        key = { index, item -> "${section.id}_${item.stableId}_$index" }
                                                                ) { _, item ->
                                                                        PlaylistCard(
                                                                                item = item,
                                                                                onClick = {
                                                                                        openItem(
                                                                                                item,
                                                                                                onPlay,
                                                                                                onOpenDetail
                                                                                        )
                                                                                }
                                                                        )
                                                                }
                                                        }
                                                }
                                                item(key = "gap:${section.id}") {
                                                        Spacer(Modifier.height(20.dp))
                                                }
                                        }
                                LoadState.Loading ->
                                        if (playlistItems.isEmpty() && songTracks.isEmpty()) {
                                                item { HomeSectionShimmer("Playlists") }
                                                item { HomeSectionShimmer("Top Artists") }
                                        }
                                is LoadState.Empty ->
                                        if (playlistItems.isEmpty() && songTracks.isEmpty()) {
                                                item {
                                                        MessagePanel(
                                                                "A quiet orchard",
                                                                state.message,
                                                                "Refresh",
                                                                onRefresh
                                                        )
                                                }
                                        }
                                is LoadState.Error -> Unit
                                LoadState.Idle -> Unit
                        }
                }
        }
}

@Composable
private fun HomeHeader(auth: AuthState, onSearch: () -> Unit) {
        val displayName =
                (auth as? AuthState.SignedIn)?.displayName?.ifBlank { "Listener" } ?: "Listener"
        val avatarUrl = (auth as? AuthState.SignedIn)?.avatarUrl.orEmpty()
        Column(
                modifier =
                        Modifier.fillMaxWidth()
                                .statusBarsPadding()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
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
                                                        MaterialTheme.typography.titleLarge.copy(
                                                                fontWeight = FontWeight.Bold
                                                        ),
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
                        color = CanopyColors.Surface,
                        shape = CircleShape,
                        modifier = Modifier.fillMaxWidth().height(48.dp),
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

@Composable
private fun HomeSectionHeader(title: String, onSeeAll: () -> Unit) {
        Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
        ) {
                Text(
                        text = title,
                        style =
                                MaterialTheme.typography.titleMedium.copy(
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 18.sp,
                                ),
                        color = CanopyColors.Text,
                )
                Text(
                        text = "See all",
                        style =
                                MaterialTheme.typography.labelMedium.copy(
                                        fontWeight = FontWeight.Medium
                                ),
                        color = CanopyColors.Muted,
                        modifier = Modifier.clickable(onClick = onSeeAll),
                )
        }
}

@Composable
private fun PlaylistCard(item: CatalogItem, onClick: () -> Unit) {
        Column(
                modifier = Modifier.width(140.dp).clickable(onClick = onClick),
        ) {
                Box(
                        modifier =
                                Modifier.fillMaxWidth()
                                        .aspectRatio(1f)
                                        .clip(RoundedCornerShape(16.dp)),
                ) { ArtworkTile(item.artworkUrl, item.title, Modifier.fillMaxSize(), 16) }
                Spacer(Modifier.height(8.dp))
                Text(
                        text = item.title,
                        style =
                                MaterialTheme.typography.titleMedium.copy(
                                        fontWeight = FontWeight.Bold
                                ),
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

@Composable
private fun CircularArtistCard(item: CatalogItem, onClick: () -> Unit) {
        Column(
                modifier = Modifier.width(80.dp).clickable(onClick = onClick),
                horizontalAlignment = Alignment.CenterHorizontally,
        ) {
                Box(
                        modifier = Modifier.size(76.dp).clip(CircleShape),
                ) { ArtworkTile(item.artworkUrl, item.title, Modifier.fillMaxSize(), 999) }
                Spacer(Modifier.height(8.dp))
                Text(
                        text = item.title,
                        style =
                                MaterialTheme.typography.labelMedium.copy(
                                        fontWeight = FontWeight.SemiBold
                                ),
                        color = CanopyColors.Text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                )
        }
}

@Composable
private fun RankedSongRow(rank: Int, track: Track, onPlay: () -> Unit) {
        Row(
                modifier =
                        Modifier.fillMaxWidth()
                                .clickable(onClick = onPlay)
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
        ) {
                Text(
                        text = "$rank",
                        style =
                                MaterialTheme.typography.titleMedium.copy(
                                        fontWeight = FontWeight.Bold
                                ),
                        color = CanopyColors.Muted,
                        modifier = Modifier.width(28.dp),
                )
                Box(
                        modifier = Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)),
                ) { ArtworkTile(track.artworkUrl, track.title, Modifier.fillMaxSize(), 10) }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                        Text(
                                text = track.title,
                                style =
                                        MaterialTheme.typography.titleMedium.copy(
                                                fontWeight = FontWeight.SemiBold
                                        ),
                                color = CanopyColors.Text,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                                text = track.artist,
                                style = MaterialTheme.typography.bodySmall,
                                color = CanopyColors.Muted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                        )
                }
                IconButton(onClick = {}) {
                        Icon(
                                Icons.Rounded.FavoriteBorder,
                                contentDescription = "Like",
                                tint = CanopyColors.Muted,
                                modifier = Modifier.size(20.dp),
                        )
                }
                IconButton(onClick = {}) {
                        Icon(
                                Icons.Rounded.MoreHoriz,
                                contentDescription = "Options",
                                tint = CanopyColors.Muted,
                                modifier = Modifier.size(20.dp),
                        )
                }
        }
}

private inline fun <reified T : CatalogItem> extractItemsOfKind(
        state: LoadState<List<CatalogSection>>
): List<T> {
        if (state !is LoadState.Content) return emptyList()
        return state.value.flatMap { it.items }.filterIsInstance<T>()
}

private fun catalogSubtitle(item: CatalogItem): String =
        when (item) {
                is CatalogItem.Song -> item.track.artist
                is CatalogItem.Record -> item.album.artist
                is CatalogItem.Performer -> item.artist.subtitle.ifBlank { "Artist" }
                is CatalogItem.Collection -> item.playlist.author.ifBlank { "Playlist" }
        }

private fun openItem(item: CatalogItem, play: (Track) -> Unit, detail: (String) -> Unit) {
        when (item) {
                is CatalogItem.Song -> play(item.track)
                else -> detail(item.stableId)
        }
}

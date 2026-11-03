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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import dev.sfg.orchard.mobile.model.BuiltInHomeSection
import dev.sfg.orchard.mobile.model.CatalogItem
import dev.sfg.orchard.mobile.model.LoadState
import dev.sfg.orchard.mobile.model.Track
import dev.sfg.orchard.mobile.ui.components.HomeSectionShimmer
import dev.sfg.orchard.mobile.ui.components.MessagePanel
import dev.sfg.orchard.mobile.ui.components.OrchardChromeHeight
import dev.sfg.orchard.mobile.ui.theme.CanopyColors

@Composable
fun HomeScreen(data: HomeData) {
    if (dev.sfg.orchard.mobile.ui.foldable.isFoldableOrWideLayout()) {
        dev.sfg.orchard.mobile.ui.foldable.FoldableHomeScreen(data)
        return
    }
    HomeSectionSheet(data)

    val actions = data.actions
    val offline = data.offline
    val spotlight = data.spotlight
    val downloadedTracks = data.downloadedTracks

    // Card style shared by several rails.
    val collectionCard: @Composable (CatalogItem) -> Unit = { item ->
        GlassRecentlyPlayedCard(item = item, onClick = { data.open(item) })
    }
    val artistCard: @Composable (CatalogItem) -> Unit = { item ->
        CircularArtistCard(item = item, onClick = { data.open(item) })
    }
    val rankedRow: @Composable (Int, Track) -> Unit = { index, track ->
        RankedSongRow(
            rank = index + 1,
            track = track,
            liked = data.isLiked(track),
            onPlay = { actions.play(track) },
            onToggleLike = { actions.toggleLike(track) },
            onPlayNext = actions.playNext,
            onAddToQueue = actions.addToQueue,
            onAddToPlaylist = actions.addToPlaylist,
            onShare = actions.share,
        )
    }
    val quickPickRow: @Composable (Track) -> Unit = { track ->
        RankedSongRow(
            track = track,
            liked = data.isLiked(track),
            onPlay = { (actions.startMix ?: actions.play)(track) },
            onToggleLike = { actions.toggleLike(track) },
            onPlayNext = actions.playNext,
            onAddToQueue = actions.addToQueue,
            onAddToPlaylist = actions.addToPlaylist,
            onShare = actions.share,
        )
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = OrchardChromeHeight),
        ) {
            // Masthead. Greets you, judges your 3am listening silently.
            item {
                GlassHomeHeader(auth = data.auth, onProfile = actions.openProfile)
            }

            if (!offline && spotlight.isNotEmpty()) {
                item {
                    DiscoverCarousel(
                        title = "Your daily discover",
                        entries = spotlight,
                        onPlayAll = { data.play(spotlight.first().item) },
                        onClick = { item ->
                            val mix = actions.startMix
                            if (item is CatalogItem.Song && mix != null) mix(item.track) else data.open(item)
                        },
                    )
                }
            }

            if (offline) {
                item {
                    OfflineModeBanner(downloadedTracks.size)
                    Spacer(Modifier.height(14.dp))
                }
            }

            data.layout.forEach { config ->
                if (!config.enabled) return@forEach

                when (config.section) {
                    BuiltInHomeSection.YOUR_PLAYLISTS -> {
                        if (offline) return@forEach
                        val playlistItems = data.playlists
                        if (playlistItems.isNotEmpty()) {
                            val openAll = data.seeAll("Your Playlists", playlistItems)
                            homeRail(
                                "Your Playlists", playlistItems, "pl", openAll,
                                header = { AccountSectionTitle("Your playlists", data.auth, openAll) },
                                card = collectionCard,
                            )
                        }
                    }
                    BuiltInHomeSection.SUBSCRIBED_ARTISTS -> {
                        if (offline) return@forEach
                        val artistItems = data.artists
                        if (artistItems.isNotEmpty()) {
                            homeRail(
                                "Keep listening", artistItems, "art", data.seeAll("Keep listening", artistItems),
                                subtitle = "Artists you follow and love", spacing = 16.dp, card = artistCard,
                            )
                        }
                    }
                    BuiltInHomeSection.TOP_SONGS -> {
                        if (offline) return@forEach
                        val picks = data.quickPicks
                        if (picks.size >= QUICK_PICKS_PER_PAGE) {
                            homeQuickPicks(
                                picks,
                                data.seeAll("Quick picks", picks.map { CatalogItem.Song(it) }),
                                row = quickPickRow,
                            )
                        }
                    }
                    BuiltInHomeSection.RECOMMENDATIONS -> {
                        if (offline) return@forEach
                        when (val state = data.state) {
                            is LoadState.Content -> {
                                state.value.forEach { section ->
                                    homeRail(
                                        section.title, section.items, section.id,
                                        data.seeAll(section.title, section.items, section.browseId, section.params),
                                        sectionKey = section.id,
                                        card = collectionCard,
                                    )
                                }
                            }
                            LoadState.Loading -> {
                                item { HomeSectionShimmer("Recommendations") }
                            }
                            is LoadState.Empty -> {
                                item {
                                    MessagePanel("A quiet orchard", state.message, "Refresh", actions.refresh)
                                }
                            }
                            else -> Unit
                        }
                    }
                    BuiltInHomeSection.DOWNLOADED_PLAYLISTS -> {
                        if (!offline) return@forEach
                        if (data.offlinePlaylists.isNotEmpty()) {
                            homeRail(
                                "Downloaded Playlists", data.offlinePlaylists, "off_pl",
                                data.seeAll("Downloaded Playlists", data.offlinePlaylists), card = collectionCard,
                            )
                        }
                    }
                    BuiltInHomeSection.DOWNLOADED_ARTISTS -> {
                        if (!offline) return@forEach
                        if (data.offlineArtists.isNotEmpty()) {
                            homeRail(
                                "Downloaded Artists", data.offlineArtists, "off_art",
                                data.seeAll("Downloaded Artists", data.offlineArtists), spacing = 16.dp, card = artistCard,
                            )
                        }
                    }
                    BuiltInHomeSection.DOWNLOADED_ALBUMS -> {
                        if (!offline) return@forEach
                        if (data.offlineAlbums.isNotEmpty()) {
                            homeRail(
                                "Downloaded Albums", data.offlineAlbums, "off_alb",
                                data.seeAll("Downloaded Albums", data.offlineAlbums),
                                card = collectionCard,
                            )
                        }
                    }
                    BuiltInHomeSection.DOWNLOADED_SONGS -> {
                        if (!offline) return@forEach
                        if (downloadedTracks.isNotEmpty()) {
                            homeTrackSection(
                                "Downloaded Songs", downloadedTracks, "off_trk",
                                data.seeAll("Downloaded Songs", downloadedTracks.map { CatalogItem.Song(it) }),
                                row = rankedRow,
                            )
                        } else {
                            item {
                                MessagePanel(
                                    title = "No downloaded music",
                                    message =
                                        "You are currently offline. Connect to the internet to stream music, or download tracks to listen offline.",
                                    actionLabel = "Try reconnecting",
                                    onAction = actions.refresh,
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
                    TextButton(onClick = actions.editLayout) {
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
                    0f to Color.Black.copy(alpha = 0.6f),
                    0.5f to Color.Black.copy(alpha = 0.35f),
                    1f to Color.Transparent,
                )
            )
    )
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

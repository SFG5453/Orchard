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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.sfg.orchard.mobile.model.BrowseDetail
import dev.sfg.orchard.mobile.model.CatalogKind
import dev.sfg.orchard.mobile.model.LoadState
import dev.sfg.orchard.mobile.model.Track
import dev.sfg.orchard.mobile.model.CatalogItem
import dev.sfg.orchard.mobile.ui.components.ArtistBioBottomSheet
import dev.sfg.orchard.mobile.ui.components.ArtistHero
import dev.sfg.orchard.mobile.ui.components.ArtistSectionBottomSheet
import dev.sfg.orchard.mobile.ui.components.CatalogCard
import dev.sfg.orchard.mobile.ui.components.DetailBackButton
import dev.sfg.orchard.mobile.ui.components.DetailDescriptionBottomSheet
import dev.sfg.orchard.mobile.ui.components.rememberArtworkPalette
import dev.sfg.orchard.mobile.ui.components.MessagePanel
import dev.sfg.orchard.mobile.ui.components.OrchardSectionHeader
import dev.sfg.orchard.mobile.ui.components.TrackRow
import dev.sfg.orchard.mobile.ui.theme.CanopyColors
import dev.sfg.orchard.mobile.ui.theme.LocalAccent

@Composable
fun DetailScreen(
    state: LoadState<BrowseDetail>,
    onBack: () -> Unit,
    onPlayAll: (List<Track>, String) -> Unit,
    onShuffle: (List<Track>, String) -> Unit,
    shuffleAvailable: Boolean,
    onPlay: (Track, String) -> Unit,
    onPlayTrack: (List<Track>, Int, String) -> Unit = { list, idx, src -> onPlay(list[idx], src) },
    onPlayNext: ((Track) -> Unit)?,
    onAddToQueue: ((Track) -> Unit)?,
    onSave: (BrowseDetail) -> Unit,
    onOpenDetail: (String) -> Unit,
    isSaved: Boolean = false,
    downloadedTrackIds: Set<String> = emptySet(),
    downloadingTrackIds: Set<String> = emptySet(),
    onDownloadTrack: ((Track) -> Unit)? = null,
    onDownloadTracks: ((List<Track>) -> Unit)? = null,
    onRemoveDownloadTrack: ((String) -> Unit)? = null,
    onRemoveDownloadTracks: ((List<Track>) -> Unit)? = null,
    animatedArtworkUrl: String = "",
    artistPortraitUrl: String = "",
    onShareTrack: ((Track) -> Unit)? = null,
    onShareCollection: ((BrowseDetail) -> Unit)? = null,
    onFetchSectionItems: (suspend (String, String) -> List<CatalogItem>)? = null,
) {
    when (state) {
        LoadState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = LocalAccent.current)
        }
        is LoadState.Error -> Column {
            DetailBackButton(onBack)
            MessagePanel("Collection unavailable", state.message)
        }
        is LoadState.Empty -> Column {
            DetailBackButton(onBack)
            MessagePanel("Nothing here", state.message)
        }
        is LoadState.Content -> {
            if (state.value.kind == CatalogKind.ARTIST) {
                ArtistDetailContent(
                    detail = state.value,
                    onBack = onBack,
                    onPlayAll = onPlayAll,
                    onShuffle = onShuffle,
                    onPlayTrack = onPlayTrack,
                    onPlayNext = onPlayNext,
                    shuffleAvailable = shuffleAvailable,
                    onAdd = onAddToQueue,
                    onSave = onSave,
                    onOpen = onOpenDetail,
                    downloadedTrackIds = downloadedTrackIds,
                    downloadingTrackIds = downloadingTrackIds,
                    onDownloadTrack = onDownloadTrack,
                    onRemoveDownloadTrack = onRemoveDownloadTrack,
                    onShareTrack = onShareTrack,
                    onShareCollection = onShareCollection,
                    onFetchSectionItems = onFetchSectionItems,
                )
            } else {
                CollectionDetailContent(
                    detail = state.value,
                    onBack = onBack,
                    onPlayAll = onPlayAll,
                    onShuffle = onShuffle,
                    onPlayTrack = onPlayTrack,
                    onPlayNext = onPlayNext,
                    shuffleAvailable = shuffleAvailable,
                    onAdd = onAddToQueue,
                    onSave = onSave,
                    onOpen = onOpenDetail,
                    isSaved = isSaved,
                    downloadedTrackIds = downloadedTrackIds,
                    downloadingTrackIds = downloadingTrackIds,
                    onDownloadTrack = onDownloadTrack,
                    onDownloadTracks = onDownloadTracks,
                    onRemoveDownloadTrack = onRemoveDownloadTrack,
                    onRemoveDownloadTracks = onRemoveDownloadTracks,
                    animatedArtworkUrl = animatedArtworkUrl,
                    artistPortraitUrl = artistPortraitUrl,
                    onShareTrack = onShareTrack,
                    onShareCollection = onShareCollection,
                )
            }
        }
        LoadState.Idle -> Unit
    }
}

/** A pending section sheet: title, initial preview items, and (optionally) the API browse key. */
private data class SectionSheetState(
    val title: String,
    val initialItems: List<CatalogItem>,
    val browseId: String = "",
    val params: String = "",
)

/** Canopy mobile layout for artists: edge-to-edge backdrop hero, popular tracks, discography rails. */
@Composable
private fun ArtistDetailContent(
    detail: BrowseDetail,
    onBack: () -> Unit,
    onPlayAll: (List<Track>, String) -> Unit,
    onShuffle: (List<Track>, String) -> Unit,
    onPlayTrack: (List<Track>, Int, String) -> Unit,
    onPlayNext: ((Track) -> Unit)?,
    shuffleAvailable: Boolean,
    onAdd: ((Track) -> Unit)?,
    onSave: (BrowseDetail) -> Unit,
    onOpen: (String) -> Unit,
    downloadedTrackIds: Set<String> = emptySet(),
    downloadingTrackIds: Set<String> = emptySet(),
    onDownloadTrack: ((Track) -> Unit)? = null,
    onRemoveDownloadTrack: ((String) -> Unit)? = null,
    onShareTrack: ((Track) -> Unit)? = null,
    onShareCollection: ((BrowseDetail) -> Unit)? = null,
    onFetchSectionItems: (suspend (String, String) -> List<CatalogItem>)? = null,
) {
    var showBioSheet by remember { mutableStateOf(false) }
    var activeSectionSheet by remember { mutableStateOf<SectionSheetState?>(null) }
    var showAllPopularTracks by remember { mutableStateOf(false) }

    if (showBioSheet && detail.description.isNotBlank()) {
        ArtistBioBottomSheet(detail = detail, onDismiss = { showBioSheet = false })
    }

    activeSectionSheet?.let { sheet ->
        ArtistSectionBottomSheet(
            title = sheet.title,
            initialItems = sheet.initialItems,
            browseId = sheet.browseId,
            params = sheet.params,
            onFetchFullItems = onFetchSectionItems,
            onOpen = onOpen,
            onDismiss = { activeSectionSheet = null },
        )
    }

    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 128.dp)) {
        item {
            ArtistHero(
                detail = detail,
                onBack = onBack,
                onPlayAll = onPlayAll,
                onShuffle = onShuffle,
                shuffleAvailable = shuffleAvailable,
                onSave = onSave,
                onOpenBio = { showBioSheet = true },
            )
        }
        if (detail.tracks.isNotEmpty()) {
            val hasMorePopular = detail.tracks.size > 5
            val displayedTracks = if (showAllPopularTracks || !hasMorePopular) {
                detail.tracks
            } else {
                detail.tracks.take(5)
            }
            item {
                OrchardSectionHeader(
                    title = "Popular",
                    action = if (hasMorePopular) {
                        if (showAllPopularTracks) "Show less" else "View all"
                    } else null,
                    onAction = if (hasMorePopular) {
                        { showAllPopularTracks = !showAllPopularTracks }
                    } else null,
                )
            }
            itemsIndexed(displayedTracks, key = { _, track -> track.id }) { index, track ->
                val trackIndex = detail.tracks.indexOf(track).coerceAtLeast(index)
                val isDownloaded = downloadedTrackIds.contains(track.id)
                val isDownloading = downloadingTrackIds.contains(track.id)
                TrackRow(
                    track = track,
                    onPlay = { onPlayTrack(detail.tracks, trackIndex, detail.title) },
                    modifier = Modifier.padding(horizontal = 8.dp),
                    onPlayNext = onPlayNext?.let { action -> { action(track) } },
                    onAddToQueue = onAdd?.let { action -> { action(track) } },
                    onDownload = onDownloadTrack?.let { action -> { action(track) } },
                    onRemoveDownload = onRemoveDownloadTrack?.let { action -> { action(track.id) } },
                    isDownloaded = isDownloaded,
                    isDownloading = isDownloading,
                    onShare = onShareTrack?.let { action -> { action(track) } },
                    onViewAlbum = if (track.albumId.isNotBlank()) {{ onOpen(track.albumId) }} else null,
                    onViewArtist = if (track.artistId.isNotBlank()) {{ onOpen(track.artistId) }} else null,
                )
            }
        }
        if (detail.sections.isNotEmpty()) {
            detail.sections.forEach { section ->
                // Show "View all" when the section has a browse endpoint (can load more from API)
                // or when there are enough local items to warrant showing the full grid.
                val hasMoreViaApi = section.browseId.isNotBlank()
                val canViewAll = hasMoreViaApi || section.items.size > 3
                item {
                    OrchardSectionHeader(
                        title = section.title,
                        action = if (canViewAll) "View all" else null,
                        onAction = if (canViewAll) {
                            {
                                activeSectionSheet = SectionSheetState(
                                    title = section.title,
                                    initialItems = section.items,
                                    browseId = section.browseId,
                                    params = section.params,
                                )
                            }
                        } else null,
                    )
                }
                item {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        items(section.items, key = { it.stableId }) { item ->
                            CatalogCard(item, onClick = { onOpen(item.stableId) })
                        }
                    }
                }
            }
        } else if (detail.related.isNotEmpty()) {
            val canViewAll = detail.related.size > 3
            item {
                OrchardSectionHeader(
                    title = "Fans also like",
                    action = if (canViewAll) "View all" else null,
                    onAction = if (canViewAll) {
                        {
                            activeSectionSheet = SectionSheetState(
                                title = "Fans also like",
                                initialItems = detail.related,
                            )
                        }
                    } else null,
                )
            }
            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    items(detail.related, key = { it.stableId }) { item ->
                        CatalogCard(item, onClick = { onOpen(item.stableId) })
                    }
                }
            }
        }
    }
}

/** Standard album and playlist presentation. */
@Composable
private fun CollectionDetailContent(
    detail: BrowseDetail,
    onBack: () -> Unit,
    onPlayAll: (List<Track>, String) -> Unit,
    onShuffle: (List<Track>, String) -> Unit,
    onPlayTrack: (List<Track>, Int, String) -> Unit,
    onPlayNext: ((Track) -> Unit)?,
    shuffleAvailable: Boolean,
    onAdd: ((Track) -> Unit)?,
    onSave: (BrowseDetail) -> Unit,
    onOpen: (String) -> Unit,
    isSaved: Boolean = false,
    downloadedTrackIds: Set<String> = emptySet(),
    downloadingTrackIds: Set<String> = emptySet(),
    onDownloadTrack: ((Track) -> Unit)? = null,
    onDownloadTracks: ((List<Track>) -> Unit)? = null,
    onRemoveDownloadTrack: ((String) -> Unit)? = null,
    onRemoveDownloadTracks: ((List<Track>) -> Unit)? = null,
    animatedArtworkUrl: String = "",
    artistPortraitUrl: String = "",
    onShareTrack: ((Track) -> Unit)? = null,
    onShareCollection: ((BrowseDetail) -> Unit)? = null,
) {
    var showDescriptionSheet by remember { mutableStateOf(false) }

    // The cover's own colours carry the whole screen.
    val palette = rememberArtworkPalette(detail.artworkUrl)

    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    0f to palette.top.copy(alpha = 0.70f),
                    0.25f to palette.bottom.copy(alpha = 0.45f),
                    0.55f to palette.deep,
                    1f to palette.deep,
                ),
            ),
    ) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 120.dp)) {
            item {
                CollectionHero(
                    detail = detail,
                    palette = palette,
                    shuffleAvailable = shuffleAvailable,
                    onBack = onBack,
                    onPlayAll = onPlayAll,
                    onShuffle = onShuffle,
                    onSave = onSave,
                    onAbout = { showDescriptionSheet = true },
                    isSaved = isSaved,
                    downloadedTrackIds = downloadedTrackIds,
                    downloadingTrackIds = downloadingTrackIds,
                    onDownloadTracks = onDownloadTracks,
                    onRemoveDownloadTracks = onRemoveDownloadTracks,
                    animatedArtworkUrl = animatedArtworkUrl,
                    artistPortraitUrl = artistPortraitUrl,
                    onShare = onShareCollection,
                )
            }
            if (detail.tracks.isNotEmpty()) {
                itemsIndexed(detail.tracks, key = { _, track -> track.id }) { index, track ->
                    val isAlbum = detail.kind == CatalogKind.ALBUM
                    val isDownloaded = downloadedTrackIds.contains(track.id)
                    val isDownloading = downloadingTrackIds.contains(track.id)
                    TrackRow(
                        track = track,
                        trackNumber = if (isAlbum) index + 1 else null,
                        showArtwork = !isAlbum,
                        parentArtist = if (isAlbum) detail.artist else "",
                        showDivider = isAlbum && index < detail.tracks.lastIndex,
                        onPlay = { onPlayTrack(detail.tracks, index, detail.title) },
                        modifier = Modifier.padding(horizontal = 8.dp),
                        onPlayNext = onPlayNext?.let { action -> { action(track) } },
                        onAddToQueue = onAdd?.let { action -> { action(track) } },
                        onDownload = onDownloadTrack?.let { action -> { action(track) } },
                        onRemoveDownload = onRemoveDownloadTrack?.let { action -> { action(track.id) } },
                        isDownloaded = isDownloaded,
                        isDownloading = isDownloading,
                        onShare = onShareTrack?.let { action -> { action(track) } },
                        onViewAlbum = if (track.albumId.isNotBlank()) {{ onOpen(track.albumId) }} else null,
                        onViewArtist = if (track.artistId.isNotBlank()) {{ onOpen(track.artistId) }} else null,
                    )
                }

                item {
                    val totalMs = remember(detail.tracks) { detail.tracks.sumOf { it.durationMs } }
                    val totalSeconds = totalMs / 1000
                    val hours = totalSeconds / 3600
                    val remainingMinutes = (totalSeconds % 3600) / 60
                    val durationSummary = buildString {
                        if (hours > 0) {
                            append(", $hours Hour${if (hours > 1) "s" else ""}")
                            if (remainingMinutes > 0) {
                                append(" $remainingMinutes Minute${if (remainingMinutes > 1) "s" else ""}")
                            }
                        } else if (remainingMinutes > 0) {
                            append(", $remainingMinutes Minute${if (remainingMinutes > 1) "s" else ""}")
                        }
                    }
                    val countSummary = "${detail.tracks.size} Song${if (detail.tracks.size == 1) "" else "s"}$durationSummary"
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 20.dp),
                    ) {
                        if (detail.year.isNotBlank()) {
                            Text(
                                text = "Released ${detail.year}",
                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                                color = Color.White.copy(alpha = 0.50f),
                            )
                            Spacer(Modifier.height(2.dp))
                        }
                        Text(
                            text = countSummary,
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                            color = Color.White.copy(alpha = 0.50f),
                        )
                    }
                }
            }
            if (detail.related.isNotEmpty()) {
                item {
                    Text(
                        text = "More like this",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = Color.White,
                        modifier = Modifier.padding(start = 20.dp, top = 24.dp, bottom = 12.dp),
                    )
                }
                item {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        items(detail.related, key = { it.stableId }) { item ->
                            CatalogCard(item, { onOpen(item.stableId) })
                        }
                    }
                }
            }
        }

        if (showDescriptionSheet && detail.description.isNotBlank()) {
            DetailDescriptionBottomSheet(detail = detail, onDismiss = { showDescriptionSheet = false })
        }
    }
}

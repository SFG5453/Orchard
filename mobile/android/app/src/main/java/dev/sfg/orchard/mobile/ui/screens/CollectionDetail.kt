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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import dev.sfg.orchard.mobile.ui.scroll.OrchardLazyColumn as LazyColumn
import dev.sfg.orchard.mobile.ui.scroll.OrchardLazyRow as LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.sfg.orchard.mobile.model.BrowseDetail
import dev.sfg.orchard.mobile.model.CatalogKind
import dev.sfg.orchard.mobile.model.Track
import dev.sfg.orchard.mobile.model.CatalogItem
import dev.sfg.orchard.mobile.ui.motion.riseIn
import dev.sfg.orchard.mobile.ui.components.ArtistSectionBottomSheet
import dev.sfg.orchard.mobile.ui.components.CatalogCard
import dev.sfg.orchard.mobile.ui.components.rememberArtworkPalette
import dev.sfg.orchard.mobile.ui.components.rememberHeroScrimProgress
import dev.sfg.orchard.mobile.ui.components.MessagePanel
import dev.sfg.orchard.mobile.ui.components.ReorderControls
import dev.sfg.orchard.mobile.ui.components.TrackRow
import dev.sfg.orchard.mobile.ui.components.filterTracks
import dev.sfg.orchard.mobile.ui.theme.CanopyColors

/** Standard album and playlist presentation. */
@Composable
internal fun CollectionDetailContent(
    detail: BrowseDetail,
    onBack: () -> Unit,
    onPlayAll: (List<Track>, String) -> Unit,
    onShuffle: (List<Track>, String) -> Unit,
    onPlayTrack: (List<Track>, Int, String) -> Unit,
    onPlayNext: ((Track) -> Unit)?,
    shuffleAvailable: Boolean,
    onAdd: ((Track) -> Unit)?,
    onAddToPlaylist: ((Track) -> Unit)? = null,
    onRemoveFromPlaylist: ((Track) -> Unit)? = null,
    onMovePlaylistTrack: ((Int, Int) -> Unit)? = null,
    localEdit: LocalPlaylistEdit? = null,
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
    smartCrossfadeEnabled: Boolean = false,
    onPlayBestMix: ((List<Track>, String, (String) -> Unit, () -> Unit) -> Unit)? = null,
    onFetchSectionItems: (suspend (String, String) -> List<CatalogItem>)? = null,
) {
    var activeSectionSheet by remember { mutableStateOf<SectionSheetState?>(null) }
    var isSearching by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    // Edit mode: rows grow move and remove buttons. Offered wherever the listener may edit the playlist.
    var editing by remember { mutableStateOf(false) }
    val canEdit = detail.kind == CatalogKind.PLAYLIST && detail.editable && onMovePlaylistTrack != null
    val editRows = editing && canEdit && !isSearching

    // The cover's own colours carry the whole screen.
    val palette = rememberArtworkPalette(detail.artworkUrl)
    val listState = rememberLazyListState()
    val scrimProgress = rememberHeroScrimProgress(listState)
    val bestMix = rememberBestMixLauncher(detail, downloadedTrackIds, onPlayAll, onPlayBestMix)
    // Albums link their artist chip; playlists mix artists, so the chip stays inert.
    val albumArtistId = remember(detail) {
        if (detail.kind != CatalogKind.ALBUM) null
        else detail.tracks.firstOrNull { it.artistId.isNotBlank() }?.artistId
    }

    val filteredTracks = remember(detail.tracks, searchQuery, isSearching) {
        if (isSearching && searchQuery.isNotBlank()) {
            filterTracks(detail.tracks, searchQuery)
        } else {
            detail.tracks
        }
    }

    if (activeSectionSheet != null) {
        activeSectionSheet?.let { sheet ->
            ArtistSectionBottomSheet(
                title = sheet.title,
                initialItems = sheet.initialItems,
                browseId = sheet.browseId,
                params = sheet.params,
                onFetchFullItems = onFetchSectionItems,
                onPlay = { track -> onPlayTrack(listOf(track), 0, sheet.title) },
                onOpen = onOpen,
                onDismiss = { activeSectionSheet = null },
            )
        }
    }

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
        LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = 120.dp)) {
            item {
                CollectionHero(
                    detail = detail,
                    palette = palette,
                    shuffleAvailable = shuffleAvailable,
                    onPlayAll = onPlayAll,
                    onShuffle = onShuffle,
                    onSave = onSave,
                    bestMix = bestMix,
                    isSaved = isSaved,
                    animatedArtworkUrl = animatedArtworkUrl,
                    artistPortraitUrl = artistPortraitUrl,
                    onOpenArtist = albumArtistId?.let { id -> { onOpen(id) } },
                    smartCrossfadeEnabled = smartCrossfadeEnabled,
                )
            }
            if (canEdit && !isSearching) {
                item {
                    PlaylistEditBar(
                        title = detail.title,
                        editing = editing,
                        onEditingChange = { editing = it },
                        local = localEdit,
                    )
                }
            }
            if (isSearching && searchQuery.isNotBlank()) {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = "${filteredTracks.size} ${if (filteredTracks.size == 1) "track" else "tracks"} found",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = CanopyColors.Muted,
                        )
                        Surface(
                            onClick = { searchQuery = "" },
                            shape = RoundedCornerShape(12.dp),
                            color = Color.White.copy(alpha = 0.12f),
                        ) {
                            Text(
                                text = "Clear",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                color = Color.White,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            )
                        }
                    }
                }

                if (filteredTracks.isEmpty()) {
                    item {
                        MessagePanel(
                            title = "No matching tracks",
                            message = "No songs matching \"$searchQuery\" found in ${detail.title}.",
                            actionLabel = "Clear search",
                            onAction = { searchQuery = "" },
                        )
                    }
                }
            }

            if (filteredTracks.isNotEmpty()) {
                itemsIndexed(filteredTracks, key = { index, track -> "${track.id}_$index" }) { index, track ->
                    val isAlbum = detail.kind == CatalogKind.ALBUM
                    val isDownloaded = downloadedTrackIds.contains(track.id)
                    val isDownloading = downloadingTrackIds.contains(track.id)
                    val originalIndex = detail.tracks.indexOf(track).takeIf { it >= 0 } ?: index
                    val rowModifier = Modifier
                        .animateItem(fadeInSpec = null)
                        .padding(horizontal = 8.dp)
                        .riseIn(index, cascadeOnScroll = true)
                    val trackRow: @Composable (Modifier) -> Unit = {
                        TrackRow(
                            track = track,
                            trackNumber = if (isAlbum) originalIndex + 1 else null,
                            showArtwork = !isAlbum,
                            parentArtist = if (isAlbum) detail.artist else "",
                            showAlbum = false,
                            showDivider = index < filteredTracks.lastIndex,
                            onPlay = { onPlayTrack(filteredTracks, index, detail.title) },
                            modifier = it,
                            onPlayNext = onPlayNext?.let { action -> { action(track) } },
                            onAddToQueue = onAdd?.let { action -> { action(track) } },
                            onAddToPlaylist = if (detail.kind == CatalogKind.ALBUM || detail.kind == CatalogKind.PLAYLIST)
                                onAddToPlaylist?.let { action -> { action(track) } } else null,
                            onRemoveFromPlaylist = if (detail.kind == CatalogKind.PLAYLIST)
                                onRemoveFromPlaylist?.let { action -> { action(track) } } else null,
                            onMoveUp = if (detail.editable && !isSearching && index > 0)
                                onMovePlaylistTrack?.let { action -> { action(index, index - 1) } } else null,
                            onMoveDown = if (detail.editable && !isSearching && index < detail.tracks.lastIndex)
                                onMovePlaylistTrack?.let { action -> { action(index, index + 1) } } else null,
                            onDownload = onDownloadTrack?.let { action -> { action(track) } },
                            onRemoveDownload = onRemoveDownloadTrack?.let { action -> { action(track.id) } },
                            isDownloaded = isDownloaded,
                            isDownloading = isDownloading,
                            onShare = onShareTrack?.let { action -> { action(track) } },
                            onViewAlbum = if (track.albumId.isNotBlank()) {{ onOpen(track.albumId) }} else null,
                            onViewArtist = if (track.artistId.isNotBlank()) {{ onOpen(track.artistId) }} else null,
                        )
                    }
                    if (editRows) {
                        Row(rowModifier, verticalAlignment = Alignment.CenterVertically) {
                            trackRow(Modifier.weight(1f))
                            ReorderControls(
                                onMoveUp = if (index > 0) onMovePlaylistTrack?.let { move -> { move(index, index - 1) } } else null,
                                onMoveDown = if (index < detail.tracks.lastIndex) onMovePlaylistTrack?.let { move -> { move(index, index + 1) } } else null,
                                onRemove = onRemoveFromPlaylist?.let { remove -> { remove(track) } },
                            )
                        }
                    } else {
                        trackRow(rowModifier)
                    }
                }

                if (!isSearching || searchQuery.isBlank()) {
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
            }
            if ((!isSearching || searchQuery.isBlank()) && detail.sections.isNotEmpty()) {
                detail.sections.forEach { section ->
                    val hasMoreViaApi = section.browseId.isNotBlank()
                    val canViewAll = hasMoreViaApi || section.items.size > 3
                    item {
                        HomeSectionTitle(
                            title = section.title,
                            onClick = if (canViewAll) {
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
                            contentPadding = PaddingValues(horizontal = 20.dp),
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            itemsIndexed(section.items, key = { index, it -> "${it.stableId}_$index" }) { index, item ->
                                CatalogCard(item, modifier = Modifier.riseIn(index, fromScale = 0.9f, cascadeOnScroll = true), onClick = {
                                    if (item is CatalogItem.Song) {
                                        onPlayTrack(listOf(item.track), 0, section.title)
                                    } else {
                                        onOpen(item.stableId)
                                    }
                                })
                            }
                        }
                    }
                }
            } else if ((!isSearching || searchQuery.isBlank()) && detail.related.isNotEmpty()) {
                item { HomeSectionTitle(title = "More like this") }
                item {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        itemsIndexed(detail.related, key = { index, it -> "${it.stableId}_$index" }) { index, item ->
                            CatalogCard(item, modifier = Modifier.riseIn(index, fromScale = 0.9f, cascadeOnScroll = true), onClick = {
                                if (item is CatalogItem.Song) {
                                    onPlayAll(listOf(item.track), detail.title)
                                } else {
                                    onOpen(item.stableId)
                                }
                            })
                        }
                    }
                }
            }
        }

        CollectionChrome(
            detail = detail,
            scrimProgress = scrimProgress,
            onBack = onBack,
            onSave = onSave,
            isSaved = isSaved,
            bestMix = bestMix,
            smartCrossfadeEnabled = smartCrossfadeEnabled,
            downloadedTrackIds = downloadedTrackIds,
            onDownloadTracks = onDownloadTracks,
            onRemoveDownloadTracks = onRemoveDownloadTracks,
            onShare = onShareCollection,
            isSearching = isSearching,
            searchQuery = searchQuery,
            onSearch = { isSearching = true },
            onSearchQueryChange = { searchQuery = it },
            onCloseSearch = {
                isSearching = false
                searchQuery = ""
            },
        )
    }
}

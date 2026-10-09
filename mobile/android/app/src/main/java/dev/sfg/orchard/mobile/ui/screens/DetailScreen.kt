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

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import dev.sfg.orchard.mobile.ui.motion.riseIn
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sfg.orchard.mobile.model.BrowseDetail
import dev.sfg.orchard.mobile.model.CatalogKind
import dev.sfg.orchard.mobile.model.LoadState
import dev.sfg.orchard.mobile.model.Track
import dev.sfg.orchard.mobile.model.CatalogItem
import dev.sfg.orchard.mobile.ui.components.ArtistSectionBottomSheet
import dev.sfg.orchard.mobile.ui.components.CatalogCard
import dev.sfg.orchard.mobile.ui.components.CategoryCard
import dev.sfg.orchard.mobile.ui.components.DetailBackButton
import dev.sfg.orchard.mobile.ui.components.MessagePanel
import dev.sfg.orchard.mobile.ui.components.OrchardSectionHeader
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
    onAddToPlaylist: ((Track) -> Unit)? = null,
    onRemoveFromPlaylist: ((Track) -> Unit)? = null,
    onMovePlaylistTrack: ((Int, Int) -> Unit)? = null,
    /** Present for a playlist kept on this phone: songs, cover, rename and delete. */
    localEdit: LocalPlaylistEdit? = null,
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
    smartCrossfadeEnabled: Boolean = false,
    onPlayBestMix: ((List<Track>, String, (String) -> Unit, () -> Unit) -> Unit)? = null,
    isRefreshing: Boolean = false,
    onRefresh: () -> Unit = {},
) {
    // Keyed on the state's kind so a refresh does not crossfade the page into itself.
    AnimatedContent(
        targetState = state,
        contentKey = { it::class },
        transitionSpec = {
            (fadeIn(tween(320, delayMillis = 60)) + scaleIn(tween(380, delayMillis = 60), initialScale = 0.97f)) togetherWith
                fadeOut(tween(140))
        },
        label = "DetailState",
    ) { state ->
    when (state) {
        LoadState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = LocalAccent.current, modifier = Modifier.riseIn(fromScale = 0.6f))
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
            val detail = state.value
            PullToRefreshBox(
                isRefreshing = isRefreshing,
                onRefresh = onRefresh,
                modifier = Modifier.fillMaxSize(),
            ) {
                if (detail.kind == CatalogKind.ARTIST) {
                    ArtistDetailContent(
                    detail = detail,
                    onBack = onBack,
                    onPlayAll = onPlayAll,
                    onShuffle = onShuffle,
                    onPlayTrack = onPlayTrack,
                    onPlayNext = onPlayNext,
                    shuffleAvailable = shuffleAvailable,
                    onAdd = onAddToQueue,
                    onSave = onSave,
                    isSaved = isSaved,
                    onOpen = onOpenDetail,
                    downloadedTrackIds = downloadedTrackIds,
                    downloadingTrackIds = downloadingTrackIds,
                    onDownloadTrack = onDownloadTrack,
                    onRemoveDownloadTrack = onRemoveDownloadTrack,
                    onShareTrack = onShareTrack,
                    onShareCollection = onShareCollection,
                    onFetchSectionItems = onFetchSectionItems,
                )
                } else if (detail.tracks.isEmpty() && (detail.sections.isNotEmpty() || detail.related.isNotEmpty())) {
                    HubDetailContent(
                    detail = detail,
                    onBack = onBack,
                    onOpen = onOpenDetail,
                    onPlayTrack = onPlayTrack,
                    onFetchSectionItems = onFetchSectionItems,
                )
                } else {
                    CollectionDetailContent(
                    detail = detail,
                    onBack = onBack,
                    onPlayAll = onPlayAll,
                    onShuffle = onShuffle,
                    onPlayTrack = onPlayTrack,
                    onPlayNext = onPlayNext,
                    shuffleAvailable = shuffleAvailable,
                    onAdd = onAddToQueue,
                    onAddToPlaylist = onAddToPlaylist,
                    onRemoveFromPlaylist = onRemoveFromPlaylist,
                    onMovePlaylistTrack = onMovePlaylistTrack,
                    localEdit = localEdit,
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
                    smartCrossfadeEnabled = smartCrossfadeEnabled,
                    onPlayBestMix = onPlayBestMix,
                    onFetchSectionItems = onFetchSectionItems,
                    )
                }
            }
        }
        LoadState.Idle -> Unit
    }
    }
}

/** Canopy mobile layout for explore/hub pages such as Moods & genres, Charts, New releases, and genre categories. */
@Composable
private fun HubDetailContent(
    detail: BrowseDetail,
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
    onPlayTrack: (List<Track>, Int, String) -> Unit,
    onFetchSectionItems: (suspend (String, String) -> List<CatalogItem>)? = null,
) {
    var activeSectionSheet by remember { mutableStateOf<SectionSheetState?>(null) }

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

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 128.dp),
    ) {
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(top = 16.dp, bottom = 8.dp),
            ) {
                DetailBackButton(onBack)
                Spacer(Modifier.height(12.dp))
                Text(
                    text = detail.title,
                    style = MaterialTheme.typography.displaySmall.copy(
                        fontWeight = FontWeight.Bold,
                        letterSpacing = (-0.5).sp,
                    ),
                    color = CanopyColors.Text,
                )
                if (detail.description.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = detail.description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = CanopyColors.Muted,
                    )
                }
            }
        }

        if (detail.sections.isNotEmpty()) {
            detail.sections.forEach { section ->
                val allCategories = section.items.all { it is CatalogItem.Category }
                val hasMoreViaApi = section.browseId.isNotBlank()
                val canViewAll = !allCategories && (hasMoreViaApi || section.items.size > 3)

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

                if (allCategories) {
                    val pairs = section.items.filterIsInstance<CatalogItem.Category>().chunked(2)
                    items(pairs) { rowItems ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            rowItems.forEach { catItem ->
                                CategoryCard(
                                    item = catItem,
                                    onClick = { onOpen(catItem.stableId) },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            if (rowItems.size == 1) {
                                Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                    item { Spacer(Modifier.height(10.dp)) }
                } else {
                    item {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            itemsIndexed(section.items, key = { index, it -> "${it.stableId}_$index" }) { _, item ->
                                CatalogCard(item, onClick = {
                                    if (item is CatalogItem.Song) {
                                        onPlayTrack(listOf(item.track), 0, section.title)
                                    } else {
                                        onOpen(item.stableId)
                                    }
                                })
                            }
                        }
                    }
                    item { Spacer(Modifier.height(12.dp)) }
                }
            }
        } else if (detail.related.isNotEmpty()) {
            val canViewAll = detail.related.size > 3
            item {
                OrchardSectionHeader(
                    title = "Explore",
                    action = if (canViewAll) "View all" else null,
                    onAction = if (canViewAll) {
                        {
                            activeSectionSheet = SectionSheetState(
                                title = "Explore",
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
                    itemsIndexed(detail.related, key = { index, it -> "${it.stableId}_$index" }) { _, item ->
                        CatalogCard(item, onClick = {
                            if (item is CatalogItem.Song) {
                                onPlayTrack(listOf(item.track), 0, detail.title)
                            } else {
                                onOpen(item.stableId)
                            }
                        })
                    }
                }
            }
        }
    }
}

/** A pending section sheet: title, initial preview items, and (optionally) the API browse key. */
internal data class SectionSheetState(
    val title: String,
    val initialItems: List<CatalogItem>,
    val browseId: String = "",
    val params: String = "",
)

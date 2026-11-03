/*
 * Copyright (C) 2026 SFG545
 * Copyright (C) 2026 Convx Project contributors
 *
 * This file is part of Orchard.
 *
 * Layout adapted from the artist header and sections in ArtistScreen
 * (app/src/main/kotlin/com/convx/music/ui/screens/artist/ArtistScreen.kt) in
 * Convx v1.5.2, https://github.com/cosmictaserdev-creator/Convx, licensed under the
 * GNU General Public License version 3. It is combined with Orchard under section 13
 * of the GNU GPL v3 and GNU AGPL v3.
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

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import dev.sfg.orchard.mobile.ui.scroll.OrchardLazyColumn as LazyColumn
import dev.sfg.orchard.mobile.ui.scroll.OrchardLazyRow as LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import dev.sfg.orchard.mobile.model.BrowseDetail
import dev.sfg.orchard.mobile.model.CatalogItem
import dev.sfg.orchard.mobile.model.CatalogSection
import dev.sfg.orchard.mobile.model.Track
import dev.sfg.orchard.mobile.ui.motion.riseIn
import dev.sfg.orchard.mobile.ui.components.ArtistAnchor
import dev.sfg.orchard.mobile.ui.components.ArtistCollapsedHeader
import dev.sfg.orchard.mobile.ui.components.ArtistHero
import dev.sfg.orchard.mobile.ui.components.ArtworkPalette
import dev.sfg.orchard.mobile.ui.components.HeroAbout
import dev.sfg.orchard.mobile.ui.components.hasRichDescription
import dev.sfg.orchard.mobile.ui.components.ArtistSectionBottomSheet
import dev.sfg.orchard.mobile.ui.components.CatalogCard
import dev.sfg.orchard.mobile.ui.components.ChromeAction
import dev.sfg.orchard.mobile.ui.components.DetailFloatingChrome
import dev.sfg.orchard.mobile.ui.components.FeaturedReleaseCard
import dev.sfg.orchard.mobile.ui.components.TrackRow
import dev.sfg.orchard.mobile.ui.components.rememberArtworkPalette
import dev.sfg.orchard.mobile.ui.components.rememberHeroScrimProgress
import dev.sfg.orchard.mobile.ui.theme.CanopyColors
import kotlinx.coroutines.launch

/** Rows shown under "Top songs" before it is expanded. */
private const val PopularPreviewCount = 5

/** One LazyColumn entry, laid out up front so the header's chips know each section's index. */
private sealed interface ArtistRow {
    val key: String
    data object Hero : ArtistRow { override val key = "hero" }
    data class Latest(val record: CatalogItem.Record) : ArtistRow { override val key = "latest" }
    data class Title(
        val title: String,
        val onClick: (() -> Unit)?,
        val actionLabel: String? = null,
        val id: String = title,
    ) : ArtistRow {
        override val key = "title_$id"
    }
    data class Song(val index: Int, val track: Track, val last: Boolean) : ArtistRow {
        override val key = "${track.id}_$index"
    }
    data class Rail(val title: String, val items: List<CatalogItem>, val id: String) : ArtistRow { override val key = "rail_$id" }
    data object About : ArtistRow { override val key = "about" }
}

/** Artist page: faded portrait hero, featured release, top songs, discography rails, then About. */
@Composable
internal fun ArtistDetailContent(
    detail: BrowseDetail,
    onBack: () -> Unit,
    onPlayAll: (List<Track>, String) -> Unit,
    onShuffle: (List<Track>, String) -> Unit,
    onPlayTrack: (List<Track>, Int, String) -> Unit,
    onPlayNext: ((Track) -> Unit)?,
    shuffleAvailable: Boolean,
    onAdd: ((Track) -> Unit)?,
    onSave: (BrowseDetail) -> Unit,
    isSaved: Boolean,
    onOpen: (String) -> Unit,
    downloadedTrackIds: Set<String> = emptySet(),
    downloadingTrackIds: Set<String> = emptySet(),
    onDownloadTrack: ((Track) -> Unit)? = null,
    onRemoveDownloadTrack: ((String) -> Unit)? = null,
    onShareTrack: ((Track) -> Unit)? = null,
    onShareCollection: ((BrowseDetail) -> Unit)? = null,
    onFetchSectionItems: (suspend (String, String) -> List<CatalogItem>)? = null,
) {
    val palette = rememberArtworkPalette(detail.artworkUrl)
    val listState = rememberLazyListState()
    val scrimProgress = rememberHeroScrimProgress(listState)
    val scope = rememberCoroutineScope()
    var activeSectionSheet by remember { mutableStateOf<SectionSheetState?>(null) }
    var showAllPopularTracks by remember { mutableStateOf(false) }

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

    fun openSheet(title: String, items: List<CatalogItem>, browseId: String = "", params: String = "") {
        activeSectionSheet = SectionSheetState(title, items, browseId, params)
    }

    val rows = buildList {
        add(ArtistRow.Hero)
        detail.sections.firstNotNullOfOrNull { section ->
            section.items.firstOrNull { it is CatalogItem.Record } as? CatalogItem.Record
        }?.let { add(ArtistRow.Latest(it)) }
        if (detail.tracks.isNotEmpty()) {
            val hasMore = detail.tracks.size > PopularPreviewCount
            val shown = if (showAllPopularTracks || !hasMore) detail.tracks else detail.tracks.take(PopularPreviewCount)
            add(ArtistRow.Title(
                "Top songs",
                if (hasMore) {{ showAllPopularTracks = !showAllPopularTracks }} else null,
                if (showAllPopularTracks) "Show less" else null,
            ))
            shown.forEachIndexed { index, track -> add(ArtistRow.Song(index, track, index == shown.lastIndex)) }
        }
        val sections = detail.sections.map { it.title to it }.ifEmpty {
            if (detail.related.isEmpty()) emptyList()
            else listOf("Fans also like" to CatalogSection(id = "related", title = "Fans also like", items = detail.related))
        }
        // The bio reads best after the music, but ahead of the similar-artists rail that closes the page.
        val similarLast = sections.lastOrNull()?.second?.items?.all { it is CatalogItem.Performer } == true
        sections.forEachIndexed { index, (title, section) ->
            if (index == sections.lastIndex && similarLast && detail.description.isNotBlank()) add(ArtistRow.About)
            // "See all" when the API can page further or the rail already overflows.
            val canViewAll = section.browseId.isNotBlank() || section.items.size > 3
            val open = if (canViewAll) {{ openSheet(title, section.items, section.browseId, section.params) }} else null
            add(ArtistRow.Title(title, open, id = "section_$index"))
            add(ArtistRow.Rail(title, section.items, id = "section_$index"))
        }
        if (detail.description.isNotBlank() && none { it is ArtistRow.About }) add(ArtistRow.About)
    }
    val anchors = rows.mapIndexedNotNull { index, row ->
        when (row) {
            is ArtistRow.Title -> ArtistAnchor(row.title, index)
            ArtistRow.About -> ArtistAnchor("About", index)
            else -> null
        }
    }
    val collapsed by remember(listState) { derivedStateOf { listState.firstVisibleItemIndex > 0 } }
    val currentAnchor by remember(listState, anchors) {
        derivedStateOf { anchors.lastOrNull { it.index <= listState.firstVisibleItemIndex + 1 } }
    }
    var headerHeight by remember { mutableIntStateOf(0) }

    Box(
        Modifier
            .fillMaxSize()
            .background(
                // Portrait tint holds through the hero, then eases into the app chrome.
                Brush.verticalGradient(
                    0f to palette.top.copy(alpha = 0.6f),
                    0.4f to palette.deep,
                    1f to CanopyColors.Chrome,
                ),
            ),
    ) {
        LazyColumn(modifier = Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = 128.dp)) {
            items(rows, key = { it.key }) { row ->
                when (row) {
                    ArtistRow.Hero -> ArtistHero(
                        detail = detail,
                        palette = palette,
                        onPlayAll = onPlayAll,
                        onShuffle = onShuffle,
                        shuffleAvailable = shuffleAvailable,
                        onSave = onSave,
                        isSaved = isSaved,
                    )
                    is ArtistRow.Latest -> FeaturedReleaseCard(item = row.record, onClick = { onOpen(row.record.stableId) })
                    is ArtistRow.Title -> HomeSectionTitle(title = row.title, onClick = row.onClick, actionLabel = row.actionLabel)
                    is ArtistRow.Song -> TrackRow(
                        track = row.track,
                        onPlay = { onPlayTrack(detail.tracks, row.index, detail.title) },
                        modifier = Modifier
                            .animateItem(fadeInSpec = null)
                            .padding(horizontal = 8.dp)
                            .riseIn(row.index, cascadeOnScroll = true),
                        showDivider = !row.last,
                        onPlayNext = onPlayNext?.let { action -> { action(row.track) } },
                        onAddToQueue = onAdd?.let { action -> { action(row.track) } },
                        onDownload = onDownloadTrack?.let { action -> { action(row.track) } },
                        onRemoveDownload = onRemoveDownloadTrack?.let { action -> { action(row.track.id) } },
                        isDownloaded = row.track.id in downloadedTrackIds,
                        isDownloading = row.track.id in downloadingTrackIds,
                        onShare = onShareTrack?.let { action -> { action(row.track) } },
                        onViewAlbum = if (row.track.albumId.isNotBlank()) {{ onOpen(row.track.albumId) }} else null,
                        onViewArtist = if (row.track.artistId.isNotBlank()) {{ onOpen(row.track.artistId) }} else null,
                        compact = true,
                    )
                    is ArtistRow.Rail -> CatalogRail(row.items, row.title, onPlayTrack, onOpen)
                    ArtistRow.About -> ArtistAboutCard(detail, palette, onOpen)
                }
            }
        }

        if (!collapsed) {
            DetailFloatingChrome(onBack = onBack, scrimProgress = scrimProgress, transparent = true) {
                if (onShareCollection != null) {
                    ChromeAction(Icons.Rounded.IosShare, "Share artist", { onShareCollection(detail) })
                }
            }
        }
        AnimatedVisibility(visible = collapsed, enter = fadeIn(), exit = fadeOut()) {
            ArtistCollapsedHeader(
                detail = detail,
                palette = palette,
                anchors = anchors,
                current = currentAnchor,
                onBack = onBack,
                onPlay = if (detail.tracks.isNotEmpty()) {{ onPlayAll(detail.tracks, detail.title) }} else null,
                onJump = { anchor ->
                    scope.launch { listState.animateScrollToItem(anchor.index, -headerHeight) }
                },
                modifier = Modifier.onSizeChanged { headerHeight = it.height },
            )
        }
    }
}

/** The bio in a tinted card, four lines until it is opened. */
@Composable
private fun ArtistAboutCard(detail: BrowseDetail, palette: ArtworkPalette, onOpen: (String) -> Unit) {
    HeroAbout(
        title = "About",
        text = detail.description,
        collapsedLines = 4,
        html = detail.hasRichDescription(),
        onLink = onOpen,
        modifier = Modifier
            .padding(start = 20.dp, end = 20.dp, top = 28.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(Brush.verticalGradient(listOf(palette.top.copy(alpha = 0.35f), CanopyColors.Surface)))
            .padding(vertical = 20.dp),
    )
}

/** Horizontal card rail; songs play in place, everything else opens its page. */
@Composable
private fun CatalogRail(
    items: List<CatalogItem>,
    source: String,
    onPlayTrack: (List<Track>, Int, String) -> Unit,
    onOpen: (String) -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        itemsIndexed(items, key = { index, it -> "${it.stableId}_$index" }) { index, item ->
            CatalogCard(item, modifier = Modifier.riseIn(index, fromScale = 0.9f, cascadeOnScroll = true), onClick = {
                if (item is CatalogItem.Song) onPlayTrack(listOf(item.track), 0, source)
                else onOpen(item.stableId)
            })
        }
    }
}

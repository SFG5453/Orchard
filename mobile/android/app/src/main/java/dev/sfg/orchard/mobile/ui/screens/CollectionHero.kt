/*
 * Copyright (C) 2026 SFG545
 * Copyright (C) 2026 Convx Project contributors
 *
 * This file is part of Orchard.
 *
 * Layout adapted from the album header in AlbumScreen
 * (app/src/main/kotlin/com/convx/music/ui/screens/AlbumScreen.kt) and the playlist
 * header in OnlinePlaylistScreen
 * (app/src/main/kotlin/com/convx/music/ui/screens/playlist/OnlinePlaylistScreen.kt) in
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

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sfg.orchard.mobile.model.LocalQobuzAlbumQuality
import dev.sfg.orchard.mobile.model.QobuzAlbumQuality
import dev.sfg.orchard.mobile.ui.components.QobuzAlbumQualityChip
import dev.sfg.orchard.mobile.model.BrowseDetail
import dev.sfg.orchard.mobile.model.CatalogKind
import dev.sfg.orchard.mobile.model.Track
import dev.sfg.orchard.mobile.ui.components.AnimatedArtworkVideo
import dev.sfg.orchard.mobile.ui.components.ArtworkPalette
import dev.sfg.orchard.mobile.ui.components.ArtworkTile
import dev.sfg.orchard.mobile.ui.components.CollectionTopBar
import dev.sfg.orchard.mobile.ui.components.DetailHeroFrame
import dev.sfg.orchard.mobile.ui.components.ExplicitBadge
import dev.sfg.orchard.mobile.ui.components.HeroAbout
import dev.sfg.orchard.mobile.ui.components.HeroCircleButton
import dev.sfg.orchard.mobile.ui.components.HeroGutter
import dev.sfg.orchard.mobile.ui.components.HeroPlayRow
import dev.sfg.orchard.mobile.ui.components.collectionDownloadAction
import dev.sfg.orchard.mobile.ui.components.heroInfoLine
import dev.sfg.orchard.mobile.ui.components.rememberArtworkPalette
import dev.sfg.orchard.mobile.ui.foldable.isFoldableOrWideLayout
import dev.sfg.orchard.mobile.ui.theme.CanopyColors
import dev.sfg.orchard.mobile.ui.theme.LocalAccent
import dev.sfg.orchard.mobile.ui.theme.legibleOnDarkChrome

private fun BrowseDetail.supportsBestMix(enabled: Boolean) =
    enabled && (kind == CatalogKind.PLAYLIST || kind == CatalogKind.ALBUM) && tracks.size > 1

/**
 * Album and playlist hero: faded full-bleed cover on phones (a shadowed card on wide
 * layouts), centred title, artist chip, info line, then shuffle / play / save.
 */
@Composable
internal fun CollectionHero(
    detail: BrowseDetail,
    palette: ArtworkPalette,
    shuffleAvailable: Boolean,
    onPlayAll: (List<Track>, String) -> Unit,
    onShuffle: (List<Track>, String) -> Unit,
    onSave: (BrowseDetail) -> Unit,
    bestMix: BestMixLauncher,
    isSaved: Boolean = false,
    animatedArtworkUrl: String = "",
    artistPortraitUrl: String = "",
    onOpenArtist: (() -> Unit)? = null,
    smartCrossfadeEnabled: Boolean = false,
) {
    val isAlbum = detail.kind == CatalogKind.ALBUM
    val albumAccent = remember(palette.accent) { palette.accent.legibleOnDarkChrome() }
    // The artist's name takes a colour from their own photograph, so it reads as theirs.
    val artistPalette = rememberArtworkPalette(artistPortraitUrl)
    val artistAccent = remember(artistPalette.accent, artistPortraitUrl, albumAccent) {
        if (artistPortraitUrl.isBlank()) albumAccent else artistPalette.accent.legibleOnDarkChrome()
    }
    val artistName = remember(detail) { collectionArtistName(detail) }
    val infoLine = remember(detail) {
        val kind = collectionKindLabel(detail)
        // Some release types already carry the year ("Single • 2021").
        val year = if (kind.contains(detail.year)) "" else detail.year
        heroInfoLine(kind, year, detail.tracks.size, detail.tracks.sumOf { it.durationMs })
    }

    val body: @Composable ColumnScope.() -> Unit = {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.padding(horizontal = HeroGutter),
        ) {
            Text(
                text = detail.title,
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 30.sp,
                    lineHeight = 34.sp,
                    letterSpacing = (-0.5).sp,
                ),
                color = CanopyColors.Text,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (detail.tracks.any { it.explicit }) {
                Spacer(Modifier.width(8.dp))
                ExplicitBadge()
            }
        }
        if (artistName.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            ArtistChip(artistName, artistPortraitUrl, artistAccent, onOpenArtist)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = infoLine,
            style = MaterialTheme.typography.bodyMedium,
            color = CanopyColors.Text.copy(alpha = 0.7f),
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = HeroGutter),
        )
        // Only while MAX is on, as on desktop; the lookup is cached per album.
        val loadAlbumQuality = LocalQobuzAlbumQuality.current
        val albumQuality by produceState<QobuzAlbumQuality?>(null, detail.id, detail.tracks.size, loadAlbumQuality) {
            value = if (isAlbum && detail.tracks.isNotEmpty()) loadAlbumQuality?.invoke(detail) else null
        }
        albumQuality?.let {
            Spacer(Modifier.height(8.dp))
            QobuzAlbumQualityChip(it)
        }

        Spacer(Modifier.height(24.dp))
        HeroPlayRow(
            onPlay = { onPlayAll(detail.tracks, detail.title) },
            playEnabled = detail.tracks.isNotEmpty(),
            // Albums keep their cover's colour on the disc; playlists stay neutral.
            playFill = if (isAlbum) albumAccent else Color.White,
            playIconTint = palette.deep,
            leading = {
                HeroCircleButton(
                    onClick = { onShuffle(detail.tracks, detail.title) },
                    icon = Icons.Rounded.Shuffle,
                    contentDescription = "Shuffle",
                    enabled = detail.tracks.isNotEmpty() && shuffleAvailable,
                )
            },
            trailing = {
                // A playlist on this phone is already in the library; there is nothing to add.
                if (!dev.sfg.orchard.mobile.local.isLocalPlaylistId(detail.id)) {
                    HeroCircleButton(
                        onClick = { onSave(detail) },
                        icon = if (isSaved) Icons.Rounded.Check else Icons.Rounded.Add,
                        contentDescription = if (isSaved) "Saved to library" else "Add to library",
                        tint = if (isSaved) LocalAccent.current else CanopyColors.Text,
                    )
                }
            },
        )

        if (detail.supportsBestMix(smartCrossfadeEnabled)) {
            Spacer(Modifier.height(12.dp))
            BestMixButton(isSorting = bestMix.isSorting, statusText = bestMix.statusText, onClick = bestMix::trigger)
        }

        if (detail.description.isNotBlank()) {
            Spacer(Modifier.height(20.dp))
            HeroAbout(
                title = if (isAlbum) "About this album" else "About this playlist",
                text = detail.description,
            )
        }
    }

    if (isFoldableOrWideLayout()) {
        Column(
            modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(top = 72.dp, bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // A full-bleed square would fill the whole fold, so wide layouts get a card.
            Box(
                modifier = Modifier
                    .size(if (isAlbum) 450.dp else 300.dp)
                    .shadow(28.dp, RoundedCornerShape(16.dp), spotColor = Color.Black.copy(alpha = 0.7f))
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFF1E1E1E)),
            ) {
                ArtworkTile(detail.artworkUrl, "Artwork for ${detail.title}", Modifier.fillMaxSize(), radius = 16)
                if (animatedArtworkUrl.isNotBlank()) {
                    AnimatedArtworkVideo(url = animatedArtworkUrl, active = true, modifier = Modifier.fillMaxSize())
                }
            }
            Spacer(Modifier.height(24.dp))
            body()
        }
    } else {
        DetailHeroFrame(
            artworkUrl = detail.artworkUrl,
            description = "Artwork for ${detail.title}",
            animatedArtworkUrl = animatedArtworkUrl,
            content = body,
        )
    }

    if (bestMix.showPrompt) {
        BestMixDownloadPrompt(
            trackCount = bestMix.undownloadedCount,
            estimatedMb = bestMix.estimatedMb,
            onConfirm = {
                bestMix.showPrompt = false
                bestMix.run()
            },
            onDismiss = { bestMix.showPrompt = false },
        )
    }
}

/** Avatar plus name; opens the artist when the album names one. */
@Composable
private fun ArtistChip(name: String, portraitUrl: String, accent: Color, onClick: (() -> Unit)?) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(CircleShape)
            .clickable(enabled = onClick != null) { onClick?.invoke() }
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        if (portraitUrl.isNotBlank()) {
            ArtworkTile(portraitUrl, name, Modifier.size(28.dp).clip(CircleShape), radius = 14)
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text = name,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            color = accent,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Floating chrome for album and playlist pages, drawn over the list. */
@Composable
internal fun CollectionChrome(
    detail: BrowseDetail,
    scrimProgress: State<Float>,
    onBack: () -> Unit,
    onSave: (BrowseDetail) -> Unit,
    isSaved: Boolean,
    bestMix: BestMixLauncher,
    smartCrossfadeEnabled: Boolean,
    downloadedTrackIds: Set<String>,
    onDownloadTracks: ((List<Track>) -> Unit)?,
    onRemoveDownloadTracks: ((List<Track>) -> Unit)?,
    onShare: ((BrowseDetail) -> Unit)?,
    isSearching: Boolean,
    searchQuery: String,
    onSearch: () -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onCloseSearch: () -> Unit,
) {
    val context = LocalContext.current
    val noun = if (detail.kind == CatalogKind.ALBUM) "album" else "playlist"
    CollectionTopBar(
        onBack = onBack,
        onShare = { shareCollection(context, detail, onShare) },
        onSave = { onSave(detail) },
        isSaved = isSaved,
        scrimProgress = scrimProgress,
        // Inline About covers the description, so the menu entry is not repeated.
        onBestMix = if (detail.supportsBestMix(smartCrossfadeEnabled)) bestMix::trigger else null,
        onSearch = onSearch,
        isSearching = isSearching,
        searchQuery = searchQuery,
        onSearchQueryChange = onSearchQueryChange,
        onCloseSearch = onCloseSearch,
        searchPlaceholder = "Find in $noun",
        aboutLabel = "About this $noun",
        onDownload = collectionDownloadAction(detail.tracks, downloadedTrackIds, onDownloadTracks, onRemoveDownloadTracks),
        isDownloaded = detail.tracks.isNotEmpty() && detail.tracks.all { it.id in downloadedTrackIds },
    )
}

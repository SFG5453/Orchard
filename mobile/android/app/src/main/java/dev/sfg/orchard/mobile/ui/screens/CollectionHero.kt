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

import android.content.Intent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sfg.orchard.mobile.ui.theme.CanopyColors
import kotlin.math.roundToInt
import dev.sfg.orchard.mobile.auth.SupabaseSyncService
import dev.sfg.orchard.mobile.model.BrowseDetail
import dev.sfg.orchard.mobile.model.CatalogKind
import dev.sfg.orchard.mobile.model.Track
import dev.sfg.orchard.mobile.playback.smart.BestMixSorter
import kotlinx.coroutines.launch
import dev.sfg.orchard.mobile.ui.components.AlbumEditorialReview
import dev.sfg.orchard.mobile.ui.components.AnimatedArtworkVideo
import dev.sfg.orchard.mobile.ui.components.ArtworkPalette
import dev.sfg.orchard.mobile.ui.components.ArtworkTile
import dev.sfg.orchard.mobile.ui.components.CollectionActionRow
import dev.sfg.orchard.mobile.ui.components.CollectionTopBar
import dev.sfg.orchard.mobile.ui.components.ExplicitBadge
import dev.sfg.orchard.mobile.ui.components.rememberArtworkPalette
import dev.sfg.orchard.mobile.ui.theme.LocalAccent
import dev.sfg.orchard.mobile.ui.theme.legibleOnDarkChrome

/**
 * Collection hero:
 * Top frosted navigation bar, centered rounded artwork card with motion/video support,
 * bold centered typography, action row (Shuffle circle, White Play pill, Add circle),
 * and editorial review summary.
 */
@Composable
fun CollectionHero(
    detail: BrowseDetail,
    palette: ArtworkPalette,
    shuffleAvailable: Boolean,
    onBack: () -> Unit,
    onPlayAll: (List<Track>, String) -> Unit,
    onShuffle: (List<Track>, String) -> Unit,
    onSave: (BrowseDetail) -> Unit,
    onAbout: () -> Unit,
    isSaved: Boolean = false,
    downloadedTrackIds: Set<String> = emptySet(),
    downloadingTrackIds: Set<String> = emptySet(),
    onDownloadTracks: ((List<Track>) -> Unit)? = null,
    onRemoveDownloadTracks: ((List<Track>) -> Unit)? = null,
    animatedArtworkUrl: String = "",
    artistPortraitUrl: String = "",
    onShare: ((BrowseDetail) -> Unit)? = null,
    smartCrossfadeEnabled: Boolean = false,
    bestMixSupabaseSync: Boolean = false,
    onPlayBestMix: ((List<Track>, String, (String) -> Unit, () -> Unit) -> Unit)? = null,
    onSearch: (() -> Unit)? = null,
    isSearching: Boolean = false,
    searchQuery: String = "",
    onSearchQueryChange: ((String) -> Unit)? = null,
    onCloseSearch: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val syncService = remember { SupabaseSyncService(context) }

    var isSorting by remember { mutableStateOf(false) }
    var sortStatusText by remember { mutableStateOf("") }
    var showDownloadPrompt by remember { mutableStateOf(false) }

    val undownloadedTracks = remember(detail.tracks, downloadedTrackIds) {
        detail.tracks.filter { it.id !in downloadedTrackIds }
    }
    val undownloadedDurationMs = remember(undownloadedTracks) {
        undownloadedTracks.sumOf { if (it.durationMs > 0) it.durationMs else 210_000L }
    }
    val estimatedMb = remember(undownloadedDurationMs) {
        (undownloadedDurationMs / 1000.0 * 20.0 / 1024.0).roundToInt().coerceAtLeast(1)
    }

    fun startBestMixExecution() {
        if (isSorting) return
        isSorting = true
        sortStatusText = "Preparing Best Mix..."
        if (onPlayBestMix != null) {
            onPlayBestMix(
                detail.tracks,
                detail.title,
                { status -> sortStatusText = status },
                {
                    isSorting = false
                    sortStatusText = ""
                },
            )
        } else {
            scope.launch {
                try {
                    sortStatusText = "Sorting Best Mix..."
                    val features = syncService.fetchTrackFeatures(detail.tracks.map { it.id })
                    val sorted = BestMixSorter.sort(detail.tracks, features)
                    onPlayAll(sorted, detail.title)
                } finally {
                    isSorting = false
                    sortStatusText = ""
                }
            }
        }
    }

    fun triggerBestMix() {
        if (isSorting) return
        if (!bestMixSupabaseSync && undownloadedTracks.isNotEmpty()) {
            showDownloadPrompt = true
        } else {
            startBestMixExecution()
        }
    }

    val isAlbum = detail.kind == CatalogKind.ALBUM
    val albumAccent = remember(palette.accent) { palette.accent.legibleOnDarkChrome() }
    // The artist's name takes a colour from their own photograph, so it reads as theirs rather
    // than the record's. Falls back to the cover's accent when no portrait resolved.
    val artistPalette = rememberArtworkPalette(artistPortraitUrl)
    val artistAccent = remember(artistPalette.accent, artistPortraitUrl, albumAccent) {
        if (artistPortraitUrl.isBlank()) albumAccent else artistPalette.accent.legibleOnDarkChrome()
    }

    val artistName = remember(detail) {
        if (detail.kind == CatalogKind.PLAYLIST) {
            detail.artist.takeIf { it.isNotBlank() && !it.equals("YouTube Music", ignoreCase = true) }
                ?: detail.tracks.map { it.artist }.filter { it.isNotBlank() && !it.equals("Unknown artist", true) }
                    .groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
                ?: detail.artist.ifBlank { "YouTube Music" }
        } else {
            detail.artist.ifBlank {
                detail.tracks.firstOrNull { it.artist.isNotBlank() }?.artist.orEmpty()
            }
        }
    }

    val subtitleText = remember(detail) {
        val parts = mutableListOf<String>()
        if (detail.kind == CatalogKind.PLAYLIST) {
            parts.add("PLAYLIST")
            val songCount = detail.tracks.size
            if (songCount > 0) {
                parts.add("$songCount ${if (songCount == 1) "SONG" else "SONGS"}")
            }
        } else {
            val cleanSubtitle = detail.subtitle.trim()
            val isVisibilityOnly = cleanSubtitle.equals("unlisted", true) ||
                cleanSubtitle.equals("public", true) ||
                cleanSubtitle.equals("private", true)

            if (cleanSubtitle.isNotBlank() && !isVisibilityOnly) {
                parts.add(cleanSubtitle.uppercase())
            } else if (detail.kind == CatalogKind.ALBUM) {
                parts.add("ALBUM")
            } else {
                parts.add("PLAYLIST")
            }
            if (detail.year.isNotBlank() && parts.none { it.contains(detail.year) }) {
                parts.add(detail.year)
            }
        }
        parts.joinToString(" • ")
    }

    fun shareAlbum() {
        if (onShare != null) {
            onShare(detail)
            return
        }
        val shareTarget = if (artistName.isNotBlank()) "${detail.title} by $artistName" else detail.title
        val intent = Intent(Intent.ACTION_SEND).apply {
            putExtra(Intent.EXTRA_TEXT, "Listen to $shareTarget on Orchard")
            type = "text/plain"
        }
        context.startActivity(Intent.createChooser(intent, "Share ${detail.title}"))
    }

    val topBar: @Composable () -> Unit = {
        CollectionTopBar(
            onBack = onBack,
            onShare = { shareAlbum() },
            onSave = { onSave(detail) },
            isSaved = isSaved,
            onAbout = if (detail.description.isNotBlank()) onAbout else null,
            onBestMix = if (smartCrossfadeEnabled && (detail.kind == CatalogKind.PLAYLIST || detail.kind == CatalogKind.ALBUM) && detail.tracks.size > 1) (::triggerBestMix) else null,
            onSearch = onSearch,
            isSearching = isSearching,
            searchQuery = searchQuery,
            onSearchQueryChange = onSearchQueryChange,
            onCloseSearch = onCloseSearch,
            searchPlaceholder = "Find in ${if (detail.kind == CatalogKind.ALBUM) "album" else "playlist"}",
            aboutLabel = "About this ${if (detail.kind == CatalogKind.ALBUM) "album" else "playlist"}",
        )
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {


        // Albums get a full-bleed cover with the titles laid over it; playlists keep the
        // centred card, which suits their mixed artwork better.
        if (detail.kind == CatalogKind.ALBUM) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clipToBounds(),
            ) {
                ArtworkTile(
                    url = detail.artworkUrl,
                    description = "Artwork for ${detail.title}",
                    modifier = Modifier.fillMaxSize(),
                    radius = 0,
                )

                if (animatedArtworkUrl.isNotBlank()) {
                    AnimatedArtworkVideo(
                        url = animatedArtworkUrl,
                        active = true,
                        modifier = Modifier.fillMaxSize(),
                    )
                }

                // The cover dissolves into the page's own artwork tint, so the colour carries
                // straight through instead of hitting a dark band under the image.
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.verticalGradient(
                            0.52f to Color.Transparent,
                            0.80f to palette.deep.copy(alpha = 0.72f),
                            1.00f to palette.deep,
                        ),
                    ),
                )

                Box(modifier = Modifier.align(Alignment.TopCenter)) { topBar() }

                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                        .padding(bottom = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            text = detail.title,
                            style = MaterialTheme.typography.headlineLarge.copy(
                                fontWeight = FontWeight.Bold,
                                letterSpacing = (-0.6).sp,
                            ),
                            color = albumAccent,
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
                        Text(
                            text = artistName,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = artistAccent,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        text = subtitleText,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontWeight = FontWeight.Medium,
                            letterSpacing = 0.4.sp,
                        ),
                        color = Color.White.copy(alpha = 0.60f),
                        textAlign = TextAlign.Center,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
        } else {
            topBar()
            Spacer(Modifier.height(8.dp))
            // Prominent Centered Artwork Card with motion cover support & soft drop shadow
            Box(
                modifier = Modifier
                    .size(260.dp)
                    .shadow(
                        elevation = 28.dp,
                        shape = RoundedCornerShape(16.dp),
                        spotColor = Color.Black.copy(alpha = 0.70f),
                        ambientColor = Color.Black.copy(alpha = 0.40f),
                    )
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFF1E1E1E)),
                contentAlignment = Alignment.Center,
            ) {
                ArtworkTile(
                    url = detail.artworkUrl,
                    description = "Artwork for ${detail.title}",
                    modifier = Modifier.fillMaxSize(),
                    radius = 16,
                )

                if (animatedArtworkUrl.isNotBlank()) {
                    AnimatedArtworkVideo(
                        url = animatedArtworkUrl,
                        active = true,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            // Centered Album Title
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.padding(horizontal = 24.dp),
            ) {
                Text(
                    text = detail.title,
                    style = MaterialTheme.typography.headlineMedium.copy(
                        fontWeight = FontWeight.Bold,
                        letterSpacing = (-0.4).sp,
                    ),
                    color = Color.White,
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

            // Centered Artist Name
            if (artistName.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = artistName,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color = LocalAccent.current,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
            }

            // Centered Metadata Subtitle (e.g. R&B/SOUL • 2022)
            Spacer(Modifier.height(4.dp))
            Text(
                text = subtitleText,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 0.4.sp,
                ),
                color = Color.White.copy(alpha = 0.60f),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 24.dp),
            )

        }

        // Action buttons row: [ Shuffle ]  [ ▶ Play ]  [ Add / Save ]  [ Download ]
        Spacer(Modifier.height(18.dp))
        val allDownloaded = detail.tracks.isNotEmpty() && detail.tracks.all { downloadedTrackIds.contains(it.id) }
        val anyDownloading = detail.tracks.isNotEmpty() && detail.tracks.any { downloadingTrackIds.contains(it.id) }
        val onDownloadAction: (() -> Unit)? = if (onDownloadTracks != null && onRemoveDownloadTracks != null && detail.tracks.isNotEmpty()) {
            {
                if (allDownloaded) {
                    onRemoveDownloadTracks(detail.tracks)
                } else {
                    onDownloadTracks(detail.tracks)
                }
            }
        } else null

        CollectionActionRow(
            accent = if (isAlbum) albumAccent else Color.White,
            onPlay = { onPlayAll(detail.tracks, detail.title) },
            onShuffle = { onShuffle(detail.tracks, detail.title) },
            onSave = { onSave(detail) },
            isSaved = isSaved,
            playEnabled = detail.tracks.isNotEmpty(),
            shuffleEnabled = detail.tracks.isNotEmpty() && shuffleAvailable,
            onDownload = onDownloadAction,
            isDownloaded = allDownloaded,
            isDownloading = anyDownloading,
            downloadEnabled = detail.tracks.isNotEmpty(),
        )

        // Best mix button at top of playlists and albums (gated by smart crossfade)
        if (smartCrossfadeEnabled && (detail.kind == CatalogKind.PLAYLIST || detail.kind == CatalogKind.ALBUM) && detail.tracks.size > 1) {
            val transition = rememberInfiniteTransition(label = "BestMixGlow")
            val borderGlow by transition.animateFloat(
                initialValue = 0.25f,
                targetValue = 0.85f,
                animationSpec = infiniteRepeatable(tween(2200), RepeatMode.Reverse),
                label = "BestMixBorderGlow",
            )
            val sparkleScale by transition.animateFloat(
                initialValue = 0.88f,
                targetValue = 1.18f,
                animationSpec = infiniteRepeatable(tween(1600), RepeatMode.Reverse),
                label = "BestMixSparkleScale",
            )

            Spacer(Modifier.height(10.dp))
            Button(
                onClick = ::triggerBestMix,
                enabled = !isSorting,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.White.copy(alpha = 0.10f),
                    contentColor = Color.White,
                    disabledContainerColor = Color.White.copy(alpha = 0.16f),
                    disabledContentColor = Color.White,
                ),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .height(42.dp)
                    .border(
                        width = 1.dp,
                        brush = Brush.horizontalGradient(
                            listOf(
                                LocalAccent.current.copy(alpha = borderGlow * 0.8f),
                                Color.White.copy(alpha = 0.40f),
                                LocalAccent.current.copy(alpha = borderGlow),
                            ),
                        ),
                        shape = RoundedCornerShape(20.dp),
                    ),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    if (isSorting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            color = LocalAccent.current,
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Icon(
                            Icons.Rounded.AutoAwesome,
                            contentDescription = "Best mix",
                            tint = LocalAccent.current,
                            modifier = Modifier
                                .size(18.dp)
                                .graphicsLayer {
                                    scaleX = sparkleScale
                                    scaleY = sparkleScale
                                },
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = if (isSorting) sortStatusText.ifBlank { "Sorting Best Mix..." } else "Best mix",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp,
                        color = Color.White,
                    )
                }
            }
        }

        if (showDownloadPrompt) {
            AlertDialog(
                onDismissRequest = { showDownloadPrompt = false },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Rounded.AutoAwesome,
                            contentDescription = null,
                            tint = LocalAccent.current,
                            modifier = Modifier.size(24.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Best Mix Offline Analysis", fontWeight = FontWeight.Bold)
                    }
                },
                text = {
                    Column {
                        Text(
                            "Best Mix analyzes harmonic keys, tempo, and cue points locally to arrange your music seamlessly.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.85f),
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "Downloading ${undownloadedTracks.size} song${if (undownloadedTracks.size == 1) "" else "s"} could take up to ~$estimatedMb MB of storage.",
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = LocalAccent.current,
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            showDownloadPrompt = false
                            startBestMixExecution()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = LocalAccent.current),
                    ) {
                        Text("Download & Sort", color = Color.Black, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDownloadPrompt = false }) {
                        Text("Cancel", color = Color.White.copy(alpha = 0.7f))
                    }
                },
                shape = RoundedCornerShape(20.dp),
                containerColor = CanopyColors.Surface,
            )
        }

        // Editorial review snippet with expandable "MORE"
        if (detail.description.isNotBlank()) {
            Spacer(Modifier.height(14.dp))
            AlbumEditorialReview(
                description = detail.description,
                onOpenAbout = onAbout,
            )
        }

        Spacer(Modifier.height(8.dp))
    }
}

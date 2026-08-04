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
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sfg.orchard.mobile.model.BrowseDetail
import dev.sfg.orchard.mobile.model.CatalogKind
import dev.sfg.orchard.mobile.model.Track
import dev.sfg.orchard.mobile.ui.components.AlbumEditorialReview
import dev.sfg.orchard.mobile.ui.components.AnimatedArtworkVideo
import dev.sfg.orchard.mobile.ui.components.CollectionActionRow
import dev.sfg.orchard.mobile.ui.components.ArtworkPalette
import dev.sfg.orchard.mobile.ui.components.ArtworkTile
import dev.sfg.orchard.mobile.ui.theme.CanopyColors
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
    animatedArtworkUrl: String = "",
    artistPortraitUrl: String = "",
    onShare: ((BrowseDetail) -> Unit)? = null,
) {
    val context = LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }

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

    // Shared so albums can float it over the cover while playlists keep it above.
    val navigationBar: @Composable () -> Unit = {
            // Top Navigation Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Back button
                Surface(
                    onClick = onBack,
                    shape = CircleShape,
                    color = Color.White.copy(alpha = 0.16f),
                    modifier = Modifier.size(38.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "Back",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }

                // Top-right action controls: Share & More options
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(
                        onClick = { shareAlbum() },
                        shape = CircleShape,
                        color = Color.White.copy(alpha = 0.16f),
                        modifier = Modifier.size(38.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Rounded.IosShare,
                                contentDescription = "Share",
                                tint = Color.White,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }

                    Box {
                        Surface(
                            onClick = { menuOpen = true },
                            shape = CircleShape,
                            color = Color.White.copy(alpha = 0.16f),
                            modifier = Modifier.size(38.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Rounded.MoreHoriz,
                                    contentDescription = "More options",
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }

                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text(if (isSaved) "Remove from library" else "Add to library") },
                                onClick = {
                                    menuOpen = false
                                    onSave(detail)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Share") },
                                onClick = {
                                    menuOpen = false
                                    shareAlbum()
                                },
                            )
                            if (detail.description.isNotBlank()) {
                                DropdownMenuItem(
                                    text = { Text("About this ${if (detail.kind == CatalogKind.ALBUM) "album" else "playlist"}") },
                                    onClick = {
                                        menuOpen = false
                                        onAbout()
                                    },
                                )
                            }
                        }
                    }
                }
            }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {


        // Albums get a full-bleed cover with the titles laid over it; playlists keep the
        // centred card, which suits their mixed artwork better.
        if (detail.kind == CatalogKind.ALBUM) {
            Box(modifier = Modifier.fillMaxWidth().aspectRatio(1f)) {
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

                Box(modifier = Modifier.align(Alignment.TopCenter)) { navigationBar() }

                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                        .padding(bottom = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
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
                    )
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
            navigationBar()
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
                modifier = Modifier.padding(horizontal = 24.dp),
            )

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

        // Action buttons row: [ Shuffle ]  [ ▶ Play ]  [ Add / Save ]
        Spacer(Modifier.height(18.dp))
        CollectionActionRow(
            accent = if (isAlbum) albumAccent else Color.White,
            onPlay = { onPlayAll(detail.tracks, detail.title) },
            onShuffle = { onShuffle(detail.tracks, detail.title) },
            onSave = { onSave(detail) },
            isSaved = isSaved,
            playEnabled = detail.tracks.isNotEmpty(),
            shuffleEnabled = detail.tracks.isNotEmpty() && shuffleAvailable,
        )

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

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

package dev.sfg.orchard.mobile.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.interaction.MutableInteractionSource
import dev.sfg.orchard.mobile.model.Track
import dev.sfg.orchard.mobile.ui.motion.PlayingBars
import dev.sfg.orchard.mobile.ui.motion.pressScale
import dev.sfg.orchard.mobile.ui.theme.LocalAccent

/** Badge for explicit songs. */
@Composable
fun ExplicitBadge(modifier: Modifier = Modifier) {
    Surface(
        color = Color.White.copy(alpha = 0.16f),
        shape = RoundedCornerShape(3.dp),
        modifier = modifier,
    ) {
        Text(
            text = "E",
            color = Color.White.copy(alpha = 0.85f),
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.sp,
            ),
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
        )
    }
}

/** Expressive track list row with rounded art or track numbers, active row highlight, and popup actions. */
@Composable
fun TrackRow(
    track: Track,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier,
    trackNumber: Int? = null,
    showArtwork: Boolean = true,
    parentArtist: String = "",
    /** Off on album and playlist pages, whose rows show the artist only. */
    showAlbum: Boolean = true,
    showDivider: Boolean = false,
    onPlayNext: (() -> Unit)? = null,
    onAddToQueue: (() -> Unit)? = null,
    onAddToPlaylist: (() -> Unit)? = null,
    onRemoveFromPlaylist: (() -> Unit)? = null,
    onMoveUp: (() -> Unit)? = null,
    onMoveDown: (() -> Unit)? = null,
    onShare: (() -> Unit)? = null,
    onDownload: (() -> Unit)? = null,
    onRemoveDownload: (() -> Unit)? = null,
    isDownloaded: Boolean = false,
    isDownloading: Boolean = false,
    onViewAlbum: (() -> Unit)? = null,
    onViewArtist: (() -> Unit)? = null,
    trailingText: String = durationText(track.durationMs),
    highlighted: Boolean = false,
    compact: Boolean = false,
) {
    var popupOpen by remember { mutableStateOf(false) }
    val bgColor by animateColorAsState(
        if (highlighted) LocalAccent.current.copy(alpha = 0.15f) else Color.Transparent,
        label = "TrackRowBg"
    )
    val artworkSize = if (compact) 40.dp else 46.dp
    val verticalPad = if (compact) 6.dp else 10.dp
    val titleStyle = if (compact) MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        else MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
    val subtitleStyle = if (compact) MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp)
        else MaterialTheme.typography.bodySmall

    if (popupOpen) {
        TrackActionsPopup(
            track = track,
            onDismiss = { popupOpen = false },
            onPlay = onPlay,
            onPlayNext = onPlayNext,
            onAddToQueue = onAddToQueue,
            onAddToPlaylist = onAddToPlaylist,
            onRemoveFromPlaylist = onRemoveFromPlaylist,
            onMoveUp = onMoveUp,
            onMoveDown = onMoveDown,
            onDownload = if (!isDownloaded) onDownload else null,
            onRemoveDownload = if (isDownloaded) onRemoveDownload else null,
            onShare = onShare,
            onViewAlbum = onViewAlbum,
            onViewArtist = onViewArtist,
        )
    }

    val pressSource = remember { MutableInteractionSource() }
    Column(modifier = modifier.fillMaxWidth()) {
        Surface(
            onClick = onPlay,
            color = bgColor,
            shape = RoundedCornerShape(if (compact) 8.dp else 10.dp),
            interactionSource = pressSource,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 1.dp)
                .pressScale(pressSource, 0.97f)
        ) {
            Row(
                Modifier.padding(horizontal = 8.dp, vertical = verticalPad),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (showArtwork && trackNumber == null) {
                    Box(contentAlignment = Alignment.Center) {
                        ArtworkTile(track.artworkUrl, "Artwork for ${track.title}", Modifier.size(artworkSize), if (compact) 8 else 10)
                        androidx.compose.animation.AnimatedVisibility(
                            visible = highlighted,
                            enter = fadeIn() + scaleIn(initialScale = 0.6f),
                            exit = fadeOut() + scaleOut(targetScale = 0.6f),
                        ) {
                            Box(
                                Modifier
                                    .size(artworkSize)
                                    .clip(RoundedCornerShape(if (compact) 8.dp else 10.dp))
                                    .background(Color.Black.copy(alpha = 0.45f)),
                                contentAlignment = Alignment.Center
                            ) {
                                PlayingBars(LocalAccent.current, size = 18.dp)
                            }
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                } else {
                    Box(
                        modifier = Modifier.width(32.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        AnimatedContent(
                            targetState = highlighted,
                            transitionSpec = {
                                (fadeIn() + scaleIn(initialScale = 0.5f)) togetherWith
                                    (fadeOut() + scaleOut(targetScale = 0.5f))
                            },
                            label = "TrackNumberToBars",
                        ) { playing ->
                            if (playing) {
                                PlayingBars(LocalAccent.current, size = 14.dp)
                            } else {
                                Text(
                                    text = (trackNumber ?: 1).toString(),
                                    color = Color.White.copy(alpha = 0.45f),
                                    style = MaterialTheme.typography.bodyLarge.copy(
                                        fontWeight = FontWeight.Medium,
                                        fontSize = 15.sp,
                                    ),
                                )
                            }
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            track.title,
                            color = if (highlighted) LocalAccent.current else Color.White,
                            style = titleStyle,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (track.explicit) {
                            ExplicitBadge()
                        }
                    }

                    val displayArtist = track.artist.takeIf {
                        it != "Unknown artist" && it.isNotBlank() && (parentArtist.isBlank() || !it.equals(parentArtist, ignoreCase = true))
                    }
                    val displayAlbum = track.album.takeIf { showAlbum && it.isNotBlank() && trackNumber == null }
                    val subtitle = listOfNotNull(displayArtist, displayAlbum).distinct().joinToString(" • ")

                    if (subtitle.isNotBlank()) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            subtitle,
                            color = Color.White.copy(alpha = 0.60f),
                            style = subtitleStyle,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                // 0 idle, 1 downloading, 2 done; the hand-off from spinner to tick pops in.
                val downloadPhase = if (isDownloading) 1 else if (isDownloaded) 2 else 0
                AnimatedContent(
                    targetState = downloadPhase,
                    transitionSpec = {
                        (fadeIn() + scaleIn(initialScale = 0.3f, animationSpec = androidx.compose.animation.core.spring(0.4f, 600f))) togetherWith
                            (fadeOut() + scaleOut(targetScale = 0.3f))
                    },
                    label = "DownloadPhase",
                ) { phase ->
                    when (phase) {
                        1 -> CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            color = LocalAccent.current,
                            strokeWidth = 2.dp,
                        )
                        2 -> Icon(
                            Icons.Rounded.DownloadDone,
                            contentDescription = "Downloaded offline",
                            tint = LocalAccent.current.copy(alpha = 0.85f),
                            modifier = Modifier.size(16.dp),
                        )
                        else -> Unit
                    }
                }
                if (trailingText.isNotBlank()) {
                    Text(
                        trailingText,
                        color = Color.White.copy(alpha = 0.45f),
                        style = subtitleStyle,
                        modifier = Modifier.padding(horizontal = 6.dp)
                    )
                }
                IconButton(onClick = { popupOpen = true }, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.Rounded.MoreHoriz,
                        contentDescription = "Actions for ${track.title}",
                        tint = Color.White.copy(alpha = 0.55f),
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
        if (showDivider) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = if (showArtwork && trackNumber == null) 72.dp else 48.dp, end = 16.dp)
                    .height(0.5.dp)
                    .background(Color.White.copy(alpha = 0.08f))
            )
        }
    }
}


fun durationText(durationMs: Long): String {
    if (durationMs <= 0) return ""
    val seconds = durationMs / 1_000
    return "%d:%02d".format(seconds / 60, seconds % 60)
}

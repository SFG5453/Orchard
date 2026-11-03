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
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MoreHoriz
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sfg.orchard.mobile.model.PlaybackSnapshot
import dev.sfg.orchard.mobile.ui.components.TrackActionsPopup
import dev.sfg.orchard.mobile.ui.glass.GlassTone
import dev.sfg.orchard.mobile.ui.glass.glassPane
import dev.sfg.orchard.mobile.ui.theme.CanopyColors

@Composable
fun ExplicitBadge(modifier: Modifier = Modifier) {
    Surface(
        color = Color.White.copy(alpha = 0.40f),
        shape = RoundedCornerShape(3.dp),
        modifier = modifier.size(16.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            // Trim font padding so the glyph centers on its cap height.
            Text(
                "E",
                color = Color.Black,
                fontWeight = FontWeight.Black,
                style =
                    TextStyle(
                        fontSize = 10.sp,
                        lineHeight = 10.sp,
                        platformStyle = PlatformTextStyle(includeFontPadding = false),
                        lineHeightStyle =
                            LineHeightStyle(
                                alignment = LineHeightStyle.Alignment.Center,
                                trim = LineHeightStyle.Trim.Both,
                            ),
                    ),
            )
        }
    }
}

/** Animated popping favorite button. */
@Composable
fun AnimatedFavoriteButton(liked: Boolean, onLiked: () -> Unit, modifier: Modifier = Modifier) {
    val heartScale by
        animateFloatAsState(
            targetValue = if (liked) 1.15f else 1.0f,
            animationSpec =
                spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMedium,
                ),
            label = "HeartScale",
        )

    IconButton(
        onClick = onLiked,
        modifier =
            modifier.size(48.dp).graphicsLayer {
                scaleX = heartScale
                scaleY = heartScale
            },
    ) {
        Icon(
            imageVector = if (liked) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
            contentDescription = if (liked) "Remove from liked songs" else "Save to liked songs",
            tint = if (liked) CanopyColors.Favorite else Color.White.copy(alpha = 0.75f),
            modifier = Modifier.size(28.dp),
        )
    }
}

/** Favourite star and overflow button, shared by the full title row and the lyrics header. */
/** Heart and overflow beside the title. The overflow always opens the menu; it never doubles as queue. */
@Composable
fun TrackActionButtons(
    liked: Boolean,
    onLiked: () -> Unit,
    onOpenMenu: (() -> Unit)?,
    glass: Boolean = true,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        AnimatedFavoriteButton(liked = liked, onLiked = onLiked)
        if (onOpenMenu != null) OverflowButton(onClick = onOpenMenu, glass = glass)
    }
}

@Composable
fun OverflowButton(onClick: () -> Unit, modifier: Modifier = Modifier, glass: Boolean = true) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = Color.Transparent,
        modifier = modifier.size(36.dp).then(if (glass) Modifier.glassPane(CircleShape, GlassTone.CONTROL) else Modifier),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Icons.Filled.MoreHoriz,
                contentDescription = "More options",
                tint = Color.White.copy(alpha = 0.85f),
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
fun PlayerTopHandle(onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .clickable(onClick = onDismiss)
                // Generous vertical padding: the visible pill is 5dp tall, far under the 48dp
                // minimum a drag needs to be reliably grabbable.
                .padding(top = 16.dp, bottom = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier =
                Modifier.width(38.dp)
                    .height(5.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.45f))
        )
    }
}

/** Track title, artist subtitle, inline explicit badge, star & more buttons. */
@Composable
fun TrackInfoRow(
    track: dev.sfg.orchard.mobile.model.Track,
    liked: Boolean,
    onLiked: () -> Unit,
    modifier: Modifier = Modifier,
    onMore: (() -> Unit)? = null,
    onAddToPlaylist: (() -> Unit)? = null,
    onShare: (() -> Unit)? = null,
    // Null when the track carries no album or artist id to open.
    onOpenAlbum: (() -> Unit)? = null,
    onOpenArtist: (() -> Unit)? = null,
    isDownloaded: Boolean = false,
    onDownload: (() -> Unit)? = null,
    onRemoveDownload: (() -> Unit)? = null,
    /** Non-null when the track has a music video to switch to. */
    onWatchVideo: (() -> Unit)? = null,
    /** Off where the player header already carries the overflow menu. */
    showMenu: Boolean = true,
    glass: Boolean = true,
) {
    var menuOpen by remember { mutableStateOf(false) }

    if (menuOpen) {
        TrackActionsPopup(
            track = track,
            onDismiss = { menuOpen = false },
            onViewQueue = onMore,
            onAddToPlaylist = onAddToPlaylist,
            onDownload = if (!isDownloaded) onDownload else null,
            onRemoveDownload = if (isDownloaded) onRemoveDownload else null,
            onShare = onShare,
            onWatchVideo = onWatchVideo,
            onViewArtist = onOpenArtist,
            onViewAlbum = onOpenAlbum,
        )
    }

    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        // Track Title & Artist with smooth transition animation when song changes
        androidx.compose.animation.AnimatedContent(
            targetState = track,
            transitionSpec = {
                (androidx.compose.animation.fadeIn(
                        androidx.compose.animation.core.tween(400, delayMillis = 60)
                    ) +
                        androidx.compose.animation.slideInVertically(
                            androidx.compose.animation.core.tween(400, delayMillis = 60)
                        ) {
                            it / 3
                        })
                    .togetherWith(
                        androidx.compose.animation.fadeOut(
                            androidx.compose.animation.core.tween(200)
                        ) +
                            androidx.compose.animation.slideOutVertically(
                                androidx.compose.animation.core.tween(200)
                            ) {
                                -it / 3
                            }
                    )
            },
            label = "TrackInfoTransition",
            modifier = Modifier.weight(1f).padding(end = 12.dp),
        ) { currentTrack ->
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = currentTrack.title,
                        style =
                            MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 22.sp,
                            ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = Color.White,
                        modifier =
                            Modifier.weight(1f, fill = false)
                                .then(
                                    if (onOpenAlbum != null) {
                                        Modifier.clickable(onClick = onOpenAlbum)
                                    } else {
                                        Modifier
                                    }
                                )
                                .basicMarquee(initialDelayMillis = 4000, repeatDelayMillis = 3000),
                    )
                    dev.sfg.orchard.mobile.ui.components.SlopBadge(currentTrack.id)
                    if (currentTrack.explicit) {
                        Spacer(Modifier.width(6.dp))
                        ExplicitBadge()
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    text = currentTrack.artist,
                    color = Color.White.copy(alpha = 0.65f),
                    style =
                        MaterialTheme.typography.bodyLarge.copy(
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium,
                        ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier =
                        Modifier.then(
                                if (onOpenArtist != null) {
                                    Modifier.clickable(onClick = onOpenArtist)
                                } else {
                                    Modifier
                                }
                            )
                            .basicMarquee(initialDelayMillis = 4000, repeatDelayMillis = 3000),
                )
            }
        }

        TrackActionButtons(
            liked = liked,
            onLiked = onLiked,
            onOpenMenu = if (showMenu) ({ menuOpen = true }) else null,
            glass = glass,
        )
    }
}

fun PlaybackSnapshot.playingFromLabel(): String {
    val engagement =
        Regex("\\b(?:plays?|views?|listeners?|subscribers?)\\b", RegexOption.IGNORE_CASE)
    return contextTitle.takeIf { it.isNotBlank() && !engagement.containsMatchIn(it) }
        ?: currentTrack?.album?.takeIf { it.isNotBlank() && !engagement.containsMatchIn(it) }
        ?: "Your Queue"
}

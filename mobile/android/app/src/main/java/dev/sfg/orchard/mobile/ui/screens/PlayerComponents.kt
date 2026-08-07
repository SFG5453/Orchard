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

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import dev.sfg.orchard.mobile.audio.AudioOutputType
import dev.sfg.orchard.mobile.audio.canReadBluetoothNames
import dev.sfg.orchard.mobile.audio.rememberAudioOutput
import dev.sfg.orchard.mobile.ui.components.TrackActionsPopup
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
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarOutline
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.Earbuds
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Headset
import androidx.compose.material.icons.rounded.Hearing
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Speaker
import androidx.compose.material.icons.rounded.TabletAndroid
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material.icons.rounded.Usb
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sfg.orchard.mobile.model.DeviceType
import dev.sfg.orchard.mobile.model.PlaybackSnapshot
import dev.sfg.orchard.mobile.model.PlaybackTarget
import dev.sfg.orchard.mobile.model.PlaybackTargetState
import dev.sfg.orchard.mobile.ui.theme.CanopyColors

@Composable
fun ExplicitBadge(modifier: Modifier = Modifier) {
    Surface(
        color = Color.White.copy(alpha = 0.40f),
        shape = RoundedCornerShape(3.dp),
        modifier = modifier.size(16.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text("E", color = Color.Black, fontSize = 10.sp, fontWeight = FontWeight.Black)
        }
    }
}

/** Animated popping favorite button. */
@Composable
fun AnimatedFavoriteButton(
    liked: Boolean,
    onLiked: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val heartScale by animateFloatAsState(
        targetValue = if (liked) 1.15f else 1.0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "HeartScale",
    )

    IconButton(
        onClick = onLiked,
        modifier = modifier
            .size(48.dp)
            .graphicsLayer {
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
@Composable
fun TrackActionButtons(
    liked: Boolean,
    onLiked: () -> Unit,
    onMore: (() -> Unit)?,
    onShare: (() -> Unit)?,
    onOpenMenu: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Surface(
            onClick = onLiked,
            shape = CircleShape,
            color = Color.White.copy(alpha = 0.12f),
            modifier = Modifier.size(36.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = if (liked) Icons.Filled.Star else Icons.Filled.StarOutline,
                    contentDescription = if (liked) "Favorited" else "Favorite",
                    tint = if (liked) Color.White else Color.White.copy(alpha = 0.85f),
                    modifier = Modifier.size(20.dp),
                )
            }
        }

        Surface(
            onClick = { if (onShare != null) onOpenMenu() else onMore?.invoke() },
            shape = CircleShape,
            color = Color.White.copy(alpha = 0.12f),
            modifier = Modifier.size(36.dp),
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
}

/**
 * Top pill drag handle.
 *
 * The caller supplies the drag gesture through [modifier]; the tap remains as a fallback so the
 * handle still works for anyone who cannot complete a drag.
 */
@Composable
fun PlayerTopHandle(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onDismiss)
            // Generous vertical padding: the visible pill is 5dp tall, far under the 48dp
            // minimum a drag needs to be reliably grabbable.
            .padding(top = 16.dp, bottom = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .width(38.dp)
                .height(5.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.45f)),
        )
    }
}

/** Track title, artist subtitle, inline explicit badge, star & more buttons. */
@Composable
fun TrackInfoRow(
    track: dev.sfg.orchard.mobile.model.Track,
    liked: Boolean,
    onLiked: () -> Unit,
    onMore: (() -> Unit)? = null,
    onAddToPlaylist: (() -> Unit)? = null,
    onShare: (() -> Unit)? = null,
    // Null when the track carries no album or artist id to open.
    onOpenAlbum: (() -> Unit)? = null,
    onOpenArtist: (() -> Unit)? = null,
    isDownloaded: Boolean = false,
    onDownload: (() -> Unit)? = null,
    onRemoveDownload: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
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
        )
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        // Track Title & Artist with smooth transition animation when song changes
        androidx.compose.animation.AnimatedContent(
            targetState = track,
            transitionSpec = {
                (androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(400, delayMillis = 60)) +
                    androidx.compose.animation.slideInVertically(androidx.compose.animation.core.tween(400, delayMillis = 60)) { it / 3 })
                    .togetherWith(
                        androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(200)) +
                            androidx.compose.animation.slideOutVertically(androidx.compose.animation.core.tween(200)) { -it / 3 },
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
                        style = MaterialTheme.typography.headlineSmall.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 21.sp,
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = Color.White,
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .then(
                                if (onOpenAlbum != null) {
                                    Modifier.clickable(onClick = onOpenAlbum)
                                } else {
                                    Modifier
                                },
                            )
                            .basicMarquee(initialDelayMillis = 4000, repeatDelayMillis = 3000),
                    )
                    if (currentTrack.explicit) {
                        Spacer(Modifier.width(6.dp))
                        ExplicitBadge()
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    text = currentTrack.artist,
                    color = Color.White.copy(alpha = 0.65f),
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .then(
                            if (onOpenArtist != null) {
                                Modifier.clickable(onClick = onOpenArtist)
                            } else {
                                Modifier
                            },
                        )
                        .basicMarquee(initialDelayMillis = 4000, repeatDelayMillis = 3000),
                )
            }
        }

        TrackActionButtons(
            liked = liked,
            onLiked = onLiked,
            onMore = onMore,
            onShare = onShare,
            onOpenMenu = { menuOpen = true },
        )
    }
}


/** Bottom quick destinations bar with active device route, lyrics, and queue with badge count. */
@Composable
fun PlayerBottomDestinations(
    targets: PlaybackTargetState,
    upcomingCount: Int,
    onLyrics: () -> Unit,
    lyricsActive: Boolean = false,
    onDevices: () -> Unit,
    onQueue: () -> Unit,
    queueActive: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Lyrics Action Button
        IconButton(
            onClick = onLyrics,
            modifier = Modifier.size(44.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.Subtitles,
                contentDescription = if (lyricsActive) "Hide lyrics" else "Lyrics",
                tint = if (lyricsActive) Color.White else Color.White.copy(alpha = 0.75f),
                modifier = Modifier.size(24.dp),
            )
        }

        // Output Route Device Pill (Headphones / AirPods / Phone / Cast)
        OutputRoutePill(targets = targets, onClick = onDevices)

        // Queue Action Button with upcoming badge
        IconButton(
            onClick = onQueue,
            modifier = Modifier.size(44.dp),
        ) {
            BadgedBox(
                badge = {
                    if (upcomingCount > 0) {
                        Badge(
                            containerColor = Color.White.copy(alpha = 0.25f),
                            contentColor = Color.White,
                        ) {
                            Text(
                                text = if (upcomingCount > 99) "99+" else upcomingCount.toString(),
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                },
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.List,
                    contentDescription = if (queueActive) "Hide queue" else "Queue",
                    tint = if (queueActive) Color.White else Color.White.copy(alpha = 0.75f),
                    modifier = Modifier.size(24.dp),
                )
            }
        }
    }
}

@Composable
private fun OutputRoutePill(
    targets: PlaybackTargetState,
    onClick: () -> Unit,
) {
    val isLocal = targets.selected is PlaybackTarget.LocalPhone
    val output by rememberAudioOutput()

    // Local playback follows the phone's real output route; remote playback names the Connect device.
    val activeDeviceName = if (isLocal) {
        output.name
    } else {
        targets.devices.firstOrNull { it.isActive }?.name ?: "Connected Device"
    }
    val tablet = LocalConfiguration.current.smallestScreenWidthDp >= 600
    val routeIcon = if (isLocal) output.type.icon(tablet) else Icons.Rounded.Cast

    // Asked for on tap rather than at launch: the user has just expressed interest in output
    // devices, so it is the one moment a Bluetooth prompt explains itself.
    val context = LocalContext.current
    val nameAccess = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* Denied just means the pill keeps showing the generic route label. */ }
    val needsNameAccess = isLocal && output.type.isBluetooth && !context.canReadBluetoothNames()

    Row(
        modifier = Modifier
            .clip(CircleShape)
            .clickable {
                if (needsNameAccess) {
                    nameAccess.launch(Manifest.permission.BLUETOOTH_CONNECT)
                }
                onClick()
            }
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = routeIcon,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.85f),
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = activeDeviceName,
            style = MaterialTheme.typography.labelMedium.copy(
                fontWeight = FontWeight.Medium,
                fontSize = 13.sp,
            ),
            color = Color.White.copy(alpha = 0.85f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Maps a physical output route to the glyph that reads fastest at 16dp. */
private fun AudioOutputType.icon(tablet: Boolean = false): ImageVector = when (this) {
    AudioOutputType.PHONE_SPEAKER, AudioOutputType.EARPIECE ->
        if (tablet) Icons.Rounded.TabletAndroid else Icons.Rounded.PhoneAndroid
    AudioOutputType.WIRED_HEADPHONES -> Icons.Rounded.Headphones
    AudioOutputType.BLUETOOTH_HEADPHONES -> Icons.Rounded.Headphones
    AudioOutputType.BLUETOOTH_EARBUDS -> Icons.Rounded.Earbuds
    AudioOutputType.BLUETOOTH_SPEAKER -> Icons.Rounded.Speaker
    AudioOutputType.CAR -> Icons.Rounded.DirectionsCar
    AudioOutputType.USB -> Icons.Rounded.Usb
    AudioOutputType.HDMI -> Icons.Rounded.Tv
    AudioOutputType.HEARING_AID -> Icons.Rounded.Hearing
    AudioOutputType.UNKNOWN -> Icons.Rounded.Headset
}

fun PlaybackSnapshot.playingFromLabel(): String {
    val engagement = Regex("\\b(?:plays?|views?|listeners?|subscribers?)\\b", RegexOption.IGNORE_CASE)
    return contextTitle.takeIf { it.isNotBlank() && !engagement.containsMatchIn(it) }
        ?: currentTrack?.album?.takeIf { it.isNotBlank() && !engagement.containsMatchIn(it) }
        ?: "Your Queue"
}

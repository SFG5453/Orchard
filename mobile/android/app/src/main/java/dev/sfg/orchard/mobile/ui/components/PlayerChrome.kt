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

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sfg.orchard.mobile.model.PlaybackSnapshot
import dev.sfg.orchard.mobile.ui.navigation.Routes
import dev.sfg.orchard.mobile.ui.theme.CanopyColors
import dev.sfg.orchard.mobile.ui.theme.LocalAccent
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

data class BottomDestination(val route: String, val label: String, val icon: ImageVector)

private val destinations = listOf(
    BottomDestination(Routes.HOME, "Home", Icons.Rounded.Home),
    BottomDestination(Routes.SEARCH, "Search", Icons.Rounded.Search),
    BottomDestination(Routes.LIBRARY, "Library", Icons.Rounded.LibraryMusic),
    BottomDestination(Routes.SETTINGS, "Profile", Icons.Rounded.Person),
)

/**
 * Height the mini player and nav bar occupy together. Screens reserve this much bottom content
 * padding so their last row can still be scrolled clear of the floating chrome.
 */
val OrchardChromeHeight = 132.dp

/**
 * Bottom navigation bar. Content still scrolls behind it rather than stopping above it, but a
 * scrim fades in underneath so labels never sit directly on album art. The gradient starts fully
 * transparent at the top of the bar, which keeps the floating look while the ramp does the work
 * of separating the two layers.
 */
@Composable
fun OrchardBottomBar(currentRoute: String?, onSelect: (String) -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    0f to Color.Transparent,
                    0.22f to CanopyColors.Chrome.copy(alpha = 0.86f),
                    1f to CanopyColors.Chrome.copy(alpha = 0.97f),
                )
            )
    ) {
        NavigationBar(
            containerColor = Color.Transparent,
            tonalElevation = 0.dp,
            // The Scaffold already insets the whole chrome column above the system navigation bar.
            // Letting the bar apply that inset a second time would eat into its fixed 64dp height,
            // which squashed the icons and labels under 3-button navigation.
            windowInsets = WindowInsets(0),
            modifier = Modifier.height(64.dp)
        ) {
                destinations.forEach { destination ->
                    val isSelected = currentRoute == destination.route
                    NavigationBarItem(
                        selected = isSelected,
                        onClick = { onSelect(destination.route) },
                        icon = {
                            Icon(
                                destination.icon,
                                contentDescription = destination.label,
                                modifier = Modifier.size(22.dp),
                            )
                        },
                        label = {
                            Text(
                                destination.label,
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            )
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = LocalAccent.current,
                            selectedTextColor = LocalAccent.current,
                            indicatorColor = LocalAccent.current.copy(alpha = 0.15f),
                            unselectedIconColor = CanopyColors.Muted,
                            unselectedTextColor = CanopyColors.Muted,
                        ),
                    )
            }
        }
    }
}

/** Expressive floating mini-player component matching SimpMusic design. */
@Composable
fun MiniPlayer(
    playback: PlaybackSnapshot,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    transition: dev.sfg.orchard.mobile.model.TransitionMarker? = null,
    /** Reports the thumbnail's place on screen so the full player can fly its cover into it. */
    onArtworkBounds: ((Rect) -> Unit)? = null,
) {
    val track = playback.currentTrack ?: return
    var offsetX by remember { mutableFloatStateOf(0f) }
    val coroutineScope = rememberCoroutineScope()

    val marker = transition?.takeIf {
        it.trackId.isNotBlank() && it.trackId == track.id && it.startMs > 0 && it.startMs < playback.durationMs
    }
    val effectiveDuration = (marker?.startMs ?: playback.durationMs).coerceAtLeast(1)

    val progress = if (effectiveDuration > 0) {
        (playback.positionMs.toFloat() / effectiveDuration.toFloat()).coerceIn(0f, 1f)
    } else 0f

    val animatedProgress by animateFloatAsState(targetValue = progress, label = "MiniPlayerProgress")
    // The pill picks up the cover's colours so it reads as part of the artwork.
    val palette = rememberArtworkPalette(track.artworkUrl)

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = CanopyColors.Surface.copy(alpha = 0.96f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp)
                .background(
                    Brush.horizontalGradient(
                        listOf(
                            lerp(palette.accent, Color.White, 0.18f),
                            lerp(palette.bottom, Color.White, 0.06f),
                        ),
                    ),
                ),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .offset { IntOffset(offsetX.roundToInt(), 0) }
                    .padding(horizontal = 10.dp)
                    .clickable(onClick = onClick),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AnimatedContent(
                    targetState = track.artworkUrl to track.title,
                    transitionSpec = {
                        fadeIn(animationSpec = tween(350)) togetherWith fadeOut(animationSpec = tween(250))
                    },
                    label = "MiniPlayerArtworkCrossfade",
                ) { (artUrl, artTitle) ->
                    ArtworkTile(
                        url = artUrl,
                        description = artTitle,
                        modifier = Modifier
                            .size(44.dp)
                            .onGloballyPositioned { onArtworkBounds?.invoke(it.boundsInRoot()) },
                        radius = 10
                    )
                }
                Spacer(Modifier.width(10.dp))
                AnimatedContent(
                    targetState = track.title to track.artist,
                    transitionSpec = {
                        (fadeIn(animationSpec = tween(350, delayMillis = 40)) + slideInVertically(animationSpec = tween(350, delayMillis = 40)) { it / 3 }) togetherWith
                            (fadeOut(animationSpec = tween(200)) + slideOutVertically(animationSpec = tween(200)) { -it / 3 })
                    },
                    label = "MiniPlayerTrackTransition",
                    modifier = Modifier.weight(1f),
                ) { (title, artist) ->
                    Column {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                            color = CanopyColors.Text,
                            maxLines = 1,
                            modifier = Modifier.basicMarquee()
                        )
                        Text(
                            text = artist,
                            style = MaterialTheme.typography.bodySmall,
                            color = CanopyColors.Muted,
                            maxLines = 1
                        )
                    }
                }
                Spacer(Modifier.width(6.dp))
                val buttonColor = lerp(palette.accent, Color.White, 0.62f)
                IconButton(
                    onClick = onTogglePlay,
                    modifier = Modifier
                        .size(40.dp)
                        .background(buttonColor, CircleShape)
                ) {
                    Icon(
                        imageVector = if (playback.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = if (playback.isPlaying) "Pause" else "Play",
                        // Keep the glyph readable whatever hue the cover produced.
                        tint = if (buttonColor.luminance() > 0.5f) Color.Black else Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            // Bottom progress bar, which picks up the same rainbow as the full player
            // while a mix is running so the pill tells the same story in miniature.
            val glow = rememberTransitionGlow(transitionProgress(playback, marker))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter),
            ) {
                LinearProgressIndicator(
                    progress = { animatedProgress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp),
                    color = LocalAccent.current,
                    trackColor = Color.Transparent,
                    strokeCap = StrokeCap.Round,
                )
                if (glow > 0.01f) {
                    Box(
                        Modifier
                            .fillMaxWidth(animatedProgress)
                            .height(3.dp)
                            .alpha(glow)
                            .background(rememberRainbowBrush()),
                    )
                }
            }
        }
    }
}

@Composable
fun PlaybackTargetLabel(name: String, isLocal: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        color = CanopyColors.Surface,
        shape = CircleShape,
        border = androidx.compose.foundation.BorderStroke(1.dp, CanopyColors.Rule),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(8.dp)
                    .background(
                        if (isLocal) CanopyColors.Muted else LocalAccent.current,
                        CircleShape,
                    ),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                name,
                style = MaterialTheme.typography.labelSmall,
                color = if (isLocal) CanopyColors.Text else LocalAccent.current,
            )
        }
    }
}

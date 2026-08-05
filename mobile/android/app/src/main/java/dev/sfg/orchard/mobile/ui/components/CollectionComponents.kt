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

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sfg.orchard.mobile.ui.theme.CanopyColors
import dev.sfg.orchard.mobile.ui.theme.LocalAccent

/**
 * Collection action buttons row:
 * [ Circular Shuffle ]  [ Wide White Play Pill ]  [ Circular Add/Favorite ]
 */
@Composable
fun CollectionActionRow(
    // Defaults to white so playlists keep the neutral pill; albums pass their cover's colour.
    accent: Color = Color.White,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    onSave: () -> Unit,
    isSaved: Boolean,
    playEnabled: Boolean = true,
    shuffleEnabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Shuffle button (frosted glass circle)
        Surface(
            onClick = onShuffle,
            enabled = shuffleEnabled,
            shape = CircleShape,
            color = Color.White.copy(alpha = 0.14f),
            modifier = Modifier.size(46.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Rounded.Shuffle,
                    contentDescription = "Shuffle",
                    tint = if (shuffleEnabled) Color.White else Color.White.copy(alpha = 0.35f),
                    modifier = Modifier.size(20.dp),
                )
            }
        }

        // Center prominent Play pill button
        Button(
            onClick = onPlay,
            enabled = playEnabled,
            colors = ButtonDefaults.buttonColors(
                containerColor = accent,
                contentColor = Color.Black,
                disabledContainerColor = accent.copy(alpha = 0.30f),
                disabledContentColor = Color.Black.copy(alpha = 0.40f),
            ),
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier
                .weight(1f)
                .height(46.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Icon(
                    Icons.Rounded.PlayArrow,
                    contentDescription = null,
                    tint = Color.Black,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "Play",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = Color.Black,
                )
            }
        }

        // Add / Save button (frosted glass circle)
        Surface(
            onClick = onSave,
            shape = CircleShape,
            color = Color.White.copy(alpha = 0.14f),
            modifier = Modifier.size(46.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    if (isSaved) Icons.Rounded.Check else Icons.Rounded.Add,
                    contentDescription = if (isSaved) "Saved to library" else "Add to library",
                    tint = if (isSaved) LocalAccent.current else Color.White,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }
}

/**
 * Editorial review / description preview with inline "MORE".
 */
@Composable
fun AlbumEditorialReview(
    description: String,
    onOpenAbout: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (description.isBlank()) return

    val cleanText = description.replace(Regex("\\s+"), " ").trim()
    val previewText = if (cleanText.length > 140) cleanText.take(140).trimEnd() + "…" else cleanText

    val annotated = buildAnnotatedString {
        append(previewText)
        append(" ")
        withStyle(
            SpanStyle(
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
            )
        ) {
            append("MORE")
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onOpenAbout)
            .padding(horizontal = 24.dp, vertical = 6.dp)
    ) {
        Text(
            text = annotated,
            style = MaterialTheme.typography.bodyMedium.copy(
                lineHeight = 18.sp,
                fontSize = 13.sp,
            ),
            color = Color.White.copy(alpha = 0.72f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun CollectionTopBar(
    onBack: () -> Unit,
    onShare: () -> Unit,
    onSave: () -> Unit,
    isSaved: Boolean,
    onAbout: (() -> Unit)? = null,
    onBestMix: (() -> Unit)? = null,
    aboutLabel: String = "About",
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
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

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                onClick = onShare,
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
                            onSave()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Share") },
                        onClick = {
                            menuOpen = false
                            onShare()
                        },
                    )
                    if (onAbout != null) {
                        DropdownMenuItem(
                            text = { Text(aboutLabel) },
                            onClick = {
                                menuOpen = false
                                onAbout()
                            },
                        )
                    }
                    if (onBestMix != null) {
                        DropdownMenuItem(
                            text = { Text("Play with Best Mix") },
                            onClick = {
                                menuOpen = false
                                onBestMix()
                            },
                        )
                    }
                }
            }
        }
    }
}

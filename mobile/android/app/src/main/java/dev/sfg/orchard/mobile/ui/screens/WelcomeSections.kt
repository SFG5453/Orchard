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

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Gradient
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.MultipleStop
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import dev.sfg.orchard.mobile.auth.AuthState
import dev.sfg.orchard.mobile.model.AudioQuality
import dev.sfg.orchard.mobile.model.OrchardSettings
import dev.sfg.orchard.mobile.ui.theme.CanopyColors
import dev.sfg.orchard.mobile.ui.theme.LocalAccent
import kotlin.math.roundToInt

@Composable
internal fun WelcomeHeroHeader() {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            LocalAccent.current.copy(alpha = 0.35f),
                            LocalAccent.current.copy(alpha = 0.08f),
                        ),
                    ),
                )
                .border(1.5.dp, LocalAccent.current.copy(alpha = 0.4f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Rounded.MusicNote,
                contentDescription = null,
                tint = LocalAccent.current,
                modifier = Modifier.size(38.dp),
            )
        }

        Spacer(Modifier.height(16.dp))

        Text(
            text = "Welcome to Orchard",
            style = MaterialTheme.typography.headlineLarge.copy(
                fontWeight = FontWeight.ExtraBold,
                fontSize = 28.sp,
            ),
            color = CanopyColors.Text,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(6.dp))

        Text(
            text = "Personalize your sound, visuals, and music experience",
            style = MaterialTheme.typography.bodyLarge,
            color = CanopyColors.Muted,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }
}

@Composable
internal fun WelcomeAuthSection(
    auth: AuthState,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
) {
    WelcomeSectionCard(
        title = "YouTube Music Account",
        subtitle = "Connect to sync your library, likes, and recommendations",
    ) {
        val isSignedIn = auth is AuthState.SignedIn
        val avatarUrl = (auth as? AuthState.SignedIn)?.avatarUrl.orEmpty()
        val displayName = (auth as? AuthState.SignedIn)?.displayName.orEmpty().ifBlank { "YouTube Music" }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(LocalAccent.current.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center,
            ) {
                if (avatarUrl.isNotBlank()) {
                    AsyncImage(
                        model = avatarUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Icon(
                        if (isSignedIn) Icons.Rounded.CheckCircle else Icons.Rounded.Person,
                        contentDescription = null,
                        tint = LocalAccent.current,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }

            Spacer(Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (isSignedIn) displayName else "Not Connected",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = CanopyColors.Text,
                )
                Text(
                    text = if (isSignedIn) "Signed in and ready to stream" else "Guest mode active",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isSignedIn) LocalAccent.current else CanopyColors.Muted,
                )
            }
        }

        Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            if (isSignedIn) {
                OutlinedButton(
                    onClick = onSignOut,
                    shape = CircleShape,
                    modifier = Modifier.fillMaxWidth().height(44.dp),
                ) {
                    Text("Sign out / Switch account", color = CanopyColors.Danger)
                }
            } else {
                Button(
                    onClick = onSignIn,
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = LocalAccent.current,
                        contentColor = Color.Black,
                    ),
                    modifier = Modifier.fillMaxWidth().height(46.dp),
                ) {
                    Text("Sign in with YouTube Music", fontWeight = FontWeight.Bold)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
internal fun WelcomeVisualsSection(
    settings: OrchardSettings,
    onUpdateSettings: (OrchardSettings) -> Unit,
) {
    WelcomeSectionCard(
        title = "Visuals & Appearance",
        subtitle = "Customize ambient lighting and animated artwork",
    ) {
        WelcomeToggleRow(
            icon = Icons.Rounded.Gradient,
            title = "Animated Background",
            subtitle = "Drifting cover colors behind the app",
            checked = settings.animatedBackground,
            onChecked = { onUpdateSettings(settings.copy(animatedBackground = it)) },
        )
        WelcomeDivider()
        WelcomeToggleRow(
            icon = Icons.Rounded.Image,
            title = "Animated Artwork",
            subtitle = "Play dynamic motion artwork for supported tracks",
            checked = settings.animatedArtwork,
            onChecked = { onUpdateSettings(settings.copy(animatedArtwork = it)) },
        )
        WelcomeDivider()
        WelcomeToggleRow(
            icon = Icons.Rounded.Palette,
            title = "System Colours",
            subtitle = "Match accent colors to your device wallpaper",
            checked = settings.useSystemColors,
            onChecked = { onUpdateSettings(settings.copy(useSystemColors = it)) },
        )
    }
}

@Composable
internal fun WelcomeCrossfadeSection(
    settings: OrchardSettings,
    onUpdateSettings: (OrchardSettings) -> Unit,
) {
    WelcomeSectionCard(
        title = "Crossfade & Transitions",
        subtitle = "Seamless blending between tracks",
    ) {
        WelcomeToggleRow(
            icon = Icons.Rounded.MultipleStop,
            title = "Crossfade Playback",
            subtitle = if (settings.crossfadeEnabled) "Tracks blend for ${settings.crossfadeSeconds}s" else "Smooth transition between songs",
            checked = settings.crossfadeEnabled,
            onChecked = { onUpdateSettings(settings.copy(crossfadeEnabled = it)) },
        )

        AnimatedVisibility(visible = settings.crossfadeEnabled) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Duration",
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                        color = CanopyColors.Text,
                    )
                    Text(
                        "${settings.crossfadeSeconds} seconds",
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                        color = LocalAccent.current,
                    )
                }

                Slider(
                    value = settings.crossfadeSeconds.toFloat(),
                    onValueChange = { onUpdateSettings(settings.copy(crossfadeSeconds = it.roundToInt())) },
                    valueRange = OrchardSettings.MIN_CROSSFADE_SECONDS.toFloat()..OrchardSettings.MAX_CROSSFADE_SECONDS.toFloat(),
                    steps = OrchardSettings.MAX_CROSSFADE_SECONDS - OrchardSettings.MIN_CROSSFADE_SECONDS - 1,
                    colors = SliderDefaults.colors(
                        thumbColor = LocalAccent.current,
                        activeTrackColor = LocalAccent.current,
                        inactiveTrackColor = CanopyColors.Canvas,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(8.dp))

                Text(
                    "Transition Type",
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                    color = CanopyColors.Text,
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(CanopyColors.Canvas, RoundedCornerShape(12.dp))
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    val smart = settings.smartCrossfade
                    WelcomeOptionChip(
                        title = "Smart Fade",
                        subtitle = "Beat-matched overlap",
                        icon = Icons.Rounded.AutoAwesome,
                        selected = smart,
                        modifier = Modifier.weight(1f),
                        onClick = { onUpdateSettings(settings.copy(smartCrossfade = true)) },
                    )
                    WelcomeOptionChip(
                        title = "Standard",
                        subtitle = "Linear volume ramp",
                        icon = Icons.Rounded.GraphicEq,
                        selected = !smart,
                        modifier = Modifier.weight(1f),
                        onClick = { onUpdateSettings(settings.copy(smartCrossfade = false)) },
                    )
                }
            }
        }
    }
}

@Composable
internal fun WelcomeQualityAndStorageSection(
    settings: OrchardSettings,
    onUpdateSettings: (OrchardSettings) -> Unit,
) {
    WelcomeSectionCard(
        title = "Audio Quality & Cache",
        subtitle = "Streaming fidelity and offline cache limit",
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RowIcon(Icons.Rounded.GraphicEq)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        "Streaming Quality",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = CanopyColors.Text,
                    )
                    Text(
                        settings.audioQuality.name.lowercase().replace('_', ' ').replaceFirstChar(Char::titlecase),
                        style = MaterialTheme.typography.bodySmall,
                        color = LocalAccent.current,
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(CanopyColors.Canvas, CircleShape)
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                AudioQuality.entries.forEach { quality ->
                    val isSelected = quality == settings.audioQuality
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(36.dp)
                            .background(
                                if (isSelected) LocalAccent.current else Color.Transparent,
                                CircleShape,
                            )
                            .clickable { onUpdateSettings(settings.copy(audioQuality = quality)) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = when (quality) {
                                AudioQuality.DATA_SAVER -> "Saver"
                                AudioQuality.NORMAL -> "Normal"
                                AudioQuality.HIGH -> "High"
                                AudioQuality.MAX -> "Max"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            color = if (isSelected) Color.Black else CanopyColors.Muted,
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            WelcomeDivider()
            Spacer(Modifier.height(12.dp))

            val steps = OrchardSettings.CACHE_SIZE_STEPS_MB
            val index = steps.indexOfFirst { it >= settings.cacheSizeMb }.takeIf { it >= 0 } ?: steps.lastIndex

            Row(verticalAlignment = Alignment.CenterVertically) {
                RowIcon(Icons.Rounded.Storage)
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Audio Cache Size",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = CanopyColors.Text,
                    )
                    Text(
                        "Store up to ${formatCacheSize(steps[index])} of played music",
                        style = MaterialTheme.typography.bodySmall,
                        color = CanopyColors.Muted,
                    )
                }
                Text(
                    formatCacheSize(steps[index]),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = LocalAccent.current,
                )
            }

            Slider(
                value = index.toFloat(),
                onValueChange = { onUpdateSettings(settings.copy(cacheSizeMb = steps[it.roundToInt()])) },
                valueRange = 0f..steps.lastIndex.toFloat(),
                steps = steps.size - 2,
                colors = SliderDefaults.colors(
                    thumbColor = LocalAccent.current,
                    activeTrackColor = LocalAccent.current,
                    inactiveTrackColor = CanopyColors.Canvas,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

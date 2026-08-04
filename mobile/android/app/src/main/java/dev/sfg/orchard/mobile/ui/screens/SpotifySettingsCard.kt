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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Animation
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sfg.orchard.mobile.model.OrchardSettings
import dev.sfg.orchard.mobile.spotify.SpotifyCanvasRepository
import dev.sfg.orchard.mobile.ui.theme.CanopyColors
import dev.sfg.orchard.mobile.ui.theme.LocalAccent

private val SpotifyGreen = Color(0xFF1DB954)

@Composable
fun SpotifySettingsCard(
    settings: OrchardSettings,
    onSettings: (OrchardSettings) -> Unit,
    onConnectSpotify: () -> Unit,
) {
    var showSpdcDialog by remember { mutableStateOf(false) }
    val isConnected = settings.spotifySpdc.isNotBlank()

    Surface(
        color = CanopyColors.Surface,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            if (isConnected) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .background(SpotifyGreen.copy(alpha = 0.15f), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "S",
                            color = SpotifyGreen,
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp,
                        )
                    }

                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Connected to Spotify",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = CanopyColors.Text,
                        )
                        Text(
                            "Canvas animated artwork (.mp4) enabled",
                            style = MaterialTheme.typography.bodySmall,
                            color = CanopyColors.Muted,
                        )
                    }

                    StatusBadge("Connected", SpotifyGreen)
                }

                Spacer(Modifier.height(14.dp))
                OutlinedButton(
                    onClick = { onSettings(settings.copy(spotifySpdc = "")) },
                    shape = CircleShape,
                    modifier = Modifier.fillMaxWidth().height(42.dp),
                ) {
                    Text("Disconnect", color = CanopyColors.Danger, fontWeight = FontWeight.SemiBold)
                }
            } else {
                Text(
                    "Spotify Canvas Integration",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = CanopyColors.Text,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Log into Spotify or supply your sp_dc cookie to fetch Spotify Canvas videos when other providers miss.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = CanopyColors.Muted,
                )
                Spacer(Modifier.height(14.dp))
                Button(
                    onClick = onConnectSpotify,
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SpotifyGreen,
                        contentColor = Color.White,
                    ),
                    modifier = Modifier.fillMaxWidth().height(46.dp),
                ) {
                    Text("Log into Spotify", fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { showSpdcDialog = true },
                    shape = CircleShape,
                    modifier = Modifier.fillMaxWidth().height(42.dp),
                ) {
                    Text("Supply sp_dc Cookie", color = CanopyColors.Text, fontWeight = FontWeight.SemiBold)
                }
            }

            Spacer(Modifier.height(16.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(CanopyColors.Rule)
            )
            Spacer(Modifier.height(12.dp))

            SpotifyToggleRow(
                icon = Icons.Rounded.Animation,
                title = "Spotify Canvas integration",
                subtitle = "Fetch animated artwork loops from Spotify",
                checked = settings.spotifyCanvasEnabled,
                onChecked = { onSettings(settings.copy(spotifyCanvasEnabled = it)) },
            )
        }
    }

    if (showSpdcDialog) {
        var input by remember { mutableStateOf(settings.spotifySpdc) }
        AlertDialog(
            onDismissRequest = { showSpdcDialog = false },
            title = { Text("Spotify sp_dc Cookie", color = CanopyColors.Text) },
            text = {
                Column {
                    Text(
                        "Paste your sp_dc cookie value from spotify.com browser session:",
                        style = MaterialTheme.typography.bodySmall,
                        color = CanopyColors.Muted,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        placeholder = { Text("Paste sp_dc cookie here...") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val extracted = SpotifyCanvasRepository.extractSpdc(input)
                    onSettings(settings.copy(spotifySpdc = extracted))
                    showSpdcDialog = false
                }) {
                    Text("Save", color = LocalAccent.current)
                }
            },
            dismissButton = {
                TextButton(onClick = { showSpdcDialog = false }) {
                    Text("Cancel", color = CanopyColors.Muted)
                }
            },
            containerColor = CanopyColors.Surface,
        )
    }
}

@Composable
private fun StatusBadge(label: String, color: Color) {
    Surface(
        color = color.copy(alpha = 0.15f),
        shape = CircleShape,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Box(
                Modifier
                    .size(6.dp)
                    .background(color, CircleShape)
            )
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = color,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun SpotifyToggleRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onChecked: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .background(CanopyColors.Canvas, RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = LocalAccent.current,
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                color = CanopyColors.Text,
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = CanopyColors.Muted,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onChecked,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.Black,
                checkedTrackColor = LocalAccent.current,
                uncheckedThumbColor = CanopyColors.Muted,
                uncheckedTrackColor = CanopyColors.Canvas,
            ),
        )
    }
}

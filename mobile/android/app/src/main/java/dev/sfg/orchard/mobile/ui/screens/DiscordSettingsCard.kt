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
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Podcasts
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import dev.sfg.orchard.mobile.discord.DiscordAuthState
import dev.sfg.orchard.mobile.discord.GatewayConnectionState
import dev.sfg.orchard.mobile.model.OrchardSettings
import dev.sfg.orchard.mobile.ui.theme.CanopyColors
import dev.sfg.orchard.mobile.ui.theme.LocalAccent

private val DiscordBlurple = Color(0xFF5865F2)
private val DiscordGreen = Color(0xFF57F287)

@Composable
fun DiscordSettingsCard(
    settings: OrchardSettings,
    discordAuth: DiscordAuthState,
    discordConnection: GatewayConnectionState,
    onSettings: (OrchardSettings) -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
) {
    Surface(
        color = CanopyColors.Surface,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            when (discordAuth) {
                is DiscordAuthState.SignedIn -> {
                    val account = discordAuth.session.account
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (!account?.avatarUrl.isNullOrBlank()) {
                            AsyncImage(
                                model = account.avatarUrl,
                                contentDescription = account.displayName,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.size(48.dp).clip(CircleShape),
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .background(DiscordBlurple, CircleShape),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    (account?.displayName?.take(1) ?: "D").uppercase(),
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 20.sp,
                                )
                            }
                        }

                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                account?.displayName ?: "Discord User",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = CanopyColors.Text,
                            )
                            val subtitle = if (account?.username != null && account.globalName != null) {
                                "@${account.username}"
                            } else {
                                "Connected"
                            }
                            Text(
                                subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = CanopyColors.Muted,
                            )
                        }

                        ConnectionBadge(discordConnection, settings.discordPresenceEnabled)
                    }

                    Spacer(Modifier.height(14.dp))
                    OutlinedButton(
                        onClick = onDisconnect,
                        shape = CircleShape,
                        modifier = Modifier.fillMaxWidth().height(42.dp),
                    ) {
                        Text("Disconnect", color = CanopyColors.Danger, fontWeight = FontWeight.SemiBold)
                    }

                    Spacer(Modifier.height(16.dp))
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(CanopyColors.Rule)
                    )
                    Spacer(Modifier.height(12.dp))

                    DiscordToggleRow(
                        icon = Icons.Rounded.Podcasts,
                        title = "Share presence",
                        subtitle = "Display currently playing track on Discord",
                        checked = settings.discordPresenceEnabled,
                        onChecked = { onSettings(settings.copy(discordPresenceEnabled = it)) },
                    )

                    Spacer(Modifier.height(10.dp))
                    DiscordToggleRow(
                        icon = Icons.Rounded.Animation,
                        title = "Animated artwork",
                        subtitle = "Convert motion covers to animated GIFs",
                        checked = settings.discordAnimatedArtwork,
                        enabled = settings.discordPresenceEnabled,
                        onChecked = { onSettings(settings.copy(discordAnimatedArtwork = it)) },
                    )
                }

                DiscordAuthState.Authorizing -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Rounded.Sync,
                            contentDescription = null,
                            tint = LocalAccent.current,
                            modifier = Modifier.size(24.dp),
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            "Authorizing with Discord…",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = CanopyColors.Text,
                        )
                    }
                }

                is DiscordAuthState.SignedOut, is DiscordAuthState.Error -> {
                    Text(
                        "Discord Rich Presence",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = CanopyColors.Text,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Broadcast what you're listening to with animated artwork and smart universal song links.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = CanopyColors.Muted,
                    )
                    if (discordAuth is DiscordAuthState.Error) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            discordAuth.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = CanopyColors.Danger,
                        )
                    }
                    Spacer(Modifier.height(14.dp))
                    Button(
                        onClick = onConnect,
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = DiscordBlurple,
                            contentColor = Color.White,
                        ),
                        modifier = Modifier.fillMaxWidth().height(46.dp),
                    ) {
                        Text("Connect Discord", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectionBadge(connection: GatewayConnectionState, isPresenceEnabled: Boolean) {
    val (label, color) = when {
        !isPresenceEnabled -> "Paused" to CanopyColors.Muted
        connection is GatewayConnectionState.Ready -> "Online" to DiscordGreen
        connection is GatewayConnectionState.Connected -> "Ready" to DiscordGreen
        connection is GatewayConnectionState.Connecting -> "Connecting" to LocalAccent.current
        else -> "Offline" to CanopyColors.Muted
    }

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
private fun DiscordToggleRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean = true,
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
                tint = if (enabled) LocalAccent.current else CanopyColors.Muted,
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                color = if (enabled) CanopyColors.Text else CanopyColors.Muted,
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = CanopyColors.Muted,
            )
        }
        Switch(
            checked = checked && enabled,
            enabled = enabled,
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

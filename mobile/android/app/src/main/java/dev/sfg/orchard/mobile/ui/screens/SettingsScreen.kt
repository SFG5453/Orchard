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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.AllInclusive
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Gradient
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SystemUpdate
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import dev.sfg.orchard.connect.BuildConfig
import dev.sfg.orchard.mobile.MobileChangelog
import dev.sfg.orchard.mobile.MobileUpdateMetadata
import dev.sfg.orchard.mobile.UpdateState
import dev.sfg.orchard.mobile.auth.AuthState
import dev.sfg.orchard.mobile.discord.DiscordAuthState
import dev.sfg.orchard.mobile.discord.GatewayConnectionState
import dev.sfg.orchard.mobile.model.AudioQuality
import dev.sfg.orchard.mobile.model.OrchardSettings
import dev.sfg.orchard.mobile.ui.components.OrchardChromeHeight
import dev.sfg.orchard.mobile.ui.components.ReleaseNotesDialog
import dev.sfg.orchard.mobile.ui.components.UpdateDialog
import dev.sfg.orchard.mobile.ui.theme.CanopyColors
import dev.sfg.orchard.mobile.ui.theme.LocalAccent

@Composable
fun SettingsScreen(
    settings: OrchardSettings,
    auth: AuthState,
    discordAuth: DiscordAuthState = DiscordAuthState.SignedOut,
    discordConnection: GatewayConnectionState = GatewayConnectionState.Disconnected,
    updateState: UpdateState = UpdateState.Idle,
    onSettings: (OrchardSettings) -> Unit,
    onSignIn: () -> Unit,
    onSwitchAccount: () -> Unit,
    onSignOut: () -> Unit,
    onConnectDiscord: () -> Unit = {},
    onDisconnectDiscord: () -> Unit = {},
    onConnectSpotify: () -> Unit = {},
    onDevices: () -> Unit,
    onWelcome: () -> Unit = {},
    onCheckForUpdates: () -> Unit = {},
    onInstallUpdate: (MobileUpdateMetadata) -> Unit = {},
    /**
     * Separate from [onSettings] because switching Autoplay off also strips the tracks it added,
     * which only the view model can do.
     */
    onAutoplayEnabled: ((Boolean) -> Unit)? = null,
) {
    var showNotesDialog by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(28.dp))
        Text(
            "Settings",
            style = MaterialTheme.typography.displayLarge.copy(fontWeight = FontWeight.Bold, fontSize = 32.sp),
            color = CanopyColors.Text,
        )

        // Account leads: it is the only row whose state the user cannot infer at a glance.
        Spacer(Modifier.height(20.dp))
        AccountCard(auth, onSignIn, onSwitchAccount, onSignOut)

        SectionLabel("Audio")
        SettingsPanel {
            QualityRow(settings.audioQuality) { onSettings(settings.copy(audioQuality = it)) }
            PanelDivider()
            EqualizerRow(settings, onSettings)
            PanelDivider()
            ToggleRow(
                icon = Icons.Rounded.GraphicEq,
                title = "Show audio bitrate",
                subtitle = "Display streaming bitrate under player scrubber",
                checked = settings.showBitrate,
                onChecked = { onSettings(settings.copy(showBitrate = it)) },
            )
            PanelDivider()
            ToggleRow(
                icon = Icons.Rounded.GraphicEq,
                title = "Volume normalization",
                subtitle = "Even out volume differences between songs",
                checked = settings.volumeNormalizationEnabled,
                onChecked = { onSettings(settings.copy(volumeNormalizationEnabled = it)) },
            )
            PanelDivider()
            ToggleRow(
                icon = Icons.Rounded.AllInclusive,
                title = "Autoplay",
                subtitle = "Keep playing related music when the queue runs out",
                checked = settings.autoplayEnabled,
                onChecked = { enabled ->
                    onAutoplayEnabled?.invoke(enabled) ?: onSettings(settings.copy(autoplayEnabled = enabled))
                },
            )
            PanelDivider()
            CrossfadeRow(settings, onSettings)
            PanelDivider()
            CacheSizeRow(settings, onSettings)
        }

        SectionLabel("Integrations")
        DiscordSettingsCard(
            settings = settings,
            discordAuth = discordAuth,
            discordConnection = discordConnection,
            onSettings = onSettings,
            onConnect = onConnectDiscord,
            onDisconnect = onDisconnectDiscord,
        )
        Spacer(Modifier.height(12.dp))
        SpotifySettingsCard(
            settings = settings,
            onSettings = onSettings,
            onConnectSpotify = onConnectSpotify,
        )
        Spacer(Modifier.height(12.dp))
        OrchardAccountSettingsCard()

        SectionLabel("Appearance")
        SettingsPanel {
            ToggleRow(
                icon = Icons.Rounded.Person,
                title = "Player gestures",
                subtitle = "Swipe to skip and tap to like on artwork",
                checked = settings.playerGesturesEnabled,
                onChecked = { onSettings(settings.copy(playerGesturesEnabled = it)) },
            )
            PanelDivider()
            ToggleRow(
                icon = Icons.Rounded.Palette,
                title = "Use system colours",
                subtitle = "Match your wallpaper instead of Orchard green",
                checked = settings.useSystemColors,
                onChecked = { onSettings(settings.copy(useSystemColors = it)) },
            )
            PanelDivider()
            ToggleRow(
                icon = Icons.Rounded.Image,
                title = "Animated artwork",
                subtitle = "Move artwork while music plays",
                checked = settings.animatedArtwork,
                onChecked = { onSettings(settings.copy(animatedArtwork = it)) },
            )
            PanelDivider()
            ToggleRow(
                icon = Icons.Rounded.Gradient,
                title = "Animated background",
                subtitle = "Let the cover's colours drift behind the app",
                checked = settings.animatedBackground,
                onChecked = { onSettings(settings.copy(animatedBackground = it)) },
            )
        }

        SectionLabel("Devices")
        SettingsPanel {
            ActionRow(
                icon = Icons.Rounded.Devices,
                title = "Manage devices",
                subtitle = "Choose where Orchard plays",
                onClick = onDevices,
            )
        }

        SectionLabel("Updates")
        SettingsPanel {
            val availableUpdate = (updateState as? UpdateState.Available)?.metadata
            if (availableUpdate != null) {
                ActionRow(
                    icon = Icons.Rounded.SystemUpdate,
                    title = "Update available (${availableUpdate.version})",
                    subtitle = if (availableUpdate.codename.isNotBlank()) {
                        "\"${availableUpdate.codename}\" • Tap to install"
                    } else {
                        "Tap to install"
                    },
                    onClick = { onInstallUpdate(availableUpdate) },
                )
                PanelDivider()
                ActionRow(
                    icon = Icons.Rounded.History,
                    title = "Release notes",
                    subtitle = "View changes in Orchard ${availableUpdate.version}",
                    onClick = { showNotesDialog = true },
                )
            } else {
                ActionRow(
                    icon = Icons.Rounded.History,
                    title = "Release notes",
                    subtitle = if (!BuildConfig.UPDATER_ENABLED) {
                        "Orchard ${BuildConfig.VERSION_NAME} • Updates disabled"
                    } else {
                        "Orchard ${BuildConfig.VERSION_NAME} • Up to date"
                    },
                    onClick = { showNotesDialog = true },
                )
                if (BuildConfig.UPDATER_ENABLED) {
                    PanelDivider()
                    ActionRow(
                        icon = Icons.Rounded.Refresh,
                        title = "Check for updates",
                        subtitle = "Check for newer Orchard releases",
                        onClick = onCheckForUpdates,
                    )
                }
            }
        }

        SectionLabel("Guide")
        SettingsPanel {
            ActionRow(
                icon = Icons.Rounded.AutoAwesome,
                title = "Welcome & setup walkthrough",
                subtitle = "Revisit the initial setup guide",
                onClick = onWelcome,
            )
        }

        Spacer(Modifier.height(32.dp))
        val versionText = if (BuildConfig.CODENAME.isNotBlank()) {
            "Orchard Mobile ${BuildConfig.VERSION_NAME} \"${BuildConfig.CODENAME}\""
        } else {
            "Orchard Mobile ${BuildConfig.VERSION_NAME}"
        }
        Text(
            versionText,
            color = CanopyColors.Eyebrow,
            style = MaterialTheme.typography.labelMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(OrchardChromeHeight))
    }

    if (showNotesDialog) {
        val available = (updateState as? UpdateState.Available)?.metadata
        if (available != null) {
            UpdateDialog(
                state = updateState,
                onInstall = {
                    showNotesDialog = false
                    onInstallUpdate(it)
                },
                onDismiss = { showNotesDialog = false },
            )
        } else {
            ReleaseNotesDialog(
                version = BuildConfig.VERSION_NAME,
                codename = BuildConfig.CODENAME,
                releaseNotes = MobileChangelog.CURRENT_RELEASE_NOTES,
                onDismiss = { showNotesDialog = false },
            )
        }
    }
}

/** Signed-in identity and the sign in / out affordance, given more weight than a plain row. */
@Composable
private fun AccountCard(
    auth: AuthState,
    onSignIn: () -> Unit,
    onSwitchAccount: () -> Unit,
    onSignOut: () -> Unit,
) {
    Surface(
        color = CanopyColors.Surface,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val avatarUrl = (auth as? AuthState.SignedIn)?.avatarUrl.orEmpty()
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(LocalAccent.current.copy(alpha = 0.16f)),
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
                        Icons.Rounded.Person,
                        contentDescription = null,
                        tint = LocalAccent.current,
                        modifier = Modifier.size(26.dp),
                    )
                }
            }
            Spacer(Modifier.size(16.dp))
            Column(Modifier.weight(1f)) {
                when (auth) {
                    AuthState.Restoring -> AccountText("Checking session…", "One moment")
                    AuthState.Authorizing -> AccountText("Completing sign-in…", "One moment")
                    AuthState.SignedOut -> AccountText(
                        "Not signed in",
                        "Sync your library and recommendations",
                    )

                    // displayName falls back to the literal "YouTube Music" when the account
                    // name was never captured, so the subtitle must not repeat the provider.
                    is AuthState.SignedIn -> AccountText(auth.displayName, "Signed in")
                    is AuthState.Error -> AccountText(
                        "Sign-in failed",
                        auth.message,
                        titleColor = CanopyColors.Danger,
                    )
                }
            }
        }
    }

    when (auth) {
        AuthState.SignedOut -> AccountButton("Sign in", onSignIn, primary = true)
        is AuthState.Error -> AccountButton("Try again", onSignIn, primary = true)
        is AuthState.SignedIn -> {
            AccountButton("Switch YouTube account", onSwitchAccount, primary = true)
            AccountButton("Sign out", onSignOut, primary = false)
        }
        else -> Unit
    }
}

@Composable
private fun AccountText(title: String, subtitle: String, titleColor: Color = CanopyColors.Text) {
    Text(
        title,
        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
        color = titleColor,
    )
    Text(
        subtitle,
        style = MaterialTheme.typography.bodyMedium,
        color = CanopyColors.Muted,
    )
}

@Composable
private fun AccountButton(label: String, onClick: () -> Unit, primary: Boolean) {
    Spacer(Modifier.height(10.dp))
    if (primary) {
        Button(
            onClick = onClick,
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(
                containerColor = LocalAccent.current,
                contentColor = Color.Black,
            ),
            modifier = Modifier.fillMaxWidth().height(48.dp),
        ) {
            Text(label, fontWeight = FontWeight.Bold)
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            shape = CircleShape,
            modifier = Modifier.fillMaxWidth().height(48.dp),
        ) {
            Text(label, color = CanopyColors.Danger, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** Small uppercase eyebrow above each group, quieter than the old bold body-sized heading. */
@Composable
private fun SectionLabel(value: String) {
    Text(
        value.uppercase(),
        style = MaterialTheme.typography.labelMedium.copy(
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.2.sp,
        ),
        color = CanopyColors.Eyebrow,
        modifier = Modifier.padding(start = 4.dp, top = 26.dp, bottom = 10.dp),
    )
}

@Composable
private fun SettingsPanel(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        color = CanopyColors.Surface,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(content = content)
    }
}

/** Hairline between rows in the same panel. */
@Composable
internal fun PanelDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = 68.dp)
            .height(0.5.dp)
            .background(CanopyColors.Rule),
    )
}

/** Icon in a tinted rounded tile, shared by every row so the left edge lines up. */
@Composable
internal fun RowIcon(icon: ImageVector) {
    Box(
        modifier = Modifier
            .size(38.dp)
            .background(LocalAccent.current.copy(alpha = 0.14f), RoundedCornerShape(11.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = LocalAccent.current, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun ToggleRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onChecked: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            // Tapping the row toggles, not just the switch itself.
            .clickable { onChecked(!checked) }
            .defaultMinSize(minHeight = 64.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RowIcon(icon)
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = CanopyColors.Text,
            )
            Text(subtitle, color = CanopyColors.Muted, style = MaterialTheme.typography.bodyMedium)
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

@Composable
private fun ActionRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .defaultMinSize(minHeight = 64.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RowIcon(icon)
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = CanopyColors.Text,
            )
            Text(subtitle, color = CanopyColors.Muted, style = MaterialTheme.typography.bodyMedium)
        }
        Icon(
            Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            contentDescription = null,
            tint = CanopyColors.Muted,
            modifier = Modifier.size(22.dp),
        )
    }
}

/** Audio quality as a single segmented control rather than three loose chips. */
@Composable
private fun QualityRow(value: AudioQuality, onChange: (AudioQuality) -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RowIcon(Icons.Rounded.GraphicEq)
            Column(Modifier.padding(horizontal = 14.dp)) {
                Text(
                    "Audio quality",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = CanopyColors.Text,
                )
                Text(
                    value.description,
                    color = CanopyColors.Muted,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .background(CanopyColors.Canvas, CircleShape)
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            AudioQuality.entries.forEach { quality ->
                val isSelected = quality == value
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(38.dp)
                        .background(
                            if (isSelected) LocalAccent.current else Color.Transparent,
                            CircleShape,
                        )
                        .clickable { onChange(quality) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        quality.label,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        color = if (isSelected) Color.Black else CanopyColors.Muted,
                    )
                }
            }
        }
    }
}

private val AudioQuality.label: String
    get() = when (this) { AudioQuality.DATA_SAVER -> "Saver"; AudioQuality.NORMAL -> "Normal"; AudioQuality.HIGH -> "High"; AudioQuality.MAX -> "Max" }

private val AudioQuality.description: String
    get() = when (this) { AudioQuality.DATA_SAVER -> "Uses the least data"; AudioQuality.NORMAL -> "Balanced quality and data"; AudioQuality.HIGH -> "Best quality, more data"; AudioQuality.MAX -> "Highest bitrate stream via NewPipe" }

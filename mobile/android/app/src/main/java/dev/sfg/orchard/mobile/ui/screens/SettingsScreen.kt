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

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.toggleable
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
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.sfg.orchard.connect.BuildConfig
import dev.sfg.orchard.mobile.MobileChangelog
import dev.sfg.orchard.mobile.MobileUpdateMetadata
import dev.sfg.orchard.mobile.UpdateState
import dev.sfg.orchard.mobile.auth.AuthState
import dev.sfg.orchard.mobile.discord.DiscordAuthState
import dev.sfg.orchard.mobile.discord.GatewayConnectionState
import dev.sfg.orchard.mobile.lastfm.LastfmState
import dev.sfg.orchard.mobile.listenbrainz.ListenBrainzState
import dev.sfg.orchard.mobile.model.AudioQuality
import dev.sfg.orchard.mobile.model.OrchardSettings
import dev.sfg.orchard.mobile.ui.components.OrchardChromeHeight
import dev.sfg.orchard.mobile.ui.components.ReleaseNotesDialog
import dev.sfg.orchard.mobile.ui.components.UpdateDialog
import dev.sfg.orchard.mobile.ui.glass.GlassStyle
import dev.sfg.orchard.mobile.ui.glass.LocalGlass
import dev.sfg.orchard.mobile.ui.theme.CanopyColors
import dev.sfg.orchard.mobile.ui.theme.LocalAccent

@Composable
fun SettingsScreen(
    settings: OrchardSettings,
    auth: AuthState,
    discordAuth: DiscordAuthState = DiscordAuthState.SignedOut,
    discordConnection: GatewayConnectionState = GatewayConnectionState.Disconnected,
    lastfmState: LastfmState = LastfmState.SignedOut,
    listenBrainzState: ListenBrainzState = ListenBrainzState.SignedOut,
    updateState: UpdateState = UpdateState.Idle,
    onSettings: (OrchardSettings) -> Unit,
    onSignIn: () -> Unit,
    onSwitchAccount: () -> Unit,
    onSignOut: () -> Unit,
    onConnectDiscord: () -> Unit = {},
    onDisconnectDiscord: () -> Unit = {},
    onConnectLastfm: () -> Unit = {},
    onCompleteLastfm: () -> Unit = {},
    onDisconnectLastfm: () -> Unit = {},
    onConnectListenBrainz: (String) -> Unit = {},
    onDisconnectListenBrainz: () -> Unit = {},
    onConnectSpotify: () -> Unit = {},
    qobuzStatus: dev.sfg.orchard.mobile.qobuz.QobuzStatus = dev.sfg.orchard.mobile.qobuz.QobuzStatus(),
    onConnectQobuz: () -> Unit = {},
    onDisconnectQobuz: () -> Unit = {},
    onQobuzEnabledChange: (Boolean) -> Unit = {},
    onQobuzQualityChange: (dev.sfg.orchard.mobile.qobuz.QobuzQuality) -> Unit = {},
    onDevices: () -> Unit,
    onWelcome: () -> Unit = {},
    onCheckForUpdates: () -> Unit = {},
    onInstallUpdate: (MobileUpdateMetadata) -> Unit = {},
    onHomeLayout: () -> Unit = {},
    /**
     * Separate from [onSettings] because switching Autoplay off also strips the tracks it added,
     * which only the view model can do.
     */
    onAutoplayEnabled: ((Boolean) -> Unit)? = null,
    cacheSizeBytes: Long = 0L,
    isClearingCache: Boolean = false,
    onClearCache: () -> Unit = {},
    onRefreshCacheSize: () -> Unit = {},
) {
    var showNotesDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        onRefreshCacheSize()
    }

    val plainStyle = remember { GlassStyle(enabled = false) }
    val settingsAccent = lerp(LocalAccent.current, Color.White, 0.35f)
    CompositionLocalProvider(LocalGlass provides plainStyle, LocalAccent provides settingsAccent) {
        Column(Modifier.fillMaxSize().background(Color.Black)) {
            Text(
                "Settings",
                style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Bold),
                color = CanopyColors.Text,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 20.dp),
            )
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(CanopyColors.Rule))
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 4.dp)) {
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
                        title = "Show audio bitrate",
                        subtitle = "Display streaming bitrate under player scrubber",
                        checked = settings.showBitrate,
                        onChecked = { onSettings(settings.copy(showBitrate = it)) },
                    )
                    PanelDivider()
                    ToggleRow(
                        title = "Volume normalization",
                        subtitle = "Even out volume differences between songs",
                        checked = settings.volumeNormalizationEnabled,
                        onChecked = { onSettings(settings.copy(volumeNormalizationEnabled = it)) },
                    )
                    PanelDivider()
                    ToggleRow(
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
                    PanelDivider()
                    ClearCacheRow(
                        cacheSizeBytes = cacheSizeBytes,
                        isClearing = isClearingCache,
                        onClear = onClearCache,
                    )
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
                PanelDivider()
                LastfmSettingsCard(
                    state = lastfmState,
                    onConnect = onConnectLastfm,
                    onComplete = onCompleteLastfm,
                    onDisconnect = onDisconnectLastfm,
                )
                PanelDivider()
                ListenBrainzSettingsCard(
                    state = listenBrainzState,
                    onConnect = onConnectListenBrainz,
                    onDisconnect = onDisconnectListenBrainz,
                )
                PanelDivider()
                SpotifySettingsCard(
                    settings = settings,
                    onSettings = onSettings,
                    onConnectSpotify = onConnectSpotify,
                )
                PanelDivider()
                QobuzSettingsCard(
                    status = qobuzStatus,
                    onConnect = onConnectQobuz,
                    onDisconnect = onDisconnectQobuz,
                    onEnabledChange = onQobuzEnabledChange,
                    onQualityChange = onQobuzQualityChange,
                )
                PanelDivider()
                OrchardAccountSettingsCard(
                    settings = settings,
                    onSettings = onSettings,
                )

                SectionLabel("Appearance")
                SettingsPanel {
                    ActionRow(
                        title = "Home screen layout",
                        subtitle = "Reorder and hide sections on Home",
                        onClick = onHomeLayout,
                    )
                    PanelDivider()
                    ToggleRow(
                        title = "Player gestures",
                        subtitle = "Swipe to skip and tap to like on artwork",
                        checked = settings.playerGesturesEnabled,
                        onChecked = { onSettings(settings.copy(playerGesturesEnabled = it)) },
                    )
                    PanelDivider()
                    ToggleRow(
                        title = "Use system colours",
                        subtitle = "Match your wallpaper instead of Orchard green",
                        checked = settings.useSystemColors,
                        onChecked = { onSettings(settings.copy(useSystemColors = it)) },
                    )
                    PanelDivider()
                    ToggleRow(
                        title = "Animated artwork",
                        subtitle = "Move artwork while music plays",
                        checked = settings.animatedArtwork,
                        onChecked = { onSettings(settings.copy(animatedArtwork = it)) },
                    )
                    PanelDivider()
                    ToggleRow(
                        title = "Download animated artwork",
                        subtitle = "Save motion covers with new downloads for offline playback",
                        checked = settings.downloadAnimatedArtwork,
                        onChecked = { onSettings(settings.copy(downloadAnimatedArtwork = it)) },
                    )
                    PanelDivider()
                    ToggleRow(
                        title = "Animated background",
                        subtitle = "Let the cover's colours drift behind the app",
                        checked = settings.animatedBackground,
                        onChecked = { onSettings(settings.copy(animatedBackground = it)) },
                    )
                }

                SectionLabel("Experimental")
                SettingsPanel {
                    ToggleRow(
                        title = "Frosted glass",
                        subtitle = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            "Blur the artwork behind panels and bars"
                        } else {
                            "Blur the artwork behind panels and bars • No frost texture on Android 12"
                        },
                        checked = settings.frostedGlass,
                        onChecked = { onSettings(settings.copy(frostedGlass = it)) },
                    )
                }

                SectionLabel("Devices")
                SettingsPanel {
                    ActionRow(
                        title = "Manage devices",
                        subtitle = "Choose where Orchard plays",
                        onClick = onDevices,
                    )
                    PanelDivider()
                    ChromecastSettingsRow()
                }

                SectionLabel("Updates")
                SettingsPanel {
                    val availableUpdate = (updateState as? UpdateState.Available)?.metadata
                    if (availableUpdate != null) {
                        ActionRow(
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
                            title = "Release notes",
                            subtitle = "View changes in Orchard ${availableUpdate.version}",
                            onClick = { showNotesDialog = true },
                        )
                    } else {
                        ActionRow(
                            title = "Release notes",
                            subtitle = if (!BuildConfig.UPDATER_ENABLED) {
                                "Orchard ${BuildConfig.VERSION_NAME} • Updates disabled"
                            } else if (settings.betaChannelEnabled) {
                                "Orchard ${BuildConfig.VERSION_NAME} • Beta channel • Up to date"
                            } else {
                                "Orchard ${BuildConfig.VERSION_NAME} • Up to date"
                            },
                            onClick = { showNotesDialog = true },
                        )
                        if (BuildConfig.UPDATER_ENABLED) {
                            PanelDivider()
                            ActionRow(
                                title = "Check for updates",
                                subtitle = "Check for newer Orchard releases",
                                onClick = onCheckForUpdates,
                            )
                        }
                    }
                    if (BuildConfig.UPDATER_ENABLED) {
                        PanelDivider()
                        ToggleRow(
                            title = "Beta channel",
                            subtitle = "Get beta builds from GitHub releases instead of the regular channel. Beta builds may be less stable.",
                            checked = settings.betaChannelEnabled,
                            onChecked = {
                                val updated = settings.copy(betaChannelEnabled = it)
                                onSettings(updated)
                            },
                        )
                    }
                }

                SectionLabel("Guide")
                SettingsPanel {
                    ActionRow(
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
        }
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
        color = Color.Transparent,
        shape = SettingsPanelShape,
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
    Surface(
        onClick = onClick,
        color = Color.Transparent,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            label,
            color = if (primary) LocalAccent.current else CanopyColors.Danger,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
        )
    }
}

/** Accent headings separate the flat settings groups. */
@Composable
private fun SectionLabel(value: String) {
    Text(
        value,
        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
        color = LocalAccent.current,
        modifier = Modifier.padding(start = 16.dp, top = 28.dp, bottom = 8.dp),
    )
}

@Composable
private fun SettingsPanel(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        color = Color.Transparent,
        shape = SettingsPanelShape,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(content = content)
    }
}

private val SettingsPanelShape = RoundedCornerShape(20.dp)

/** Hairline between rows in the same panel. */
@Composable
internal fun PanelDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
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
    title: String,
    subtitle: String,
    checked: Boolean,
    onChecked: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            // Tapping the row toggles, not just the switch itself.
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChecked)
            .defaultMinSize(minHeight = 64.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {

        Column(Modifier.weight(1f).padding(end = 16.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Normal),
                color = CanopyColors.Text,
            )
            Text(subtitle, color = CanopyColors.Muted, style = MaterialTheme.typography.bodyMedium)
        }
        Switch(
            checked = checked,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = LocalAccent.current,
                uncheckedThumbColor = CanopyColors.Muted,
                uncheckedTrackColor = CanopyColors.Canvas,
            ),
        )
    }
}

@Composable
private fun ActionRow(
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

        Column(Modifier.weight(1f).padding(end = 16.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Normal),
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

            Column(Modifier) {
                Text(
                    "Audio quality",
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Normal),
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

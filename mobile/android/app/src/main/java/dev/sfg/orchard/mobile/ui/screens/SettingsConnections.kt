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

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sfg.orchard.mobile.discord.DiscordPresenceStatus
import dev.sfg.orchard.mobile.lastfm.LastfmState
import dev.sfg.orchard.mobile.listenbrainz.ListenBrainzState
import dev.sfg.orchard.mobile.model.OrchardSettings
import dev.sfg.orchard.mobile.qobuz.QobuzQuality
import dev.sfg.orchard.mobile.qobuz.QobuzStatus

/** Services listed under Connections; each opens its own detail page. */
internal enum class ConnectionService(val title: String, val blurb: String) {
    Discord("Discord", "Share what you’re listening to"),
    Lastfm("Last.fm", "Send now-playing updates and completed listens"),
    ListenBrainz("ListenBrainz", "Send listens with your user token"),
    Spotify("Spotify", "Canvas videos in the player"),
    Qobuz("Qobuz", "Lossless and Hi-Res audio"),
    Orchard("Orchard Account", "Use your account across devices"),
}

/** One-line state for a service; [summary] doubles as the row subtitle. */
internal data class ConnectionStatus(val connected: Boolean, val summary: String)

internal fun connectionStatuses(
    settings: OrchardSettings,
    discordStatus: DiscordPresenceStatus,
    lastfmState: LastfmState,
    listenBrainzState: ListenBrainzState,
    qobuzStatus: QobuzStatus,
    orchardEmail: String,
): Map<ConnectionService, ConnectionStatus> = mapOf(
    ConnectionService.Discord to when {
        !settings.discordPresenceEnabled -> ConnectionStatus(false, "Presence off")
        discordStatus is DiscordPresenceStatus.Sharing -> ConnectionStatus(true, "Sharing presence")
        discordStatus is DiscordPresenceStatus.Ready -> ConnectionStatus(true, "Ready")
        discordStatus is DiscordPresenceStatus.Error -> ConnectionStatus(false, discordStatus.message)
        else -> ConnectionStatus(false, "Discord app not installed")
    },
    ConnectionService.Lastfm to when (lastfmState) {
        is LastfmState.Connected -> ConnectionStatus(true, "Scrobbling on")
        LastfmState.Connecting -> ConnectionStatus(false, "Starting browser authorization…")
        is LastfmState.Pending -> ConnectionStatus(false, "Approve Orchard in your browser, then finish")
        is LastfmState.Error -> ConnectionStatus(false, lastfmState.message)
        LastfmState.SignedOut -> ConnectionStatus(false, ConnectionService.Lastfm.blurb)
    },
    ConnectionService.ListenBrainz to when (listenBrainzState) {
        is ListenBrainzState.Connected -> ConnectionStatus(true, "Connected as ${listenBrainzState.user}")
        ListenBrainzState.Validating -> ConnectionStatus(false, "Checking your token…")
        is ListenBrainzState.Error -> ConnectionStatus(false, listenBrainzState.message)
        ListenBrainzState.SignedOut -> ConnectionStatus(false, ConnectionService.ListenBrainz.blurb)
    },
    ConnectionService.Spotify to if (settings.spotifySpdc.isNotBlank()) {
        ConnectionStatus(true, if (settings.spotifyCanvasEnabled) "Canvas on" else "Canvas off")
    } else {
        ConnectionStatus(false, ConnectionService.Spotify.blurb)
    },
    ConnectionService.Qobuz to if (qobuzStatus.isConnected) {
        ConnectionStatus(true, if (qobuzStatus.enabled) qobuzStatus.quality.label else "Disabled")
    } else {
        ConnectionStatus(false, ConnectionService.Qobuz.blurb)
    },
    ConnectionService.Orchard to if (orchardEmail.isNotBlank()) {
        ConnectionStatus(true, "Signed in as $orchardEmail")
    } else {
        ConnectionStatus(false, ConnectionService.Orchard.blurb)
    },
)

/** Connected services first, the rest under Available with a Connect hint. */
@Composable
internal fun ConnectionsList(
    statuses: Map<ConnectionService, ConnectionStatus>,
    onOpen: (ConnectionService) -> Unit,
) {
    val (connected, available) = ConnectionService.entries.partition { statuses[it]?.connected == true }
    if (connected.isNotEmpty()) {
        SectionLabel("Connected")
        SettingsPanel(index = 1) {
            connected.forEachIndexed { index, service ->
                if (index > 0) PanelDivider()
                ConnectionRow(service, statuses.getValue(service), onOpen)
            }
        }
    }
    if (available.isNotEmpty()) {
        SectionLabel("Available", 2)
        SettingsPanel(index = 3) {
            available.forEachIndexed { index, service ->
                if (index > 0) PanelDivider()
                ConnectionRow(service, statuses.getValue(service), onOpen)
            }
        }
    }
}

@Composable
private fun ConnectionRow(
    service: ConnectionService,
    status: ConnectionStatus,
    onOpen: (ConnectionService) -> Unit,
) {
    SettingsRow(
        title = service.title,
        subtitle = status.summary,
        modifier = Modifier.clickable { onOpen(service) },
    ) {
        if (status.connected) {
            Icon(
                Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = SettingsStyle.Chevron,
            )
        } else {
            // Display-only: the whole row opens the detail page.
            Surface(
                shape = CircleShape,
                color = SettingsStyle.ButtonFill,
                border = BorderStroke(1.dp, SettingsStyle.ButtonBorder),
            ) {
                Text(
                    "Connect",
                    style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp),
                    color = SettingsStyle.Title,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }
    }
}

/** Status pill shown under a detail page title. */
@Composable
internal fun ConnectionChip(status: ConnectionStatus, modifier: Modifier = Modifier) {
    val lit = status.connected
    Surface(
        shape = CircleShape,
        color = if (lit) SettingsStyle.Sage.copy(alpha = 0.18f) else SettingsStyle.ButtonFill,
        border = BorderStroke(1.dp, if (lit) SettingsStyle.Sage.copy(alpha = 0.3f) else SettingsStyle.ButtonBorder),
        modifier = modifier,
    ) {
        Box(Modifier.defaultMinSize(minHeight = 28.dp).padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
            Text(
                if (lit) "Connected" else "Not connected",
                style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp),
                color = if (lit) SettingsStyle.SageSoft else SettingsStyle.Description,
            )
        }
    }
}

/** Detail body: the service's own card inside one panel. */
@Composable
internal fun ConnectionDetail(
    service: ConnectionService,
    settings: OrchardSettings,
    discordStatus: DiscordPresenceStatus,
    lastfmState: LastfmState,
    listenBrainzState: ListenBrainzState,
    qobuzStatus: QobuzStatus,
    onSettings: (OrchardSettings) -> Unit,
    onConnectLastfm: () -> Unit,
    onCompleteLastfm: () -> Unit,
    onDisconnectLastfm: () -> Unit,
    onConnectListenBrainz: (String) -> Unit,
    onDisconnectListenBrainz: () -> Unit,
    onConnectSpotify: () -> Unit,
    onConnectQobuz: () -> Unit,
    onDisconnectQobuz: () -> Unit,
    onQobuzEnabledChange: (Boolean) -> Unit,
    onQobuzQualityChange: (QobuzQuality) -> Unit,
) {
    Column(Modifier.padding(top = 8.dp)) {
        SettingsPanel(index = 1) {
            when (service) {
                ConnectionService.Discord -> DiscordSettingsCard(
                    settings = settings,
                    discordStatus = discordStatus,
                    onSettings = onSettings,
                )
                ConnectionService.Lastfm -> LastfmSettingsCard(
                    state = lastfmState,
                    onConnect = onConnectLastfm,
                    onComplete = onCompleteLastfm,
                    onDisconnect = onDisconnectLastfm,
                )
                ConnectionService.ListenBrainz -> ListenBrainzSettingsCard(
                    state = listenBrainzState,
                    onConnect = onConnectListenBrainz,
                    onDisconnect = onDisconnectListenBrainz,
                )
                ConnectionService.Spotify -> SpotifySettingsCard(
                    settings = settings,
                    onSettings = onSettings,
                    onConnectSpotify = onConnectSpotify,
                )
                ConnectionService.Qobuz -> QobuzSettingsCard(
                    status = qobuzStatus,
                    onConnect = onConnectQobuz,
                    onDisconnect = onDisconnectQobuz,
                    onEnabledChange = onQobuzEnabledChange,
                    onQualityChange = onQobuzQualityChange,
                )
                ConnectionService.Orchard -> OrchardAccountSettingsCard()
            }
        }
    }
}

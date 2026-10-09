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
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import dev.sfg.orchard.mobile.ui.motion.riseIn
import dev.sfg.orchard.mobile.ui.scroll.orchardVerticalScroll as verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sfg.orchard.mobile.audio.selfDeviceWord
import dev.sfg.orchard.mobile.connect.ConnectState
import dev.sfg.orchard.mobile.model.PlaybackDevice
import dev.sfg.orchard.mobile.model.PlaybackTarget
import dev.sfg.orchard.mobile.model.PlaybackTargetState
import dev.sfg.orchard.mobile.social.PartyState
import dev.sfg.orchard.mobile.ui.theme.CanopyColors
import dev.sfg.orchard.mobile.ui.theme.LocalAccent

@Composable
internal fun FrostedDevicesScreenContent(
    targets: PlaybackTargetState,
    connect: ConnectState,
    party: PartyState,
    onSelect: (PlaybackTarget) -> Unit,
    onCreateParty: () -> Unit,
    onJoinParty: (String) -> Unit,
    onLeaveParty: () -> Unit,
    onRenameDevice: (PlaybackDevice, String) -> Unit,
) {
    val context = LocalContext.current
    var deviceToRename by remember { mutableStateOf<PlaybackDevice?>(null) }

    val activeDevice =
        targets.devices.firstOrNull { it.isActive } ?: targets.devices.firstOrNull { it.isLocal }
    val availableDevices = targets.devices.filter { it != activeDevice }
    val controllers = connect.controllerNames

    // Single scroll column: a weighted child would force the sheet to full height.
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        FrostedHeader()
        PanelDivider()
        Column {
            Spacer(Modifier.height(18.dp))
            activeDevice?.let { device ->
                Box(Modifier.riseIn(1, fromScale = 0.92f)) {
                    FrostedActiveDeviceHeroCard(
                        device = device,
                        isTransferring = targets.isTransferring,
                        deviceWord = context.selfDeviceWord(),
                        controlledBy = if (device.isLocal) controllers else emptyList(),
                        onRename = if (device.isLocal) ({ deviceToRename = device }) else null,
                    )
                }
                Spacer(Modifier.height(14.dp))
            }

            if (targets.message.isNotBlank()) {
                Box(Modifier.riseIn(distance = 12f)) { FrostedMessageBanner(message = targets.message) }
                Spacer(Modifier.height(14.dp))
            }

            FrostedSectionHeader(title = "Orchard Connect")
            Spacer(Modifier.height(8.dp))
            when {
                !connect.available -> FrostedMessageBanner(
                    "Sign in to your Orchard account in Settings to play on your other devices.",
                )
                !connect.online -> FrostedMessageBanner("Reaching Orchard Connect…")
                availableDevices.isEmpty() -> FrostedMessageBanner(
                    "No other devices are online. Open Orchard on your computer and sign in to the same Orchard account.",
                )
            }
            if (availableDevices.isNotEmpty()) {
                availableDevices.forEachIndexed { i, device ->
                    FrostedDeviceRow(
                        modifier = Modifier.riseIn(i + 2),
                        device = device,
                        onClick = {
                            onSelect(
                                if (device.isLocal) PlaybackTarget.LocalPhone
                                else PlaybackTarget.Remote(device.id)
                            )
                        },
                        onRename = if (device.isLocal) ({ deviceToRename = device }) else null,
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }
            Spacer(Modifier.height(14.dp))

            // Google Cast / Chromecast
            FrostedSectionHeader(title = "Chromecast")
            Spacer(Modifier.height(8.dp))
            Box(Modifier.riseIn(4)) { ChromecastConnectRow() }
            Spacer(Modifier.height(14.dp))

            PanelDivider()

            // Real-time Listening Party Hub
            Box(Modifier.riseIn(6)) {
                FrostedListeningPartyPanel(
                    party = party,
                    onCreate = onCreateParty,
                    onJoin = onJoinParty,
                    onLeave = onLeaveParty,
                )
            }

            Spacer(Modifier.navigationBarsPadding().height(24.dp))
        }
    }

    deviceToRename?.let { dev ->
        RenameDeviceDialog(
            device = dev,
            onDismiss = { deviceToRename = null },
            onConfirm = { newName ->
                onRenameDevice(dev, newName)
                deviceToRename = null
            },
        )
    }
}

@Composable
private fun FrostedHeader() {
    Text(
        "Connect",
        style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Bold),
        color = CanopyColors.Text,
        modifier = Modifier.padding(bottom = 12.dp),
    )
}

@Composable
private fun FrostedActiveDeviceHeroCard(
    device: PlaybackDevice,
    isTransferring: Boolean,
    deviceWord: String,
    controlledBy: List<String> = emptyList(),
    onRename: (() -> Unit)? = null,
) {
    val shape = RoundedCornerShape(22.dp)
    val accent = LocalAccent.current

    Surface(shape = shape, color = Color.Transparent, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(vertical = 16.dp, horizontal = 4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Device Icon in glowing glass circle
                Box(
                    modifier =
                        Modifier.size(48.dp)
                            .clip(CircleShape)
                            .background(accent.copy(alpha = 0.2f))
                            .border(1.dp, accent.copy(alpha = 0.35f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = getDeviceIcon(device),
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(24.dp),
                    )
                }

                Spacer(Modifier.width(14.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Current output",
                        style =
                            MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp,
                            ),
                        color = accent,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = device.displayName,
                            style =
                                MaterialTheme.typography.titleLarge.copy(
                                    fontWeight = FontWeight.Bold
                                ),
                            color = CanopyColors.Text,
                        )
                        if (onRename != null) {
                            Spacer(Modifier.width(6.dp))
                            IconButton(onClick = onRename, modifier = Modifier.size(28.dp)) {
                                Icon(
                                    Icons.Rounded.Edit,
                                    contentDescription = "Rename ${device.displayName}",
                                    tint = CanopyColors.Muted,
                                    modifier = Modifier.size(15.dp),
                                )
                            }
                        }
                    }
                    val subtitlePrefix =
                        if (device.customName.isNotBlank()) "(${device.name}) • " else ""
                    Text(
                        text =
                            subtitlePrefix +
                                when {
                                    controlledBy.isNotEmpty() -> "Controlled by ${controlledBy.joinToString()}"
                                    device.isLocal -> "This $deviceWord"
                                    else -> "Playing there, controlled from here"
                                },
                        style = MaterialTheme.typography.bodySmall,
                        color = CanopyColors.Muted,
                    )
                }

                // Live Equalizer wave animation
                Icon(Icons.Rounded.CheckCircle, "Current output", tint = accent)
            }

            if (isTransferring) {
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier =
                        Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(accent.copy(alpha = 0.15f))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Rounded.SwapHoriz,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Connecting…",
                        style =
                            MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        color = CanopyColors.Text,
                    )
                }
            }
        }
    }
}

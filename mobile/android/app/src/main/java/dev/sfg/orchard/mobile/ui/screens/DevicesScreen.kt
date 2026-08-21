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

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Sensors
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import dev.sfg.orchard.connect.protocol.ConnectAudioEngine
import dev.sfg.orchard.connect.ui.PairingScanActivity
import dev.sfg.orchard.mobile.audio.selfDeviceWord
import dev.sfg.orchard.mobile.model.DeviceAvailability
import dev.sfg.orchard.mobile.model.DeviceType
import dev.sfg.orchard.mobile.model.PlaybackDevice
import dev.sfg.orchard.mobile.model.PlaybackTarget
import dev.sfg.orchard.mobile.model.PlaybackTargetState
import dev.sfg.orchard.mobile.social.PartyPeer
import dev.sfg.orchard.mobile.social.PartyRole
import dev.sfg.orchard.mobile.social.PartyState
import dev.sfg.orchard.mobile.social.PartyStatus
import dev.sfg.orchard.mobile.social.cleanRoomCode
import dev.sfg.orchard.mobile.ui.components.OrchardChromeHeight
import dev.sfg.orchard.mobile.ui.glass.GlassTone
import dev.sfg.orchard.mobile.ui.glass.LocalGlass
import dev.sfg.orchard.mobile.ui.glass.glassFill
import dev.sfg.orchard.mobile.ui.glass.glassPane
import dev.sfg.orchard.mobile.ui.theme.CanopyColors
import dev.sfg.orchard.mobile.ui.theme.LocalAccent

/** Room codes the listening party worker generates are always 6 characters. */
private const val ROOM_CODE_LENGTH = 6

/**
 * Connect & Devices screen.
 *
 * When frosted glass is enabled, it renders a rich frosted glass experience
 * ([FrostedDevicesScreenContent]) featuring hero playback output routing, live equalizer
 * visualizers, remote DSP audio controls, sleek LAN desktop pairing, and an active listening party
 * hub.
 *
 * When frosted glass is off, it preserves the standard clean layout
 * ([StandardDevicesScreenContent]).
 */
@Composable
fun DevicesScreen(
    targets: PlaybackTargetState,
    connectMessage: String,
    protocolVersion: Int = 1,
    audioEngine: ConnectAudioEngine = ConnectAudioEngine(),
    party: PartyState = PartyState(),
    onBack: () -> Unit,
    onSelect: (PlaybackTarget) -> Unit,
    onPair: (String) -> Unit,
    onDisconnect: () -> Unit,
    onPresetSelect: (String) -> Unit = {},
    onToggleAutoEq: (Boolean) -> Unit = {},
    onToggleManualEq: (Boolean) -> Unit = {},
    onCreateParty: () -> Unit = {},
    onJoinParty: (String) -> Unit = {},
    onLeaveParty: () -> Unit = {},
    onRenameDevice: (PlaybackDevice, String) -> Unit = { _, _ -> },
    onRemoveDevice: (String) -> Unit = {},
) {
    val glass = LocalGlass.current.enabled
    if (glass) {
        FrostedDevicesScreenContent(
            targets = targets,
            connectMessage = connectMessage,
            protocolVersion = protocolVersion,
            audioEngine = audioEngine,
            party = party,
            onBack = onBack,
            onSelect = onSelect,
            onPair = onPair,
            onDisconnect = onDisconnect,
            onPresetSelect = onPresetSelect,
            onToggleAutoEq = onToggleAutoEq,
            onToggleManualEq = onToggleManualEq,
            onCreateParty = onCreateParty,
            onJoinParty = onJoinParty,
            onLeaveParty = onLeaveParty,
            onRenameDevice = onRenameDevice,
            onRemoveDevice = onRemoveDevice,
        )
    } else {
        StandardDevicesScreenContent(
            targets = targets,
            connectMessage = connectMessage,
            protocolVersion = protocolVersion,
            audioEngine = audioEngine,
            party = party,
            onBack = onBack,
            onSelect = onSelect,
            onPair = onPair,
            onDisconnect = onDisconnect,
            onPresetSelect = onPresetSelect,
            onToggleAutoEq = onToggleAutoEq,
            onToggleManualEq = onToggleManualEq,
            onCreateParty = onCreateParty,
            onJoinParty = onJoinParty,
            onLeaveParty = onLeaveParty,
            onRenameDevice = onRenameDevice,
            onRemoveDevice = onRemoveDevice,
        )
    }
}

// ══════════════════════════════════════════════════════════════════════════════
// REVAMPED FROSTED GLASS CONNECT SCREEN
// ══════════════════════════════════════════════════════════════════════════════

@Composable
private fun FrostedDevicesScreenContent(
    targets: PlaybackTargetState,
    connectMessage: String,
    protocolVersion: Int,
    audioEngine: ConnectAudioEngine,
    party: PartyState,
    onBack: () -> Unit,
    onSelect: (PlaybackTarget) -> Unit,
    onPair: (String) -> Unit,
    onDisconnect: () -> Unit,
    onPresetSelect: (String) -> Unit,
    onToggleAutoEq: (Boolean) -> Unit,
    onToggleManualEq: (Boolean) -> Unit,
    onCreateParty: () -> Unit,
    onJoinParty: (String) -> Unit,
    onLeaveParty: () -> Unit,
    onRenameDevice: (PlaybackDevice, String) -> Unit,
    onRemoveDevice: (String) -> Unit,
) {
    val context = LocalContext.current
    var pairingInput by remember { mutableStateOf("") }
    var deviceToRename by remember { mutableStateOf<PlaybackDevice?>(null) }
    val scanner =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result
            ->
            if (result.resultCode == Activity.RESULT_OK) {
                result.data?.getStringExtra("SCAN_RESULT")?.takeIf(String::isNotBlank)?.let {
                    pairingInput = it
                    onPair(it)
                }
            }
        }

    val activeDevice =
        targets.devices.firstOrNull { it.isActive } ?: targets.devices.firstOrNull { it.isLocal }
    val availableDevices = targets.devices.filter { it != activeDevice }
    val hasRemote = targets.devices.any { !it.isLocal }
    val message = targets.message.ifBlank { connectMessage }

    Column(
        modifier =
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)
    ) {
        Spacer(Modifier.height(12.dp))

        // Top Navigation & Header Row
        FrostedHeaderRow(party = party, hasRemote = hasRemote, onBack = onBack)

        Spacer(Modifier.height(18.dp))

        // Hero Active Output Destination Card
        activeDevice?.let { device ->
            FrostedActiveDeviceHeroCard(
                device = device,
                isTransferring = targets.isTransferring,
                deviceWord = context.selfDeviceWord(),
                onRename = { deviceToRename = device },
            )
            Spacer(Modifier.height(14.dp))
        }

        // Status or Error Message Banner
        if (message.isNotBlank()) {
            FrostedMessageBanner(message = message)
            Spacer(Modifier.height(14.dp))
        }

        // Available Playback Targets (if any additional devices exist)
        if (availableDevices.isNotEmpty()) {
            FrostedSectionHeader(
                title = "Available Devices",
                badge = "${availableDevices.size} paired",
            )
            Spacer(Modifier.height(8.dp))

            availableDevices.forEach { device ->
                FrostedDeviceRow(
                    device = device,
                    onClick = {
                        onSelect(
                            if (device.isLocal) PlaybackTarget.LocalPhone
                            else PlaybackTarget.Remote(device.id)
                        )
                    },
                    onRename = { deviceToRename = device },
                    onRemove = if (!device.isLocal) { { onRemoveDevice(device.id) } } else null,
                )
                Spacer(Modifier.height(8.dp))
            }
            Spacer(Modifier.height(8.dp))
        }

        // Remote DSP Audio Engine (when targeting remote desktop with v2+ protocol)
        if (protocolVersion >= 2 && targets.selected is PlaybackTarget.Remote) {
            FrostedRemoteAudioEngineCard(
                audioEngine = audioEngine,
                onPresetSelect = onPresetSelect,
                onToggleAutoEq = onToggleAutoEq,
                onToggleManualEq = onToggleManualEq,
            )
            Spacer(Modifier.height(14.dp))
        }

        // Desktop LAN Pairing Hub
        FrostedPairingPanel(
            input = pairingInput,
            onInput = { pairingInput = it },
            onPair = { onPair(pairingInput) },
            onScan = {
                scanner.launch(
                    Intent(context, PairingScanActivity::class.java).apply {
                        action = "com.google.zxing.client.android.SCAN"
                        putExtra("SCAN_FORMATS", "QR_CODE")
                    }
                )
            },
            onDisconnect = onDisconnect,
            hasRemote = hasRemote,
        )

        Spacer(Modifier.height(14.dp))

        // Real-time Listening Party Hub
        FrostedListeningPartyPanel(
            party = party,
            onCreate = onCreateParty,
            onJoin = onJoinParty,
            onLeave = onLeaveParty,
        )

        // Bottom space so floating navigation bar & mini player do not overlap contents
        Spacer(Modifier.height(OrchardChromeHeight + 20.dp))
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
fun RenameDeviceDialog(
    device: PlaybackDevice,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var nameInput by remember(device) { mutableStateOf(device.displayName) }
    val accent = LocalAccent.current
    val shape = RoundedCornerShape(24.dp)
    val hasCustomName = device.customName.isNotBlank()

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = shape,
            color = glassFill(CanopyColors.Surface),
            modifier =
                Modifier.fillMaxWidth()
                    .glassPane(shape, GlassTone.PANEL)
                    .border(1.dp, CanopyColors.RuleStrong, shape),
        ) {
            Column(
                modifier = Modifier.padding(22.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier =
                        Modifier.size(48.dp)
                            .clip(CircleShape)
                            .background(accent.copy(alpha = 0.18f))
                            .glassPane(CircleShape, GlassTone.CONTROL)
                            .border(1.dp, accent.copy(alpha = 0.35f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector =
                            when (device.type) {
                                DeviceType.PHONE -> Icons.Rounded.PhoneAndroid
                                DeviceType.COMPUTER -> Icons.Rounded.Computer
                                else -> Icons.Rounded.Devices
                            },
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(24.dp),
                    )
                }

                Spacer(Modifier.height(14.dp))

                Text(
                    text = if (device.isLocal) "Rename This Device" else "Rename Paired Device",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = CanopyColors.Text,
                )

                Spacer(Modifier.height(4.dp))

                Text(
                    text =
                        if (device.isLocal) {
                            "Set a custom name for this device in Connect and Listening Parties."
                        } else {
                            "Set a custom nickname for this Orchard desktop device."
                        },
                    style = MaterialTheme.typography.bodySmall,
                    color = CanopyColors.Muted,
                    textAlign = TextAlign.Center,
                )

                Spacer(Modifier.height(18.dp))

                TextField(
                    value = nameInput,
                    onValueChange = { nameInput = it },
                    placeholder = { Text(device.name, color = CanopyColors.Muted) },
                    singleLine = true,
                    shape = CircleShape,
                    colors =
                        TextFieldDefaults.colors(
                            focusedContainerColor = CanopyColors.Canvas,
                            unfocusedContainerColor = CanopyColors.Canvas,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            focusedTextColor = CanopyColors.Text,
                            unfocusedTextColor = CanopyColors.Text,
                        ),
                    trailingIcon = {
                        if (nameInput.isNotBlank()) {
                            IconButton(onClick = { nameInput = "" }) {
                                Icon(
                                    Icons.Rounded.Close,
                                    contentDescription = "Clear",
                                    tint = CanopyColors.Muted,
                                )
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().border(1.dp, CanopyColors.Rule, CircleShape),
                )

                if (hasCustomName) {
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = {
                            onConfirm("")
                            onDismiss()
                        },
                        shape = CircleShape,
                        modifier = Modifier.fillMaxWidth().height(36.dp),
                    ) {
                        Icon(
                            Icons.Rounded.RestartAlt,
                            contentDescription = null,
                            tint = CanopyColors.Muted,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "Reset to Default (${device.name})",
                            style = MaterialTheme.typography.labelSmall,
                            color = CanopyColors.MutedStrong,
                        )
                    }
                }

                Spacer(Modifier.height(20.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        shape = CircleShape,
                        modifier =
                            Modifier.weight(1f)
                                .height(44.dp)
                                .glassPane(CircleShape, GlassTone.CONTROL)
                                .border(1.dp, CanopyColors.Rule, CircleShape),
                    ) {
                        Text("Cancel", fontWeight = FontWeight.SemiBold, color = CanopyColors.Text)
                    }

                    Button(
                        onClick = {
                            onConfirm(nameInput.trim())
                            onDismiss()
                        },
                        shape = CircleShape,
                        colors =
                            ButtonDefaults.buttonColors(
                                containerColor = accent,
                                contentColor = Color.Black,
                            ),
                        modifier = Modifier.weight(1f).height(44.dp),
                    ) {
                        Text("Save", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// Frosted UI Components
// ──────────────────────────────────────────────────────────────────────────────

@Composable
private fun FrostedHeaderRow(party: PartyState, hasRemote: Boolean, onBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                onClick = onBack,
                shape = CircleShape,
                color = glassFill(CanopyColors.Surface),
                modifier =
                    Modifier.size(42.dp)
                        .glassPane(CircleShape, GlassTone.CONTROL)
                        .border(1.dp, CanopyColors.Rule, CircleShape),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = "Back",
                        tint = CanopyColors.Text,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            Spacer(Modifier.width(12.dp))

            Column {
                Text(
                    text = "Connect",
                    style =
                        MaterialTheme.typography.displayLarge.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 28.sp,
                            letterSpacing = (-0.5).sp,
                        ),
                    color = CanopyColors.Text,
                )
                Text(
                    text = "Devices & listening parties",
                    style = MaterialTheme.typography.bodySmall,
                    color = CanopyColors.Muted,
                )
            }
        }

        // Live connection badge chip
        val (badgeLabel, badgeColor, badgeIcon) =
            when {
                party.isActive -> Triple("Party Live", LocalAccent.current, Icons.Rounded.Groups)
                hasRemote ->
                    Triple("LAN Paired", CanopyColors.SecondaryAccent, Icons.Rounded.Computer)
                else -> Triple("LAN Ready", CanopyColors.Muted, Icons.Rounded.Sensors)
            }

        Surface(
            shape = CircleShape,
            color = glassFill(CanopyColors.Surface, badgeColor.copy(alpha = 0.12f)),
            modifier =
                Modifier.glassPane(CircleShape, GlassTone.CONTROL)
                    .border(1.dp, CanopyColors.Rule, CircleShape),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = badgeIcon,
                    contentDescription = null,
                    tint = badgeColor,
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    text = badgeLabel,
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = CanopyColors.Text,
                )
            }
        }
    }
}

@Composable
private fun FrostedActiveDeviceHeroCard(
    device: PlaybackDevice,
    isTransferring: Boolean,
    deviceWord: String,
    onRename: (() -> Unit)? = null,
) {
    val shape = RoundedCornerShape(22.dp)
    val accent = LocalAccent.current

    Surface(
        shape = shape,
        color = glassFill(CanopyColors.Surface, accent.copy(alpha = 0.14f)),
        modifier =
            Modifier.fillMaxWidth()
                .glassPane(shape, GlassTone.PANEL)
                .border(1.dp, CanopyColors.RuleStrong, shape),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
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
                            .glassPane(CircleShape, GlassTone.CONTROL)
                            .border(1.dp, accent.copy(alpha = 0.35f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector =
                            when (device.type) {
                                DeviceType.PHONE -> Icons.Rounded.PhoneAndroid
                                DeviceType.COMPUTER -> Icons.Rounded.Computer
                                else -> Icons.Rounded.Devices
                            },
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(24.dp),
                    )
                }

                Spacer(Modifier.width(14.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "CURRENT OUTPUT",
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
                                MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                            color = CanopyColors.Text,
                        )
                        if (onRename != null) {
                            Spacer(Modifier.width(6.dp))
                            IconButton(
                                onClick = onRename,
                                modifier = Modifier.size(28.dp),
                            ) {
                                Icon(
                                    Icons.Rounded.Edit,
                                    contentDescription = "Rename ${device.displayName}",
                                    tint = CanopyColors.Muted,
                                    modifier = Modifier.size(15.dp),
                                )
                            }
                        }
                    }
                    val subtitlePrefix = if (device.customName.isNotBlank()) "(${device.name}) • " else ""
                    Text(
                        text =
                            subtitlePrefix + if (device.isLocal) "Playing locally on this $deviceWord"
                            else "Streaming via Orchard Connect LAN",
                        style = MaterialTheme.typography.bodySmall,
                        color = CanopyColors.Muted,
                    )
                }

                // Live Equalizer wave animation
                LiveEqualizerWave(color = accent)
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
                        text = "Transferring playback…",
                        style =
                            MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        color = CanopyColors.Text,
                    )
                }
            }
        }
    }
}

@Composable
private fun FrostedDeviceRow(
    device: PlaybackDevice,
    onClick: () -> Unit,
    onRename: (() -> Unit)? = null,
    onRemove: (() -> Unit)? = null,
) {
    val shape = RoundedCornerShape(16.dp)

    Surface(
        onClick = onClick,
        shape = shape,
        color = glassFill(CanopyColors.Surface),
        modifier =
            Modifier.fillMaxWidth()
                .glassPane(shape, GlassTone.PANEL)
                .border(1.dp, CanopyColors.Rule, shape),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier =
                    Modifier.size(40.dp)
                        .clip(CircleShape)
                        .background(CanopyColors.Canvas)
                        .border(1.dp, CanopyColors.Rule, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector =
                        when (device.type) {
                            DeviceType.PHONE -> Icons.Rounded.PhoneAndroid
                            DeviceType.COMPUTER -> Icons.Rounded.Computer
                            else -> Icons.Rounded.Devices
                        },
                    contentDescription = null,
                    tint = CanopyColors.MutedStrong,
                    modifier = Modifier.size(20.dp),
                )
            }

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = device.displayName,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = CanopyColors.Text,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val isOnline = device.availability == DeviceAvailability.ONLINE
                    Box(
                        modifier =
                            Modifier.size(7.dp)
                                .clip(CircleShape)
                                .background(
                                    if (isOnline) CanopyColors.Accent else CanopyColors.Muted
                                )
                    )
                    Spacer(Modifier.width(6.dp))
                    val customSubtitle = if (device.customName.isNotBlank()) "(${device.name}) • " else ""
                    Text(
                        text =
                            customSubtitle + when (device.availability) {
                                DeviceAvailability.ONLINE -> "Online • Tap to stream"
                                DeviceAvailability.OFFLINE -> "Offline"
                                else -> "Unavailable"
                            },
                        style = MaterialTheme.typography.bodySmall,
                        color = CanopyColors.Muted,
                    )
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (onRename != null) {
                    IconButton(
                        onClick = onRename,
                        modifier = Modifier.size(34.dp),
                    ) {
                        Icon(
                            Icons.Rounded.Edit,
                            contentDescription = "Rename",
                            tint = CanopyColors.Muted,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }

                if (onRemove != null) {
                    IconButton(
                        onClick = onRemove,
                        modifier = Modifier.size(34.dp),
                    ) {
                        Icon(
                            Icons.Rounded.DeleteOutline,
                            contentDescription = "Forget device",
                            tint = CanopyColors.Danger.copy(alpha = 0.7f),
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }

                // Switch button chip
                Surface(
                    shape = CircleShape,
                    color = glassFill(CanopyColors.Canvas),
                    modifier =
                        Modifier.glassPane(CircleShape, GlassTone.CONTROL)
                            .border(1.dp, CanopyColors.Rule, CircleShape),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(
                            Icons.Rounded.SwapHoriz,
                            contentDescription = null,
                            tint = LocalAccent.current,
                            modifier = Modifier.size(14.dp),
                        )
                        Text(
                            text = "Switch",
                            style =
                                MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = CanopyColors.Text,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FrostedRemoteAudioEngineCard(
    audioEngine: ConnectAudioEngine,
    onPresetSelect: (String) -> Unit,
    onToggleAutoEq: (Boolean) -> Unit,
    onToggleManualEq: (Boolean) -> Unit,
) {
    val shape = RoundedCornerShape(22.dp)
    val accent = LocalAccent.current

    Surface(
        shape = shape,
        color = glassFill(CanopyColors.Surface),
        modifier =
            Modifier.fillMaxWidth()
                .glassPane(shape, GlassTone.PANEL)
                .border(1.dp, CanopyColors.Rule, shape),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Rounded.GraphicEq,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(22.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = "Remote Audio Engine",
                        style =
                            MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        color = CanopyColors.Text,
                    )
                }

                Surface(
                    shape = CircleShape,
                    color = glassFill(CanopyColors.Canvas, accent.copy(alpha = 0.15f)),
                    modifier =
                        Modifier.glassPane(CircleShape, GlassTone.CONTROL)
                            .border(1.dp, CanopyColors.Rule, CircleShape),
                ) {
                    Text(
                        text = "DSP Active",
                        style =
                            MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = accent,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }

            Spacer(Modifier.height(4.dp))
            Text(
                text = "Customize equalizer & headphone compensation running on desktop.",
                style = MaterialTheme.typography.bodyMedium,
                color = CanopyColors.Muted,
            )

            // EQ Presets
            if (audioEngine.presets.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                Text(
                    text = "EQ PRESETS",
                    style =
                        MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.8.sp,
                        ),
                    color = CanopyColors.Eyebrow,
                )
                Spacer(Modifier.height(8.dp))

                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    items(audioEngine.presets) { preset ->
                        val selected = audioEngine.activePreset == preset.value
                        Surface(
                            onClick = { onPresetSelect(preset.value) },
                            shape = CircleShape,
                            color = if (selected) accent else glassFill(CanopyColors.Canvas),
                            modifier =
                                Modifier.then(
                                        if (selected) Modifier
                                        else Modifier.glassPane(CircleShape, GlassTone.CONTROL)
                                    )
                                    .border(
                                        1.dp,
                                        if (selected) accent else CanopyColors.Rule,
                                        CircleShape,
                                    ),
                        ) {
                            Text(
                                text =
                                    preset.label.ifBlank {
                                        preset.value.replaceFirstChar { it.uppercase() }
                                    },
                                color = if (selected) Color.Black else CanopyColors.Text,
                                style =
                                    MaterialTheme.typography.bodyMedium.copy(
                                        fontWeight =
                                            if (selected) FontWeight.Bold else FontWeight.Normal
                                    ),
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // Auto EQ Toggle
            FrostedToggleRow(
                title = "Auto EQ Compensation",
                subtitle = "Automatic profile compensation for connected headphones",
                checked = audioEngine.autoEqEnabled,
                onCheckedChange = onToggleAutoEq,
            )

            Spacer(Modifier.height(10.dp))

            // Manual 10-Band EQ Toggle
            FrostedToggleRow(
                title = "Manual 10-Band EQ",
                subtitle = "Custom parametric frequency curve tuning",
                checked = audioEngine.manualEqEnabled,
                onCheckedChange = onToggleManualEq,
            )

            // Interactive Equalizer Spectrum Visualization
            Spacer(Modifier.height(14.dp))
            FrostedEqualizerSpectrum(
                active = audioEngine.autoEqEnabled || audioEngine.manualEqEnabled
            )
        }
    }
}

@Composable
private fun FrostedToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = CanopyColors.Text,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = CanopyColors.Muted,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors =
                SwitchDefaults.colors(
                    checkedThumbColor = Color.Black,
                    checkedTrackColor = LocalAccent.current,
                    uncheckedThumbColor = CanopyColors.Muted,
                    uncheckedTrackColor = CanopyColors.Canvas,
                    uncheckedBorderColor = CanopyColors.Rule,
                ),
        )
    }
}

@Composable
private fun FrostedEqualizerSpectrum(active: Boolean) {
    val accent = LocalAccent.current
    val infiniteTransition = rememberInfiniteTransition(label = "SpectrumAnim")

    val heights =
        listOf(
            infiniteTransition.animateFloat(
                0.3f,
                0.9f,
                infiniteRepeatable(tween(500, easing = FastOutSlowInEasing), RepeatMode.Reverse),
                label = "h1",
            ),
            infiniteTransition.animateFloat(
                0.5f,
                1.0f,
                infiniteRepeatable(tween(400, easing = FastOutSlowInEasing), RepeatMode.Reverse),
                label = "h2",
            ),
            infiniteTransition.animateFloat(
                0.2f,
                0.7f,
                infiniteRepeatable(tween(650, easing = FastOutSlowInEasing), RepeatMode.Reverse),
                label = "h3",
            ),
            infiniteTransition.animateFloat(
                0.6f,
                0.95f,
                infiniteRepeatable(tween(350, easing = FastOutSlowInEasing), RepeatMode.Reverse),
                label = "h4",
            ),
            infiniteTransition.animateFloat(
                0.4f,
                0.85f,
                infiniteRepeatable(tween(550, easing = FastOutSlowInEasing), RepeatMode.Reverse),
                label = "h5",
            ),
            infiniteTransition.animateFloat(
                0.3f,
                0.75f,
                infiniteRepeatable(tween(450, easing = FastOutSlowInEasing), RepeatMode.Reverse),
                label = "h6",
            ),
            infiniteTransition.animateFloat(
                0.5f,
                0.9f,
                infiniteRepeatable(tween(600, easing = FastOutSlowInEasing), RepeatMode.Reverse),
                label = "h7",
            ),
        )

    Box(
        modifier =
            Modifier.fillMaxWidth()
                .height(38.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(CanopyColors.Canvas)
                .border(1.dp, CanopyColors.Rule, RoundedCornerShape(12.dp))
                .padding(horizontal = 14.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.Bottom,
        ) {
            heights.forEach { animHeight ->
                val fraction = if (active) animHeight.value else 0.25f
                Box(
                    modifier =
                        Modifier.width(6.dp)
                            .height((26 * fraction).dp)
                            .clip(CircleShape)
                            .background(
                                if (active) accent else CanopyColors.Muted.copy(alpha = 0.4f)
                            )
                )
            }
        }
    }
}

@Composable
private fun FrostedPairingPanel(
    input: String,
    onInput: (String) -> Unit,
    onPair: () -> Unit,
    onScan: () -> Unit,
    onDisconnect: () -> Unit,
    hasRemote: Boolean,
) {
    val shape = RoundedCornerShape(22.dp)
    val context = LocalContext.current
    val accent = LocalAccent.current

    Surface(
        shape = shape,
        color = glassFill(CanopyColors.Surface),
        modifier =
            Modifier.fillMaxWidth()
                .glassPane(shape, GlassTone.PANEL)
                .border(1.dp, CanopyColors.Rule, shape),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Rounded.Computer,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = "Pair Orchard Desktop",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = CanopyColors.Text,
                )
            }

            Spacer(Modifier.height(4.dp))
            Text(
                text = "Scan the QR code shown in Orchard desktop, or paste its pairing URL.",
                style = MaterialTheme.typography.bodyMedium,
                color = CanopyColors.Muted,
            )

            Spacer(Modifier.height(14.dp))

            // Primary QR Scanner Button
            Surface(
                onClick = onScan,
                shape = CircleShape,
                color = glassFill(CanopyColors.Canvas, accent.copy(alpha = 0.15f)),
                modifier =
                    Modifier.fillMaxWidth()
                        .height(48.dp)
                        .glassPane(CircleShape, GlassTone.CONTROL)
                        .border(1.dp, accent.copy(alpha = 0.4f), CircleShape),
            ) {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        Icons.Rounded.QrCodeScanner,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = "Scan QR Code with Camera",
                        style =
                            MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = CanopyColors.Text,
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            // Manual Pairing Link Input Field with Paste & Clear actions
            TextField(
                value = input,
                onValueChange = onInput,
                placeholder = { Text("orchard-connect://pair…", color = CanopyColors.Muted) },
                singleLine = true,
                leadingIcon = {
                    Icon(
                        Icons.Rounded.Link,
                        contentDescription = null,
                        tint = if (input.isNotBlank()) accent else CanopyColors.Muted,
                    )
                },
                trailingIcon = {
                    if (input.isNotBlank()) {
                        IconButton(onClick = { onInput("") }) {
                            Icon(
                                Icons.Rounded.Close,
                                contentDescription = "Clear",
                                tint = CanopyColors.Muted,
                            )
                        }
                    } else {
                        IconButton(
                            onClick = {
                                getClipboardText(context)?.takeIf(String::isNotBlank)?.let(onInput)
                            }
                        ) {
                            Icon(
                                Icons.Rounded.ContentPaste,
                                contentDescription = "Paste",
                                tint = accent,
                            )
                        }
                    }
                },
                shape = CircleShape,
                colors =
                    TextFieldDefaults.colors(
                        focusedContainerColor = CanopyColors.Canvas,
                        unfocusedContainerColor = CanopyColors.Canvas,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        focusedTextColor = CanopyColors.Text,
                        unfocusedTextColor = CanopyColors.Text,
                    ),
                modifier = Modifier.fillMaxWidth().border(1.dp, CanopyColors.Rule, CircleShape),
            )

            Spacer(Modifier.height(12.dp))

            // Connect Action Button
            Button(
                onClick = onPair,
                enabled = input.isNotBlank(),
                shape = CircleShape,
                colors =
                    ButtonDefaults.buttonColors(
                        containerColor = accent,
                        contentColor = Color.Black,
                        disabledContainerColor = CanopyColors.Canvas,
                        disabledContentColor = CanopyColors.Muted,
                    ),
                modifier = Modifier.fillMaxWidth().height(44.dp),
            ) {
                Text("Connect", fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }

            // Disconnect Remembered Device Button
            if (hasRemote) {
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = onDisconnect,
                    shape = CircleShape,
                    colors =
                        ButtonDefaults.outlinedButtonColors(contentColor = CanopyColors.Danger),
                    modifier =
                        Modifier.fillMaxWidth()
                            .height(44.dp)
                            .glassPane(CircleShape, GlassTone.CONTROL)
                            .border(1.dp, CanopyColors.Danger.copy(alpha = 0.35f), CircleShape),
                ) {
                    Icon(
                        Icons.Rounded.Close,
                        contentDescription = null,
                        tint = CanopyColors.Danger,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Disconnect Remembered Desktop",
                        fontWeight = FontWeight.SemiBold,
                        color = CanopyColors.Danger,
                    )
                }
            }
        }
    }
}

@Composable
private fun FrostedListeningPartyPanel(
    party: PartyState,
    onCreate: () -> Unit,
    onJoin: (String) -> Unit,
    onLeave: () -> Unit,
) {
    val shape = RoundedCornerShape(22.dp)
    val accent = LocalAccent.current

    Surface(
        shape = shape,
        color = glassFill(CanopyColors.Surface),
        modifier =
            Modifier.fillMaxWidth()
                .glassPane(shape, GlassTone.PANEL)
                .border(1.dp, CanopyColors.Rule, shape),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Rounded.Groups,
                        contentDescription = null,
                        tint = if (party.isActive) accent else CanopyColors.Muted,
                        modifier = Modifier.size(24.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = "Listening Party",
                        style =
                            MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        color = CanopyColors.Text,
                    )
                }

                Surface(
                    shape = CircleShape,
                    color =
                        glassFill(
                            CanopyColors.Canvas,
                            if (party.isActive) accent.copy(alpha = 0.15f) else Color.Transparent,
                        ),
                    modifier =
                        Modifier.glassPane(CircleShape, GlassTone.CONTROL)
                            .border(1.dp, CanopyColors.Rule, CircleShape),
                ) {
                    Text(
                        text = if (party.isActive) "LIVE SESSION" else "SYNC PLAYBACK",
                        style =
                            MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = if (party.isActive) accent else CanopyColors.Muted,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }

            Spacer(Modifier.height(4.dp))

            if (party.isActive) {
                FrostedActiveParty(party = party, onLeave = onLeave)
            } else {
                FrostedInactiveParty(party = party, onCreate = onCreate, onJoin = onJoin)
            }

            if (party.error.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                FrostedMessageBanner(message = party.error, isError = true)
            }
        }
    }
}

@Composable
private fun FrostedInactiveParty(
    party: PartyState,
    onCreate: () -> Unit,
    onJoin: (String) -> Unit,
) {
    var codeInput by remember { mutableStateOf("") }
    val context = LocalContext.current
    val accent = LocalAccent.current

    Text(
        text = "Listen together in real time with synchronized queues and smart crossfade.",
        style = MaterialTheme.typography.bodyMedium,
        color = CanopyColors.Muted,
    )

    Spacer(Modifier.height(14.dp))

    // 6-Character Room Code Field
    TextField(
        value = codeInput,
        onValueChange = { codeInput = cleanRoomCode(it).take(ROOM_CODE_LENGTH) },
        placeholder = { Text("ROOM CODE (e.g. A3F8K2)", color = CanopyColors.Muted) },
        singleLine = true,
        leadingIcon = {
            Icon(
                Icons.Rounded.AutoAwesome,
                contentDescription = null,
                tint = if (codeInput.isNotBlank()) accent else CanopyColors.Muted,
            )
        },
        trailingIcon = {
            if (codeInput.isNotBlank()) {
                IconButton(onClick = { codeInput = "" }) {
                    Icon(
                        Icons.Rounded.Close,
                        contentDescription = "Clear",
                        tint = CanopyColors.Muted,
                    )
                }
            } else {
                IconButton(
                    onClick = {
                        getClipboardText(context)?.takeIf(String::isNotBlank)?.let {
                            codeInput = cleanRoomCode(it).take(ROOM_CODE_LENGTH)
                        }
                    }
                ) {
                    Icon(Icons.Rounded.ContentPaste, contentDescription = "Paste", tint = accent)
                }
            }
        },
        shape = CircleShape,
        colors =
            TextFieldDefaults.colors(
                focusedContainerColor = CanopyColors.Canvas,
                unfocusedContainerColor = CanopyColors.Canvas,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                focusedTextColor = CanopyColors.Text,
                unfocusedTextColor = CanopyColors.Text,
            ),
        modifier = Modifier.fillMaxWidth().border(1.dp, CanopyColors.Rule, CircleShape),
    )

    Spacer(Modifier.height(14.dp))

    // Action Buttons Row: Join Party & Start Party
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(
            onClick = { onJoin(codeInput) },
            enabled = codeInput.isNotBlank() && party.status != PartyStatus.CONNECTING,
            shape = CircleShape,
            colors =
                ButtonDefaults.buttonColors(containerColor = accent, contentColor = Color.Black),
            modifier = Modifier.weight(1f).height(44.dp),
        ) {
            Text("Join Party", fontWeight = FontWeight.Bold, fontSize = 14.sp)
        }

        OutlinedButton(
            onClick = onCreate,
            enabled = party.status != PartyStatus.CONNECTING,
            shape = CircleShape,
            modifier =
                Modifier.weight(1f)
                    .height(44.dp)
                    .glassPane(CircleShape, GlassTone.CONTROL)
                    .border(1.dp, CanopyColors.Rule, CircleShape),
        ) {
            Text(
                "Host a Party",
                fontWeight = FontWeight.Bold,
                color = CanopyColors.Text,
                fontSize = 14.sp,
            )
        }
    }
}

@Composable
private fun FrostedActiveParty(party: PartyState, onLeave: () -> Unit) {
    val context = LocalContext.current
    val accent = LocalAccent.current

    // Session Status Label
    val statusText =
        when (party.status) {
            PartyStatus.CONNECTING -> "Connecting to room…"
            PartyStatus.OFFLINE -> "Reconnecting to listening party…"
            else ->
                if (party.isHost) "Hosting Session • All listeners follow your queue"
                else "Synced • Following host's playback"
        }

    Text(
        text = statusText,
        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
        color = if (party.status == PartyStatus.CONNECTED) accent else CanopyColors.Muted,
    )

    if (party.code.isNotBlank()) {
        Spacer(Modifier.height(14.dp))

        // Large Room Code Display Card with Copy & Share Actions
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = glassFill(CanopyColors.Canvas),
            modifier =
                Modifier.fillMaxWidth()
                    .glassPane(RoundedCornerShape(16.dp), GlassTone.CONTROL)
                    .border(1.dp, CanopyColors.Rule, RoundedCornerShape(16.dp)),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "ROOM CODE",
                    style =
                        MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.2.sp,
                        ),
                    color = CanopyColors.Eyebrow,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = party.code.toCharArray().joinToString("  "),
                    style =
                        MaterialTheme.typography.displayLarge.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 32.sp,
                            letterSpacing = 4.sp,
                            fontFamily = FontFamily.Monospace,
                        ),
                    color = CanopyColors.Text,
                )

                Spacer(Modifier.height(12.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    // Copy Code Button
                    Surface(
                        onClick = {
                            copyToClipboard(context, party.code, label = "Orchard Room Code")
                            Toast.makeText(
                                    context,
                                    "Room code copied to clipboard",
                                    Toast.LENGTH_SHORT,
                                )
                                .show()
                        },
                        shape = CircleShape,
                        color = glassFill(CanopyColors.Surface),
                        modifier =
                            Modifier.weight(1f)
                                .height(38.dp)
                                .glassPane(CircleShape, GlassTone.CONTROL)
                                .border(1.dp, CanopyColors.Rule, CircleShape),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxSize(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            Icon(
                                Icons.Rounded.ContentCopy,
                                contentDescription = null,
                                tint = accent,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "Copy Code",
                                style =
                                    MaterialTheme.typography.labelMedium.copy(
                                        fontWeight = FontWeight.Bold
                                    ),
                                color = CanopyColors.Text,
                            )
                        }
                    }

                    // Share Link Button
                    Surface(
                        onClick = {
                            val shareUrl =
                                party.room?.let { room -> room.shareUrl.ifBlank { room.joinUrl } }
                                    ?: "orchard-party://join/${party.code}"
                            val sendIntent =
                                Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(
                                        Intent.EXTRA_TEXT,
                                        "Join my Orchard listening party! Room code: ${party.code}\n$shareUrl",
                                    )
                                }
                            context.startActivity(
                                Intent.createChooser(sendIntent, "Share listening party link")
                            )
                        },
                        shape = CircleShape,
                        color = glassFill(CanopyColors.Surface),
                        modifier =
                            Modifier.weight(1f)
                                .height(38.dp)
                                .glassPane(CircleShape, GlassTone.CONTROL)
                                .border(1.dp, CanopyColors.Rule, CircleShape),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxSize(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            Icon(
                                Icons.Rounded.Share,
                                contentDescription = null,
                                tint = accent,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "Share",
                                style =
                                    MaterialTheme.typography.labelMedium.copy(
                                        fontWeight = FontWeight.Bold
                                    ),
                                color = CanopyColors.Text,
                            )
                        }
                    }
                }
            }
        }
    }

    // Connected Peer Listeners Roster
    Spacer(Modifier.height(14.dp))
    Text(
        text =
            when (party.peers.size) {
                0 -> "No other listeners in the room yet"
                1 -> "1 other listener connected"
                else -> "${party.peers.size} other listeners connected"
            },
        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
        color = CanopyColors.Muted,
    )

    party.peers.forEach { peer ->
        Spacer(Modifier.height(6.dp))
        FrostedPeerRow(peer = peer)
    }

    Spacer(Modifier.height(16.dp))

    // Leave or End Party Button
    OutlinedButton(
        onClick = onLeave,
        shape = CircleShape,
        colors = ButtonDefaults.outlinedButtonColors(contentColor = CanopyColors.Danger),
        modifier =
            Modifier.fillMaxWidth()
                .height(44.dp)
                .glassPane(CircleShape, GlassTone.CONTROL)
                .border(1.dp, CanopyColors.Danger.copy(alpha = 0.35f), CircleShape),
    ) {
        Text(
            text = if (party.isHost) "End Party (Close Room)" else "Leave Party",
            color = CanopyColors.Danger,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun FrostedPeerRow(peer: PartyPeer) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = glassFill(CanopyColors.Canvas),
        modifier =
            Modifier.fillMaxWidth()
                .glassPane(RoundedCornerShape(12.dp), GlassTone.CONTROL)
                .border(1.dp, CanopyColors.Rule, RoundedCornerShape(12.dp)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Rounded.PhoneAndroid,
                contentDescription = null,
                tint = if (peer.open) LocalAccent.current else CanopyColors.Muted,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = peer.name.ifBlank { "Listener" },
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = CanopyColors.Text,
                modifier = Modifier.weight(1f),
            )

            // WebRTC live sync dot
            Box(
                modifier =
                    Modifier.size(8.dp)
                        .clip(CircleShape)
                        .background(if (peer.open) LocalAccent.current else CanopyColors.Muted)
            )

            if (peer.role == PartyRole.HOST) {
                Spacer(Modifier.width(8.dp))
                Surface(shape = CircleShape, color = LocalAccent.current) {
                    Text(
                        text = "HOST",
                        style =
                            MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 10.sp,
                            ),
                        color = Color.Black,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun FrostedSectionHeader(title: String, badge: String? = null) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = CanopyColors.Text,
        )
        badge?.let {
            Text(text = it, style = MaterialTheme.typography.labelSmall, color = CanopyColors.Muted)
        }
    }
}

@Composable
private fun FrostedMessageBanner(message: String, isError: Boolean = false) {
    val tint = if (isError) CanopyColors.Danger else LocalAccent.current
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = glassFill(CanopyColors.Surface, tint.copy(alpha = 0.12f)),
        modifier =
            Modifier.fillMaxWidth()
                .glassPane(RoundedCornerShape(14.dp), GlassTone.PANEL)
                .border(1.dp, tint.copy(alpha = 0.35f), RoundedCornerShape(14.dp)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Rounded.Info,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = CanopyColors.Text,
            )
        }
    }
}

@Composable
private fun LiveEqualizerWave(color: Color) {
    val infiniteTransition = rememberInfiniteTransition(label = "LiveEqWave")
    val b1 =
        infiniteTransition.animateFloat(
            0.3f,
            0.9f,
            infiniteRepeatable(tween(450, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "w1",
        )
    val b2 =
        infiniteTransition.animateFloat(
            0.7f,
            0.3f,
            infiniteRepeatable(tween(350, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "w2",
        )
    val b3 =
        infiniteTransition.animateFloat(
            0.4f,
            1.0f,
            infiniteRepeatable(tween(550, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "w3",
        )
    val b4 =
        infiniteTransition.animateFloat(
            0.9f,
            0.4f,
            infiniteRepeatable(tween(400, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "w4",
        )

    Row(
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.Bottom,
        modifier = Modifier.height(20.dp),
    ) {
        listOf(b1, b2, b3, b4).forEach { anim ->
            Box(
                modifier =
                    Modifier.width(3.5.dp)
                        .height((20 * anim.value).dp)
                        .clip(CircleShape)
                        .background(color)
            )
        }
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// Helpers for Clipboard Actions
// ──────────────────────────────────────────────────────────────────────────────

private fun copyToClipboard(context: Context, text: String, label: String = "Orchard") {
    val clipboard =
        context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
}

private fun getClipboardText(context: Context): String? {
    val clipboard =
        context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return null
    val clip = clipboard.primaryClip ?: return null
    if (clip.itemCount > 0) {
        return clip.getItemAt(0).text?.toString()
    }
    return null
}

@Composable
private fun StandardDevicesScreenContent(
    targets: PlaybackTargetState,
    connectMessage: String,
    protocolVersion: Int,
    audioEngine: ConnectAudioEngine,
    party: PartyState,
    onBack: () -> Unit,
    onSelect: (PlaybackTarget) -> Unit,
    onPair: (String) -> Unit,
    onDisconnect: () -> Unit,
    onPresetSelect: (String) -> Unit,
    onToggleAutoEq: (Boolean) -> Unit,
    onToggleManualEq: (Boolean) -> Unit,
    onCreateParty: () -> Unit,
    onJoinParty: (String) -> Unit,
    onLeaveParty: () -> Unit,
    onRenameDevice: (PlaybackDevice, String) -> Unit,
    onRemoveDevice: (String) -> Unit,
) {
    var pairingInput by remember { mutableStateOf("") }
    var deviceToRename by remember { mutableStateOf<PlaybackDevice?>(null) }
    val context = LocalContext.current
    val scanner =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result
            ->
            if (result.resultCode == Activity.RESULT_OK) {
                result.data?.getStringExtra("SCAN_RESULT")?.takeIf(String::isNotBlank)?.let {
                    pairingInput = it
                    onPair(it)
                }
            }
        }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = CanopyColors.Text)
            }
            Text(
                "Choose a device",
                style =
                    MaterialTheme.typography.displayLarge.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 24.sp,
                    ),
                color = CanopyColors.Text,
            )
        }
        Text(
            "Select where music plays. Return here anytime to pull audio back to this " +
                "${context.selfDeviceWord()}.",
            color = CanopyColors.Muted,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        )
        Spacer(Modifier.height(10.dp))
        targets.devices.forEach { device ->
            StandardDeviceRow(
                device,
                onClick = {
                    onSelect(
                        if (device.isLocal) PlaybackTarget.LocalPhone
                        else PlaybackTarget.Remote(device.id)
                    )
                },
                onRename = { deviceToRename = device },
                onRemove = if (!device.isLocal) { { onRemoveDevice(device.id) } } else null,
            )
        }
        if (targets.isTransferring) {
            Text(
                "Transferring playback…",
                color = LocalAccent.current,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(16.dp),
            )
        }
        val message = targets.message.ifBlank { connectMessage }
        if (message.isNotBlank()) {
            Text(
                message,
                color = CanopyColors.Muted,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }

        if (protocolVersion >= 2 && targets.selected is PlaybackTarget.Remote) {
            StandardRemoteAudioEngineCard(
                audioEngine = audioEngine,
                onPresetSelect = onPresetSelect,
                onToggleAutoEq = onToggleAutoEq,
                onToggleManualEq = onToggleManualEq,
            )
        }

        StandardPairingPanel(
            input = pairingInput,
            onInput = { pairingInput = it },
            onPair = { onPair(pairingInput) },
            onScan = {
                scanner.launch(
                    Intent(context, PairingScanActivity::class.java).apply {
                        action = "com.google.zxing.client.android.SCAN"
                        putExtra("SCAN_FORMATS", "QR_CODE")
                    }
                )
            },
            onDisconnect = onDisconnect,
            hasRemote = targets.devices.any { !it.isLocal },
        )

        StandardListeningPartyPanel(
            party = party,
            onCreate = onCreateParty,
            onJoin = onJoinParty,
            onLeave = onLeaveParty,
        )
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
private fun StandardListeningPartyPanel(
    party: PartyState,
    onCreate: () -> Unit,
    onJoin: (String) -> Unit,
    onLeave: () -> Unit,
) {
    var codeInput by remember { mutableStateOf("") }
    val shape = RoundedCornerShape(20.dp)
    Surface(
        color = glassFill(CanopyColors.Surface),
        shape = shape,
        modifier =
            Modifier.fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp)
                .glassPane(shape),
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Rounded.Groups,
                    contentDescription = null,
                    tint = if (party.isActive) LocalAccent.current else CanopyColors.Muted,
                    modifier = Modifier.size(24.dp),
                )
                Text(
                    "Listening party",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = CanopyColors.Text,
                    modifier = Modifier.padding(start = 10.dp),
                )
            }
            Spacer(Modifier.height(4.dp))

            if (party.isActive) {
                StandardActiveParty(party = party, onLeave = onLeave)
            } else {
                Text(
                    "Play the same music, in time, with anyone on Orchard.",
                    color = CanopyColors.Muted,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(14.dp))
                TextField(
                    value = codeInput,
                    onValueChange = { codeInput = cleanRoomCode(it).take(ROOM_CODE_LENGTH) },
                    placeholder = { Text("Room code") },
                    singleLine = true,
                    shape = CircleShape,
                    colors =
                        TextFieldDefaults.colors(
                            focusedContainerColor = CanopyColors.Canvas,
                            unfocusedContainerColor = CanopyColors.Canvas,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            focusedTextColor = CanopyColors.Text,
                            unfocusedTextColor = CanopyColors.Text,
                        ),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    Modifier.fillMaxWidth().padding(top = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Button(
                        onClick = { onJoin(codeInput) },
                        enabled = codeInput.isNotBlank() && party.status != PartyStatus.CONNECTING,
                        shape = CircleShape,
                        colors =
                            ButtonDefaults.buttonColors(
                                containerColor = LocalAccent.current,
                                contentColor = Color.Black,
                            ),
                        modifier = Modifier.weight(1f).height(44.dp),
                    ) {
                        Text("Join", fontWeight = FontWeight.Bold)
                    }
                    OutlinedButton(
                        onClick = onCreate,
                        enabled = party.status != PartyStatus.CONNECTING,
                        shape = CircleShape,
                        modifier = Modifier.weight(1f).height(44.dp),
                    ) {
                        Text(
                            "Start a party",
                            fontWeight = FontWeight.Bold,
                            color = CanopyColors.Text,
                        )
                    }
                }
            }

            if (party.error.isNotBlank()) {
                Text(
                    party.error,
                    color = CanopyColors.Danger,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun StandardActiveParty(party: PartyState, onLeave: () -> Unit) {
    Text(
        when (party.status) {
            PartyStatus.CONNECTING -> "Connecting…"
            PartyStatus.OFFLINE -> "Reconnecting to the party…"
            else -> if (party.isHost) "You are hosting" else "Following the host"
        },
        color =
            if (party.status == PartyStatus.CONNECTED) LocalAccent.current else CanopyColors.Muted,
        style = MaterialTheme.typography.bodyMedium,
    )

    if (party.code.isNotBlank()) {
        Spacer(Modifier.height(14.dp))
        Text("Room code", color = CanopyColors.Muted, style = MaterialTheme.typography.labelLarge)
        Text(
            party.code.toCharArray().joinToString(" "),
            style =
                MaterialTheme.typography.displayLarge.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 28.sp,
                    letterSpacing = 2.sp,
                ),
            color = CanopyColors.Text,
        )
    }

    Spacer(Modifier.height(12.dp))
    Text(
        when (party.peers.size) {
            0 -> "No one else has joined yet."
            1 -> "1 other listener"
            else -> "${party.peers.size} other listeners"
        },
        color = CanopyColors.Muted,
        style = MaterialTheme.typography.bodyMedium,
    )
    party.peers.forEach { peer ->
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Rounded.PhoneAndroid,
                contentDescription = null,
                tint = if (peer.open) LocalAccent.current else CanopyColors.Muted,
                modifier = Modifier.size(18.dp),
            )
            Text(
                peer.name.ifBlank { "Listener" },
                color = CanopyColors.Text,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f).padding(start = 10.dp),
            )
            if (peer.role == PartyRole.HOST) {
                Text(
                    "Host",
                    color = LocalAccent.current,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }

    OutlinedButton(
        onClick = onLeave,
        shape = CircleShape,
        modifier = Modifier.fillMaxWidth().padding(top = 14.dp).height(44.dp),
    ) {
        Text(
            if (party.isHost) "End the party" else "Leave the party",
            color = CanopyColors.Danger,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun StandardRemoteAudioEngineCard(
    audioEngine: ConnectAudioEngine,
    onPresetSelect: (String) -> Unit,
    onToggleAutoEq: (Boolean) -> Unit,
    onToggleManualEq: (Boolean) -> Unit,
) {
    val shape = RoundedCornerShape(20.dp)
    Surface(
        color = glassFill(CanopyColors.Surface),
        shape = shape,
        modifier =
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).glassPane(shape),
    ) {
        Column(Modifier.padding(20.dp)) {
            Text(
                "Remote Audio Engine",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = CanopyColors.Text,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Customize EQ and presets running on Orchard desktop.",
                color = CanopyColors.Muted,
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(14.dp))

            if (audioEngine.presets.isNotEmpty()) {
                Text(
                    "EQ Presets",
                    style =
                        MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = CanopyColors.Text,
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    audioEngine.presets.take(4).forEach { preset ->
                        val selected = audioEngine.activePreset == preset.value
                        Surface(
                            onClick = { onPresetSelect(preset.value) },
                            color = if (selected) LocalAccent.current else CanopyColors.Canvas,
                            shape = CircleShape,
                        ) {
                            Text(
                                text = preset.label.ifBlank { preset.value },
                                color = if (selected) Color.Black else CanopyColors.Text,
                                style =
                                    MaterialTheme.typography.bodyMedium.copy(
                                        fontWeight =
                                            if (selected) FontWeight.Bold else FontWeight.Normal
                                    ),
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                            )
                        }
                    }
                }
                Spacer(Modifier.height(14.dp))
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Auto EQ",
                        style = MaterialTheme.typography.titleMedium,
                        color = CanopyColors.Text,
                    )
                    Text(
                        "Automatic headphone compensation",
                        style = MaterialTheme.typography.bodySmall,
                        color = CanopyColors.Muted,
                    )
                }
                Switch(checked = audioEngine.autoEqEnabled, onCheckedChange = onToggleAutoEq)
            }

            Spacer(Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Manual 10-Band EQ",
                        style = MaterialTheme.typography.titleMedium,
                        color = CanopyColors.Text,
                    )
                    Text(
                        "Custom frequency tuning",
                        style = MaterialTheme.typography.bodySmall,
                        color = CanopyColors.Muted,
                    )
                }
                Switch(checked = audioEngine.manualEqEnabled, onCheckedChange = onToggleManualEq)
            }
        }
    }
}

@Composable
private fun StandardDeviceRow(
    device: PlaybackDevice,
    onClick: () -> Unit,
    onRename: (() -> Unit)? = null,
    onRemove: (() -> Unit)? = null,
) {
    val shape = RoundedCornerShape(16.dp)
    Surface(
        onClick = onClick,
        color =
            if (device.isActive) {
                glassFill(CanopyColors.SurfaceHover, LocalAccent.current.copy(alpha = 0.16f))
            } else {
                glassFill(CanopyColors.Surface)
            },
        shape = shape,
        modifier =
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).glassPane(shape),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                when (device.type) {
                    DeviceType.PHONE -> Icons.Rounded.PhoneAndroid
                    DeviceType.COMPUTER -> Icons.Rounded.Computer
                    else -> Icons.Rounded.Devices
                },
                contentDescription = null,
                tint = if (device.isActive) LocalAccent.current else CanopyColors.Muted,
                modifier = Modifier.size(32.dp),
            )
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Text(
                    device.displayName,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = CanopyColors.Text,
                )
                val customPrefix = if (device.customName.isNotBlank()) "(${device.name}) • " else ""
                Text(
                    customPrefix + when {
                        device.isActive -> "Playing here"
                        device.availability == DeviceAvailability.ONLINE -> "Available"
                        device.availability == DeviceAvailability.OFFLINE -> "Offline"
                        else -> "Unavailable"
                    },
                    color = if (device.isActive) LocalAccent.current else CanopyColors.Muted,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (onRename != null) {
                IconButton(onClick = onRename, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.Rounded.Edit,
                        contentDescription = "Rename",
                        tint = CanopyColors.Muted,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            if (onRemove != null) {
                IconButton(onClick = onRemove, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.Rounded.DeleteOutline,
                        contentDescription = "Forget device",
                        tint = CanopyColors.Danger.copy(alpha = 0.7f),
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            if (device.isActive) {
                Icon(
                    Icons.Rounded.CheckCircle,
                    "Active device",
                    tint = LocalAccent.current,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
    }
}

@Composable
private fun StandardPairingPanel(
    input: String,
    onInput: (String) -> Unit,
    onPair: () -> Unit,
    onScan: () -> Unit,
    onDisconnect: () -> Unit,
    hasRemote: Boolean,
) {
    val shape = RoundedCornerShape(20.dp)
    Surface(
        color = glassFill(CanopyColors.Surface),
        shape = shape,
        modifier = Modifier.fillMaxWidth().padding(16.dp).glassPane(shape),
    ) {
        Column(Modifier.padding(20.dp)) {
            Text(
                "Add an Orchard device",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = CanopyColors.Text,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Scan the QR code shown by Orchard desktop, or paste its pairing link.",
                color = CanopyColors.Muted,
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(14.dp))
            TextField(
                value = input,
                onValueChange = onInput,
                placeholder = { Text("orchard-connect://pair…") },
                singleLine = true,
                shape = CircleShape,
                colors =
                    TextFieldDefaults.colors(
                        focusedContainerColor = CanopyColors.Canvas,
                        unfocusedContainerColor = CanopyColors.Canvas,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        focusedTextColor = CanopyColors.Text,
                        unfocusedTextColor = CanopyColors.Text,
                    ),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                Modifier.fillMaxWidth().padding(top = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Button(
                    onClick = onPair,
                    enabled = input.isNotBlank(),
                    shape = CircleShape,
                    colors =
                        ButtonDefaults.buttonColors(
                            containerColor = LocalAccent.current,
                            contentColor = Color.Black,
                        ),
                    modifier = Modifier.weight(1f).height(44.dp),
                ) {
                    Text("Connect", fontWeight = FontWeight.Bold)
                }
                OutlinedButton(
                    onClick = onScan,
                    shape = CircleShape,
                    modifier = Modifier.weight(1f).height(44.dp),
                ) {
                    Icon(
                        Icons.Rounded.QrCodeScanner,
                        contentDescription = null,
                        tint = LocalAccent.current,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("Scan", fontWeight = FontWeight.Bold, color = CanopyColors.Text)
                }
            }
            if (hasRemote) {
                OutlinedButton(
                    onClick = onDisconnect,
                    shape = CircleShape,
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp).height(44.dp),
                ) {
                    Text("Disconnect remembered device", color = CanopyColors.Danger)
                }
            }
        }
    }
}

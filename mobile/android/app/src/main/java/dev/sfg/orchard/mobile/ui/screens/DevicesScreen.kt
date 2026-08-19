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
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import dev.sfg.orchard.mobile.audio.selfDeviceWord
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sfg.orchard.connect.ui.PairingScanActivity
import dev.sfg.orchard.mobile.model.DeviceAvailability
import dev.sfg.orchard.mobile.model.DeviceType
import dev.sfg.orchard.mobile.model.PlaybackDevice
import dev.sfg.orchard.mobile.model.PlaybackTarget
import dev.sfg.orchard.mobile.model.PlaybackTargetState
import dev.sfg.orchard.mobile.social.PartyRole
import dev.sfg.orchard.mobile.social.PartyState
import dev.sfg.orchard.mobile.social.PartyStatus
import dev.sfg.orchard.mobile.social.cleanRoomCode
import dev.sfg.orchard.mobile.ui.theme.CanopyColors
import dev.sfg.orchard.mobile.ui.theme.LocalAccent

@Composable
fun DevicesScreen(
    targets: PlaybackTargetState,
    connectMessage: String,
    protocolVersion: Int = 1,
    audioEngine: dev.sfg.orchard.connect.protocol.ConnectAudioEngine = dev.sfg.orchard.connect.protocol.ConnectAudioEngine(),
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
) {
    var pairingInput by remember { mutableStateOf("") }
    val context = LocalContext.current
    val scanner = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
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
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = CanopyColors.Text)
            }
            Text(
                "Choose a device",
                style = MaterialTheme.typography.displayLarge.copy(fontWeight = FontWeight.Bold, fontSize = 24.sp),
                color = CanopyColors.Text
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
            DeviceRow(device, onClick = {
                onSelect(if (device.isLocal) PlaybackTarget.LocalPhone else PlaybackTarget.Remote(device.id))
            })
        }
        if (targets.isTransferring) {
            Text(
                "Transferring playback…",
                color = LocalAccent.current,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(16.dp)
            )
        }
        val message = targets.message.ifBlank { connectMessage }
        if (message.isNotBlank()) {
            Text(
                message,
                color = CanopyColors.Muted,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }

        if (protocolVersion >= 2 && targets.selected is PlaybackTarget.Remote) {
            RemoteAudioEngineCard(
                audioEngine = audioEngine,
                onPresetSelect = onPresetSelect,
                onToggleAutoEq = onToggleAutoEq,
                onToggleManualEq = onToggleManualEq,
            )
        }

        PairingPanel(
            input = pairingInput,
            onInput = { pairingInput = it },
            onPair = { onPair(pairingInput) },
            onScan = {
                scanner.launch(Intent(context, PairingScanActivity::class.java).apply {
                    action = "com.google.zxing.client.android.SCAN"
                    putExtra("SCAN_FORMATS", "QR_CODE")
                })
            },
            onDisconnect = onDisconnect,
            hasRemote = targets.devices.any { !it.isLocal },
        )

        ListeningPartyPanel(
            party = party,
            onCreate = onCreateParty,
            onJoin = onJoinParty,
            onLeave = onLeaveParty,
        )
    }
}

/**
 * Create/join controls for a listening party.
 *
 * It sits with the device pickers rather than in the player because it answers the same question
 * they do — where, and with whom, this music is playing — and a listener who wants to move audio
 * somewhere else looks here first.
 */
@Composable
private fun ListeningPartyPanel(
    party: PartyState,
    onCreate: () -> Unit,
    onJoin: (String) -> Unit,
    onLeave: () -> Unit,
) {
    var codeInput by remember { mutableStateOf("") }
    Surface(
        color = CanopyColors.Surface,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp),
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
                ActiveParty(party = party, onLeave = onLeave)
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
                    colors = TextFieldDefaults.colors(
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
                        colors = ButtonDefaults.buttonColors(
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
                        Text("Start a party", fontWeight = FontWeight.Bold, color = CanopyColors.Text)
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
private fun ActiveParty(party: PartyState, onLeave: () -> Unit) {
    Text(
        when (party.status) {
            PartyStatus.CONNECTING -> "Connecting…"
            PartyStatus.OFFLINE -> "Reconnecting to the party…"
            // The host is the device the rest of the room follows, which is worth stating plainly:
            // it is the difference between a control moving everyone and only asking.
            else -> if (party.isHost) "You are hosting" else "Following the host"
        },
        color = if (party.status == PartyStatus.CONNECTED) LocalAccent.current else CanopyColors.Muted,
        style = MaterialTheme.typography.bodyMedium,
    )

    if (party.code.isNotBlank()) {
        Spacer(Modifier.height(14.dp))
        Text("Room code", color = CanopyColors.Muted, style = MaterialTheme.typography.labelLarge)
        Text(
            // Spaced out because this code gets read aloud and typed on another device.
            party.code.toCharArray().joinToString(" "),
            style = MaterialTheme.typography.displayLarge.copy(
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
                Text("Host", color = LocalAccent.current, style = MaterialTheme.typography.labelLarge)
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

/** Room codes the worker generates are always this long. */
private const val ROOM_CODE_LENGTH = 6

@Composable
private fun RemoteAudioEngineCard(
    audioEngine: dev.sfg.orchard.connect.protocol.ConnectAudioEngine,
    onPresetSelect: (String) -> Unit,
    onToggleAutoEq: (Boolean) -> Unit,
    onToggleManualEq: (Boolean) -> Unit,
) {
    Surface(
        color = CanopyColors.Surface,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
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
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
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
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
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
                    Text("Auto EQ", style = MaterialTheme.typography.titleMedium, color = CanopyColors.Text)
                    Text("Automatic headphone compensation", style = MaterialTheme.typography.bodySmall, color = CanopyColors.Muted)
                }
                androidx.compose.material3.Switch(
                    checked = audioEngine.autoEqEnabled,
                    onCheckedChange = onToggleAutoEq,
                )
            }

            Spacer(Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Manual 10-Band EQ", style = MaterialTheme.typography.titleMedium, color = CanopyColors.Text)
                    Text("Custom frequency tuning", style = MaterialTheme.typography.bodySmall, color = CanopyColors.Muted)
                }
                androidx.compose.material3.Switch(
                    checked = audioEngine.manualEqEnabled,
                    onCheckedChange = onToggleManualEq,
                )
            }
        }
    }
}

@Composable
private fun DeviceRow(device: PlaybackDevice, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        color = if (device.isActive) CanopyColors.SurfaceHover else CanopyColors.Surface,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
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
                    device.name,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = CanopyColors.Text
                )
                Text(
                    when {
                        device.isActive -> "Playing here"
                        device.availability == DeviceAvailability.ONLINE -> "Available"
                        device.availability == DeviceAvailability.OFFLINE -> "Offline"
                        else -> "Unavailable"
                    },
                    color = if (device.isActive) LocalAccent.current else CanopyColors.Muted,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (device.isActive) {
                Icon(Icons.Rounded.CheckCircle, "Active device", tint = LocalAccent.current, modifier = Modifier.size(24.dp))
            }
        }
    }
}

@Composable
private fun PairingPanel(
    input: String,
    onInput: (String) -> Unit,
    onPair: () -> Unit,
    onScan: () -> Unit,
    onDisconnect: () -> Unit,
    hasRemote: Boolean,
) {
    Surface(
        color = CanopyColors.Surface,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth().padding(16.dp),
    ) {
        Column(Modifier.padding(20.dp)) {
            Text(
                "Add an Orchard device",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = CanopyColors.Text
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Scan the QR code shown by Orchard desktop, or paste its pairing link.",
                color = CanopyColors.Muted,
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(14.dp))
            TextField(
                value = input,
                onValueChange = onInput,
                placeholder = { Text("orchard-connect://pair…") },
                singleLine = true,
                shape = CircleShape,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = CanopyColors.Canvas,
                    unfocusedContainerColor = CanopyColors.Canvas,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    focusedTextColor = CanopyColors.Text,
                    unfocusedTextColor = CanopyColors.Text,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = onPair,
                    enabled = input.isNotBlank(),
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = LocalAccent.current, contentColor = Color.Black),
                    modifier = Modifier.weight(1f).height(44.dp)
                ) {
                    Text("Connect", fontWeight = FontWeight.Bold)
                }
                OutlinedButton(
                    onClick = onScan,
                    shape = CircleShape,
                    modifier = Modifier.weight(1f).height(44.dp)
                ) {
                    Icon(Icons.Rounded.QrCodeScanner, contentDescription = null, tint = LocalAccent.current)
                    Spacer(Modifier.width(6.dp))
                    Text("Scan", fontWeight = FontWeight.Bold, color = CanopyColors.Text)
                }
            }
            if (hasRemote) {
                OutlinedButton(
                    onClick = onDisconnect,
                    shape = CircleShape,
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp).height(44.dp)
                ) {
                    Text("Disconnect remembered device", color = CanopyColors.Danger)
                }
            }
        }
    }
}

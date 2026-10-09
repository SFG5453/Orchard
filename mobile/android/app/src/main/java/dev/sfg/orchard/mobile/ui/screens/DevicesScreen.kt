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

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.DevicesFold
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.TabletMac
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import dev.sfg.orchard.mobile.audio.isFoldableHardware
import dev.sfg.orchard.mobile.audio.isTabletForm
import dev.sfg.orchard.mobile.connect.ConnectState
import dev.sfg.orchard.mobile.model.DeviceType
import dev.sfg.orchard.mobile.model.PlaybackDevice
import dev.sfg.orchard.mobile.model.PlaybackTarget
import dev.sfg.orchard.mobile.model.PlaybackTargetState
import dev.sfg.orchard.mobile.social.PartyState
import dev.sfg.orchard.mobile.ui.glass.GlassTone
import dev.sfg.orchard.mobile.ui.glass.glassPane
import dev.sfg.orchard.mobile.ui.theme.CanopyColors
import dev.sfg.orchard.mobile.ui.theme.LocalAccent

/** Room codes the listening party worker generates are always 6 characters. */
internal const val ROOM_CODE_LENGTH = 6

/**
 * Connect & Devices popup, shown over the full player so output routing, Orchard Connect and
 * listening parties never navigate away from playback.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicesSheet(
    targets: PlaybackTargetState,
    connect: ConnectState,
    party: PartyState = PartyState(),
    onDismiss: () -> Unit,
    onSelect: (PlaybackTarget) -> Unit,
    onCreateParty: () -> Unit = {},
    onJoinParty: (String) -> Unit = {},
    onLeaveParty: () -> Unit = {},
    onRenameDevice: (PlaybackDevice, String) -> Unit = { _, _ -> },
) {
    val accent = lerp(LocalAccent.current, Color.White, 0.35f)
    CompositionLocalProvider(LocalAccent provides accent) {
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = CanopyColors.Surface,
            dragHandle = { BottomSheetDefaults.DragHandle(color = CanopyColors.Muted.copy(alpha = 0.4f)) },
        ) {
            FrostedDevicesScreenContent(
                targets = targets,
                connect = connect,
                party = party,
                onSelect = onSelect,
                onCreateParty = onCreateParty,
                onJoinParty = onJoinParty,
                onLeaveParty = onLeaveParty,
                onRenameDevice = onRenameDevice,
            )
        }
    }
}

// ══════════════════════════════════════════════════════════════════════════════
// CONNECT SCREEN
// ══════════════════════════════════════════════════════════════════════════════

@Composable
fun RenameDeviceDialog(device: PlaybackDevice, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var nameInput by remember(device) { mutableStateOf(device.displayName) }
    val accent = LocalAccent.current
    val shape = RoundedCornerShape(24.dp)
    val hasCustomName = device.customName.isNotBlank()

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = shape,
            color = Color.Transparent,
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
                        imageVector = getDeviceIcon(device),
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(24.dp),
                    )
                }

                Spacer(Modifier.height(14.dp))

                Text(
                    text = "Rename This Device",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = CanopyColors.Text,
                )

                Spacer(Modifier.height(4.dp))

                Text(
                    text = "Set a custom name for this device in Connect and Listening Parties.",
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

internal fun copyToClipboard(context: Context, text: String, label: String = "Orchard") {
    val clipboard =
        context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
}

internal fun getClipboardText(context: Context): String? {
    val clipboard =
        context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return null
    val clip = clipboard.primaryClip ?: return null
    if (clip.itemCount > 0) {
        return clip.getItemAt(0).text?.toString()
    }
    return null
}

@Composable
internal fun getDeviceIcon(device: PlaybackDevice): androidx.compose.ui.graphics.vector.ImageVector {
    val context = androidx.compose.ui.platform.LocalContext.current
    return if (device.isLocal && context.isFoldableHardware()) {
        Icons.Rounded.DevicesFold
    } else if (device.isLocal && context.isTabletForm()) {
        Icons.Rounded.TabletMac
    } else {
        when (device.type) {
            DeviceType.PHONE -> Icons.Rounded.PhoneAndroid
            DeviceType.COMPUTER -> Icons.Rounded.Computer
            else -> Icons.Rounded.Devices
        }
    }
}

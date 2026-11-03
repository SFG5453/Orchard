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

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.DevicesFold
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.Earbuds
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Headset
import androidx.compose.material.icons.rounded.Hearing
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Speaker
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material.icons.rounded.TabletAndroid
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material.icons.rounded.Usb
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sfg.orchard.mobile.audio.AudioOutputType
import dev.sfg.orchard.mobile.audio.canReadBluetoothNames
import dev.sfg.orchard.mobile.audio.isFoldableHardware
import dev.sfg.orchard.mobile.audio.isTabletForm
import dev.sfg.orchard.mobile.audio.rememberAudioOutput
import dev.sfg.orchard.mobile.model.PlaybackTarget
import dev.sfg.orchard.mobile.model.PlaybackTargetState

/** Bottom quick destinations bar with player tools, active output route, and queue badge count. */
@Composable
fun PlayerBottomDestinations(
    targets: PlaybackTargetState,
    upcomingCount: Int,
    onLyrics: () -> Unit,
    modifier: Modifier = Modifier,
    lyricsActive: Boolean = false,
    onDevices: () -> Unit,
    onQueue: () -> Unit,
    queueActive: Boolean = false,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Output Route Device Pill (Headphones / AirPods / Phone / Cast)
        // Not filling, so a short device name leaves the gap to SpaceBetween; a long one ellipsizes.
        OutputRoutePill(targets = targets, onClick = onDevices, modifier = Modifier.weight(1f, fill = false))
        Spacer(Modifier.width(8.dp))

        // Lyrics Action Button
        IconButton(onClick = onLyrics, modifier = Modifier.size(44.dp)) {
            Icon(
                imageVector = Icons.Rounded.Subtitles,
                contentDescription = if (lyricsActive) "Hide lyrics" else "Lyrics",
                tint = if (lyricsActive) Color.White else Color.White.copy(alpha = 0.75f),
                modifier = Modifier.size(24.dp),
            )
        }

        // Queue Action Button with upcoming badge
        IconButton(onClick = onQueue, modifier = Modifier.size(44.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.List,
                    contentDescription = if (queueActive) "Hide queue" else "Queue",
                    tint = if (queueActive) Color.White else Color.White.copy(alpha = 0.75f),
                    modifier = Modifier.size(24.dp),
                )
                if (upcomingCount > 0) {
                    // Sized to the text so "99+" keeps a pill shape instead of clipping.
                    Text(
                        text = if (upcomingCount > 99) "99+" else upcomingCount.toString(),
                        color = Color.White,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        softWrap = false,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .offset(x = 10.dp, y = (-6).dp)
                            .defaultMinSize(minWidth = 16.dp)
                            .background(Color(0xFF3A3A3C), CircleShape)
                            .padding(horizontal = 4.dp, vertical = 1.dp),
                    )
                }
            }
        }
    }
}

@Composable
internal fun OutputRoutePill(targets: PlaybackTargetState, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val isLocal = targets.selected is PlaybackTarget.LocalPhone
    val output by rememberAudioOutput()

    // Local playback follows the phone's real output route; remote playback names the Connect
    // device.
    val activeDeviceName =
        if (isLocal) {
            output.name
        } else {
            targets.devices.firstOrNull { it.isActive }?.name ?: "Connected Device"
        }
    val context = LocalContext.current
    val routeIcon = if (isLocal) output.type.icon(context) else Icons.Rounded.Cast

    // Asked for on tap rather than at launch: the user has just expressed interest in output
    // devices, so it is the one moment a Bluetooth prompt explains itself.
    val nameAccess =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            /* Denied just means the pill keeps showing the generic route label. */
        }
    val needsNameAccess = isLocal && output.type.isBluetooth && !context.canReadBluetoothNames()

    Row(
        modifier =
            modifier.clip(CircleShape)
                .clickable {
                    if (needsNameAccess) {
                        nameAccess.launch(Manifest.permission.BLUETOOTH_CONNECT)
                    }
                    onClick()
                }
                .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = routeIcon,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.85f),
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = activeDeviceName,
            style =
                MaterialTheme.typography.labelMedium.copy(
                    fontWeight = FontWeight.Medium,
                    fontSize = 13.sp,
                ),
            color = Color.White.copy(alpha = 0.85f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Maps a physical output route to the glyph that reads fastest at 16dp. */
private fun AudioOutputType.icon(context: android.content.Context): ImageVector =
    when (this) {
        AudioOutputType.PHONE_SPEAKER,
        AudioOutputType.EARPIECE ->
            if (context.isFoldableHardware() && context.isTabletForm()) {
                Icons.Rounded.DevicesFold
            } else if (context.isTabletForm()) {
                Icons.Rounded.TabletAndroid
            } else {
                Icons.Rounded.PhoneAndroid
            }
        AudioOutputType.WIRED_HEADPHONES -> Icons.Rounded.Headphones
        AudioOutputType.BLUETOOTH_HEADPHONES -> Icons.Rounded.Headphones
        AudioOutputType.BLUETOOTH_EARBUDS -> Icons.Rounded.Earbuds
        AudioOutputType.BLUETOOTH_SPEAKER -> Icons.Rounded.Speaker
        AudioOutputType.CAR -> Icons.Rounded.DirectionsCar
        AudioOutputType.USB -> Icons.Rounded.Usb
        AudioOutputType.HDMI -> Icons.Rounded.Tv
        AudioOutputType.HEARING_AID -> Icons.Rounded.Hearing
        AudioOutputType.UNKNOWN -> Icons.Rounded.Headset
    }

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
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.sfg.orchard.mobile.model.DeviceAvailability
import dev.sfg.orchard.mobile.model.PlaybackDevice
import dev.sfg.orchard.mobile.ui.motion.pressScale
import dev.sfg.orchard.mobile.ui.theme.CanopyColors
import dev.sfg.orchard.mobile.ui.theme.LocalAccent

@Composable
internal fun FrostedDeviceRow(
    device: PlaybackDevice,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onRename: (() -> Unit)? = null,
    onRemove: (() -> Unit)? = null,
) {
    val shape = RoundedCornerShape(16.dp)
    val source = remember { MutableInteractionSource() }

    Surface(
        onClick = onClick,
        shape = shape,
        color = Color.Transparent,
        interactionSource = source,
        modifier = modifier.fillMaxWidth().pressScale(source, 0.96f),
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
                    imageVector = getDeviceIcon(device),
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
                    val customSubtitle =
                        if (device.customName.isNotBlank()) "(${device.name}) • " else ""
                    Text(
                        text =
                            customSubtitle +
                                when (device.availability) {
                                    DeviceAvailability.ONLINE -> "Available"
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
                    IconButton(onClick = onRename, modifier = Modifier.size(34.dp)) {
                        Icon(
                            Icons.Rounded.Edit,
                            contentDescription = "Rename",
                            tint = CanopyColors.Muted,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }

                if (onRemove != null) {
                    IconButton(onClick = onRemove, modifier = Modifier.size(34.dp)) {
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
                    color = Color.Transparent,
                    modifier = Modifier.border(1.dp, CanopyColors.Rule, CircleShape),
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
                                MaterialTheme.typography.labelSmall.copy(
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

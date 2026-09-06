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
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sfg.orchard.mobile.qobuz.QobuzQuality
import dev.sfg.orchard.mobile.qobuz.QobuzStatus
import dev.sfg.orchard.mobile.ui.glass.glassFill
import dev.sfg.orchard.mobile.ui.glass.glassPane
import dev.sfg.orchard.mobile.ui.theme.CanopyColors
import dev.sfg.orchard.mobile.ui.theme.LocalAccent

val QobuzGold = Color(0xFFDFB15B)

@Composable
fun QobuzSettingsCard(
    status: QobuzStatus,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onQualityChange: (QobuzQuality) -> Unit,
    modifier: Modifier = Modifier,
) {
    val isConnected = status.isConnected
    val shape = RoundedCornerShape(20.dp)

    Surface(
        color = glassFill(CanopyColors.Surface),
        shape = shape,
        modifier = modifier.fillMaxWidth().glassPane(shape),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .background(QobuzGold.copy(alpha = 0.15f), CircleShape)
                        .border(1.dp, QobuzGold.copy(alpha = 0.35f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "Q",
                        color = QobuzGold,
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp,
                    )
                }

                Spacer(Modifier.width(14.dp))

                Column(Modifier.weight(1f)) {
                    Text(
                        text = "Qobuz Lossless",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = CanopyColors.Text,
                    )
                    Text(
                        text = if (isConnected) "Connected" else "Not connected",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isConnected) QobuzGold else CanopyColors.Muted,
                    )
                }

                if (isConnected) {
                    Switch(
                        checked = status.enabled,
                        onCheckedChange = onEnabledChange,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = QobuzGold,
                            checkedTrackColor = QobuzGold.copy(alpha = 0.35f),
                            uncheckedThumbColor = CanopyColors.Muted,
                            uncheckedTrackColor = CanopyColors.SurfaceHover,
                        ),
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            Text(
                text = if (isConnected) {
                    "Use your Qobuz subscription for lossless and Hi-Res streaming. Requires Audio Quality set to MAX."
                } else {
                    "Connect your Qobuz account to stream lossless CD-quality and 24-bit Hi-Res audio for matched tracks on MAX quality."
                },
                style = MaterialTheme.typography.bodySmall,
                color = CanopyColors.Muted,
                lineHeight = 18.sp,
            )

            if (status.lastError.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = status.lastError,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Spacer(Modifier.height(14.dp))

            if (!isConnected) {
                Button(
                    onClick = onConnect,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = QobuzGold,
                        contentColor = Color.Black,
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Connect Qobuz", fontWeight = FontWeight.SemiBold)
                }
            } else {
                // Quality options row
                Text(
                    text = "Streaming Quality",
                    style = MaterialTheme.typography.labelMedium,
                    color = CanopyColors.MutedStrong,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    QobuzQuality.entries.forEach { quality ->
                        val isSelected = quality == status.quality
                        val chipShape = RoundedCornerShape(10.dp)
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(chipShape)
                                .background(
                                    if (isSelected) QobuzGold.copy(alpha = 0.20f)
                                    else CanopyColors.SurfaceHover
                                )
                                .border(
                                    1.dp,
                                    if (isSelected) QobuzGold else Color.Transparent,
                                    chipShape
                                )
                                .clickable { onQualityChange(quality) }
                                .padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = quality.label,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                color = if (isSelected) QobuzGold else CanopyColors.Text,
                            )
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                OutlinedButton(
                    onClick = onDisconnect,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Text("Disconnect Qobuz")
                }
            }
        }
    }
}

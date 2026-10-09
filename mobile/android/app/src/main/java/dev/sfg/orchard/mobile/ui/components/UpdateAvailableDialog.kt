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

package dev.sfg.orchard.mobile.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import dev.sfg.orchard.mobile.ui.scroll.orchardVerticalScroll as verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.sfg.orchard.connect.BuildConfig
import dev.sfg.orchard.mobile.MobileUpdateMetadata
import dev.sfg.orchard.mobile.UpdateState
import dev.sfg.orchard.mobile.ui.theme.CanopyColors
import dev.sfg.orchard.mobile.ui.theme.LocalAccent

@Composable
internal fun UpdateAvailableDialog(state: UpdateState.Available, onInstall: (MobileUpdateMetadata) -> Unit, onDismiss: () -> Unit) {
    val metadata = state.metadata
    val context = LocalContext.current
    val currentVersion = remember(context) {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }
            .getOrNull() ?: BuildConfig.VERSION_NAME
    }
    val sections = remember(metadata.releaseNotes) { parseReleaseNoteSections(metadata.releaseNotes) }
    val totalChanges = remember(sections) { sections.sumOf { it.items.size } }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = CanopyColors.Surface,
            border = BorderStroke(1.dp, CanopyColors.RuleStrong),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 24.dp)
                .widthIn(max = 440.dp)
                .heightIn(max = 680.dp),
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
            ) {
                // Dialog Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(42.dp)
                                .background(LocalAccent.current.copy(alpha = 0.14f), RoundedCornerShape(12.dp)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Rounded.SystemUpdate,
                                contentDescription = null,
                                tint = LocalAccent.current,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(
                                "Orchard update",
                                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                                color = CanopyColors.Text,
                            )
                            Text(
                                if (metadata.publishedAt.isNotBlank()) "Published ${metadata.publishedAt}" else "Update available",
                                style = MaterialTheme.typography.bodySmall,
                                color = CanopyColors.Muted,
                            )
                        }
                    }
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            Icons.Rounded.Close,
                            contentDescription = "Close update prompt",
                            tint = CanopyColors.Muted,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                // Versions stats panel
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = CanopyColors.Canvas,
                    border = BorderStroke(1.dp, CanopyColors.Rule),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column {
                            Text(
                                "INSTALLED",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 0.8.sp,
                                ),
                                color = CanopyColors.Eyebrow,
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                "v$currentVersion",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = CanopyColors.MutedStrong,
                            )
                        }

                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowForward,
                            contentDescription = null,
                            tint = CanopyColors.Muted.copy(alpha = 0.6f),
                            modifier = Modifier.size(18.dp),
                        )

                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                "AVAILABLE",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 0.8.sp,
                                ),
                                color = LocalAccent.current,
                            )
                            Spacer(Modifier.height(2.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "v${metadata.version}",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                    color = CanopyColors.Text,
                                )
                                if (metadata.codename.isNotBlank()) {
                                    Spacer(Modifier.width(6.dp))
                                    Surface(
                                        color = LocalAccent.current.copy(alpha = 0.14f),
                                        shape = RoundedCornerShape(6.dp),
                                    ) {
                                        Text(
                                            metadata.codename,
                                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                            color = LocalAccent.current,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))

                // Release Notes Section
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 2.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Release notes",
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp,
                        ),
                        color = CanopyColors.Eyebrow,
                    )
                    if (totalChanges > 0) {
                        Surface(
                            color = CanopyColors.Canvas,
                            shape = CircleShape,
                        ) {
                            Text(
                                if (totalChanges == 1) "1 change" else "$totalChanges changes",
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                                color = CanopyColors.Muted,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            )
                        }
                    }
                }

                Spacer(Modifier.height(6.dp))

                // Scrollable Release Notes Container
                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                ) {
                    if (sections.isNotEmpty()) {
                        sections.forEachIndexed { index, section ->
                            if (index > 0) Spacer(Modifier.height(10.dp))
                            ReleaseNoteSectionCard(section)
                        }
                    } else {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = CanopyColors.Canvas.copy(alpha = 0.6f),
                            border = BorderStroke(1.dp, CanopyColors.Rule),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                "Includes general performance improvements and bug fixes.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = CanopyColors.Muted,
                                modifier = Modifier.padding(14.dp),
                            )
                        }
                    }
                }

                Spacer(Modifier.height(18.dp))

                // Actions Footer
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        onClick = onDismiss,
                        shape = CircleShape,
                        modifier = Modifier
                            .weight(1f)
                            .height(46.dp),
                    ) {
                        Text(
                            "Not now",
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                            color = CanopyColors.Muted,
                        )
                    }

                    Button(
                        onClick = { onInstall(metadata) },
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = LocalAccent.current,
                            contentColor = Color.Black,
                        ),
                        modifier = Modifier
                            .weight(1.3f)
                            .height(46.dp),
                    ) {
                        Icon(
                            Icons.Rounded.Download,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "Update now",
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                        )
                    }
                }
            }
        }
    }
}

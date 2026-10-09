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
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.sfg.orchard.connect.BuildConfig
import dev.sfg.orchard.mobile.ui.theme.CanopyColors
import dev.sfg.orchard.mobile.ui.theme.LocalAccent

/**
 * Clean card rendering an individual release note category section with header, icon, and bullet items.
 */
@Composable
internal fun ReleaseNoteSectionCard(section: ReleaseNoteSection) {
    val categoryColor = categoryColor(section.category)

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = CanopyColors.Canvas.copy(alpha = 0.65f),
        border = BorderStroke(1.dp, CanopyColors.Rule),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
        ) {
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
                            .size(24.dp)
                            .background(categoryColor.copy(alpha = 0.16f), RoundedCornerShape(7.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            categoryIcon(section.category),
                            contentDescription = null,
                            tint = categoryColor,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        section.title,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = CanopyColors.Text,
                    )
                }
                Surface(
                    color = CanopyColors.Surface,
                    shape = CircleShape,
                ) {
                    Text(
                        "${section.items.size}",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        color = CanopyColors.Muted,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                section.items.forEach { item ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Box(
                            modifier = Modifier
                                .padding(top = 7.dp, end = 8.dp)
                                .size(5.dp)
                                .background(categoryColor.copy(alpha = 0.8f), CircleShape),
                        )
                        Text(
                            text = formatMarkdownInline(item, CanopyColors.Text, LocalAccent.current),
                            style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 19.sp),
                            color = CanopyColors.MutedStrong,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Displays release notes for the current installed version or past releases.
 */
@Composable
fun ReleaseNotesDialog(
    version: String = BuildConfig.VERSION_NAME,
    codename: String = BuildConfig.CODENAME,
    releaseNotes: String = dev.sfg.orchard.mobile.MobileChangelog.CURRENT_RELEASE_NOTES,
    onDismiss: () -> Unit,
) {
    val sections = remember(releaseNotes) { parseReleaseNoteSections(releaseNotes) }
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
                // Header
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
                                Icons.Rounded.AutoAwesome,
                                contentDescription = null,
                                tint = LocalAccent.current,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(
                                "What's new",
                                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                                color = CanopyColors.Text,
                            )
                            Text(
                                if (codename.isNotBlank()) "Orchard $version \"$codename\"" else "Orchard $version",
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
                            contentDescription = "Close release notes",
                            tint = CanopyColors.Muted,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                // Current version card
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
                                "CURRENT VERSION",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 0.8.sp,
                                ),
                                color = CanopyColors.Eyebrow,
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                "v$version",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = CanopyColors.Text,
                            )
                        }

                        if (codename.isNotBlank()) {
                            Surface(
                                color = LocalAccent.current.copy(alpha = 0.14f),
                                shape = RoundedCornerShape(6.dp),
                            ) {
                                Text(
                                    codename,
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
                                    color = LocalAccent.current,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))

                // Release Notes Header
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

                // Scrollable Release Notes
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

                Button(
                    onClick = onDismiss,
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = LocalAccent.current,
                        contentColor = Color.Black,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(46.dp),
                ) {
                    Text(
                        "Done",
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                    )
                }
            }
        }
    }
}

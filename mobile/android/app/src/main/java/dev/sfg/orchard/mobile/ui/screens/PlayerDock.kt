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

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sfg.orchard.mobile.model.RepeatMode
import dev.sfg.orchard.mobile.ui.glass.GlassTone
import dev.sfg.orchard.mobile.ui.glass.glassPane

internal val DockHeight = 56.dp
private val DockInset = 4.dp
internal val DockContent = Color(0xFF0E1520)
internal val DockHighlight = Color.White.copy(alpha = 0.92f)

/**
 * Bottom dock: one capsule that switches between lyrics, the cover and the queue, plus a button
 * that opens the playback modes tray. The highlight follows the open panel.
 */
@Composable
internal fun PlayerDock(
    panel: PlayerPanel,
    source: String,
    upcomingCount: Int,
    shuffle: Boolean,
    repeatMode: RepeatMode,
    sleepTimerActive: Boolean,
    trayOpen: Boolean,
    onLyrics: () -> Unit,
    onQueue: () -> Unit,
    onToggleTray: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val selected = when (panel) {
        PlayerPanel.LYRICS -> 0
        PlayerPanel.NONE -> 1
        PlayerPanel.QUEUE -> 2
    }
    val modeActive = shuffle || repeatMode != RepeatMode.OFF || sleepTimerActive

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        DockTabs(
            selected = selected,
            source = source,
            upcomingCount = upcomingCount,
            onLyrics = { if (panel != PlayerPanel.LYRICS) onLyrics() },
            onCover = {
                when (panel) {
                    PlayerPanel.LYRICS -> onLyrics()
                    PlayerPanel.QUEUE -> onQueue()
                    PlayerPanel.NONE -> Unit
                }
            },
            onQueue = { if (panel != PlayerPanel.QUEUE) onQueue() },
            modifier = Modifier.weight(1f),
        )
        Box(
            modifier = Modifier
                .size(DockHeight)
                .then(
                    if (trayOpen) Modifier.clip(CircleShape).background(DockHighlight)
                    else Modifier.glassPane(CircleShape, GlassTone.CONTROL),
                )
                .clip(CircleShape)
                .selectable(selected = trayOpen, role = Role.Button, onClick = onToggleTray)
                .semantics { contentDescription = "Playback modes" },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.Tune,
                contentDescription = null,
                tint = if (trayOpen) DockContent else Color.White.copy(alpha = 0.78f),
                modifier = Modifier.size(24.dp),
            )
            if (modeActive && !trayOpen) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 12.dp, end = 12.dp)
                        .size(8.dp)
                        .background(Color.White, CircleShape),
                )
            }
        }
    }
}

@Composable
private fun DockTabs(
    selected: Int,
    source: String,
    upcomingCount: Int,
    onLyrics: () -> Unit,
    onCover: () -> Unit,
    onQueue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val position by animateFloatAsState(selected.toFloat(), tween(250), label = "DockHighlight")
    BoxWithConstraints(
        modifier = modifier
            .height(DockHeight)
            .glassPane(CircleShape, GlassTone.CONTROL)
            .padding(DockInset),
    ) {
        val slot = maxWidth / 3
        Box(
            Modifier
                .offset(x = slot * position)
                .size(width = slot, height = DockHeight - DockInset * 2)
                .background(DockHighlight, CircleShape),
        )
        Row(Modifier.fillMaxWidth()) {
            DockTab(selected == 0, "Lyrics", onLyrics, Modifier.weight(1f)) { tint ->
                Icon(Icons.Rounded.Subtitles, null, tint = tint, modifier = Modifier.size(24.dp))
            }
            DockTab(selected == 1, "Now playing", onCover, Modifier.weight(1f)) { tint ->
                if (playbackSourceHasMark(source)) {
                    PlaybackSourceMark(source, tint, size = 24.dp)
                } else {
                    Icon(Icons.Rounded.Album, null, tint = tint, modifier = Modifier.size(24.dp))
                }
            }
            DockTab(selected == 2, "Up next", onQueue, Modifier.weight(1f)) { tint ->
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.AutoMirrored.Rounded.List, null, tint = tint, modifier = Modifier.size(24.dp))
                    if (upcomingCount > 0) {
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
                                .offset(x = 12.dp, y = (-8).dp)
                                .defaultMinSize(minWidth = 16.dp)
                                .background(Color(0xFF3A3A3C), CircleShape)
                                .padding(horizontal = 4.dp, vertical = 1.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DockTab(
    selected: Boolean,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier,
    icon: @Composable (tint: Color) -> Unit,
) {
    Box(
        modifier = modifier
            .height(DockHeight - DockInset * 2)
            .clip(CircleShape)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        icon(if (selected) DockContent else Color.White.copy(alpha = 0.78f))
    }
}

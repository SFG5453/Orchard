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

import dev.sfg.orchard.mobile.i18n.tr

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AllInclusive
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sfg.orchard.mobile.model.PlaybackTargetState
import dev.sfg.orchard.mobile.model.RepeatMode
import dev.sfg.orchard.mobile.ui.glass.GlassTone
import dev.sfg.orchard.mobile.ui.glass.glassPane

private val TrayShape = RoundedCornerShape(28.dp)

/** Playback modes and the output route, as frosted glass over the player's backdrop. */
@Composable
internal fun PlayerModesTray(
    modifier: Modifier,
    targets: PlaybackTargetState,
    shuffle: Boolean,
    repeatMode: RepeatMode,
    autoplayEnabled: Boolean,
    sleepTimerActive: Boolean,
    canControl: Boolean,
    onShuffle: () -> Unit,
    onRepeat: () -> Unit,
    onAutoplayEnabled: ((Boolean) -> Unit)?,
    onSleepTimer: () -> Unit,
    onDevices: () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .glassPane(TrayShape, GlassTone.OVERLAY)
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            ModeTile(
                icon = Icons.Rounded.Shuffle,
                label = tr("Shuffle"),
                active = shuffle,
                enabled = canControl,
                onClick = onShuffle,
                modifier = Modifier.weight(1f),
            )
            ModeTile(
                icon = if (repeatMode == RepeatMode.ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                label = when (repeatMode) {
                    RepeatMode.OFF -> "Repeat"
                    RepeatMode.ALL -> "Repeat all"
                    RepeatMode.ONE -> "Repeat one"
                },
                active = repeatMode != RepeatMode.OFF,
                enabled = canControl,
                onClick = onRepeat,
                modifier = Modifier.weight(1f),
            )
            ModeTile(
                icon = Icons.Rounded.Bedtime,
                label = tr("Sleep"),
                active = sleepTimerActive,
                enabled = true,
                onClick = onSleepTimer,
                modifier = Modifier.weight(1f),
            )
            if (onAutoplayEnabled != null) {
                ModeTile(
                    icon = Icons.Rounded.AllInclusive,
                    label = tr("Autoplay"),
                    active = autoplayEnabled,
                    enabled = true,
                    onClick = { onAutoplayEnabled(!autoplayEnabled) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        OutputRoutePill(targets = targets, onClick = onDevices)
    }
}

@Composable
private fun ModeTile(
    icon: ImageVector,
    label: String,
    active: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tint = if (active) DockContent else Color.White
    Column(
        modifier = modifier
            .height(64.dp)
            .alpha(if (enabled) 1f else 0.4f)
            .clip(RoundedCornerShape(20.dp))
            .background(if (active) DockHighlight else Color.Transparent)
            .toggleable(value = active, enabled = enabled, role = Role.Switch, onValueChange = { onClick() })
            .padding(horizontal = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(6.dp))
        Text(
            text = tr(label),
            color = tint,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            textAlign = TextAlign.Center,
        )
    }
}

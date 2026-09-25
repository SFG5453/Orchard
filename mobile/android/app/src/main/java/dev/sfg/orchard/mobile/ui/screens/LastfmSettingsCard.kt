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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sfg.orchard.mobile.lastfm.LastfmState
import dev.sfg.orchard.mobile.ui.theme.CanopyColors

private val LastfmRed = Color(0xFFD51007)

@Composable
fun LastfmSettingsCard(
    state: LastfmState,
    onConnect: () -> Unit,
    onComplete: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val shape = RoundedCornerShape(20.dp)
    Surface(color = Color.Transparent, shape = shape, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(44.dp).background(LastfmRed.copy(alpha = 0.16f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("L", color = LastfmRed, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "Last.fm",
                        style =
                            MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = CanopyColors.Text,
                    )
                    Text(
                        lastfmSubtitle(state),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (state is LastfmState.Error) CanopyColors.Danger else CanopyColors.Muted,
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            when (state) {
                LastfmState.SignedOut,
                is LastfmState.Error -> IntegrationAction("Connect Last.fm", onClick = onConnect)
                LastfmState.Connecting -> IntegrationAction("Connecting…", onClick = {})
                is LastfmState.Pending ->
                    IntegrationAction("Finish connection", onClick = onComplete)
                is LastfmState.Connected ->
                    IntegrationAction("Sign out of Last.fm", destructive = true, onClick = onDisconnect)
            }
        }
    }
}

private fun lastfmSubtitle(state: LastfmState): String =
    when (state) {
        LastfmState.SignedOut -> "Send now-playing updates and completed listens"
        LastfmState.Connecting -> "Starting browser authorization…"
        is LastfmState.Pending -> "Approve Orchard in your browser, then finish here"
        is LastfmState.Connected -> "Connected as ${state.user}"
        is LastfmState.Error -> state.message
    }

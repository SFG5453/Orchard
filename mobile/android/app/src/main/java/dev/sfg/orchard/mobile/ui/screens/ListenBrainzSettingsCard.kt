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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sfg.orchard.mobile.listenbrainz.ListenBrainzState
import dev.sfg.orchard.mobile.ui.theme.CanopyColors
import dev.sfg.orchard.mobile.ui.theme.LocalAccent

private val ListenBrainzOrange = Color(0xFFEB743B)

@Composable
fun ListenBrainzSettingsCard(
    state: ListenBrainzState,
    onConnect: (String) -> Unit,
    onDisconnect: () -> Unit,
) {
    var showTokenDialog by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(20.dp)
    Surface(color = Color.Transparent, shape = shape, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(44.dp)
                        .background(ListenBrainzOrange.copy(alpha = 0.16f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "B",
                        color = ListenBrainzOrange,
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "ListenBrainz",
                        style =
                            MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = CanopyColors.Text,
                    )
                    Text(
                        listenBrainzSubtitle(state),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (state is ListenBrainzState.Error) CanopyColors.Danger else CanopyColors.Muted,
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            when (state) {
                ListenBrainzState.SignedOut,
                is ListenBrainzState.Error ->
                    IntegrationAction("Connect ListenBrainz", onClick = { showTokenDialog = true })
                ListenBrainzState.Validating -> IntegrationAction("Validating token…", onClick = {})
                is ListenBrainzState.Connected ->
                    IntegrationAction("Sign out of ListenBrainz", destructive = true, onClick = onDisconnect)
            }
        }
    }

    if (showTokenDialog) {
        var token by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showTokenDialog = false },
            title = { Text("ListenBrainz user token", color = CanopyColors.Text) },
            text = {
                Column {
                    Text(
                        "Paste the token from listenbrainz.org/settings. It is encrypted on this device.",
                        style = MaterialTheme.typography.bodySmall,
                        color = CanopyColors.Muted,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = token,
                        onValueChange = { token = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = token.isNotBlank(),
                    onClick = {
                        onConnect(token)
                        showTokenDialog = false
                    },
                ) {
                    Text("Connect", color = LocalAccent.current)
                }
            },
            dismissButton = {
                TextButton(onClick = { showTokenDialog = false }) {
                    Text("Cancel", color = CanopyColors.Muted)
                }
            },
            containerColor = CanopyColors.Surface,
        )
    }
}

private fun listenBrainzSubtitle(state: ListenBrainzState): String =
    when (state) {
        ListenBrainzState.SignedOut -> "Send listens directly with your encrypted user token"
        ListenBrainzState.Validating -> "Checking your token…"
        is ListenBrainzState.Connected -> "Connected as ${state.user}"
        is ListenBrainzState.Error -> state.message
    }

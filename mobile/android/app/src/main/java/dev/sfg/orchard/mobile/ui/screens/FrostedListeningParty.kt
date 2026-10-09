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

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sfg.orchard.mobile.social.PartyPeer
import dev.sfg.orchard.mobile.social.PartyRole
import dev.sfg.orchard.mobile.social.PartyState
import dev.sfg.orchard.mobile.social.PartyStatus
import dev.sfg.orchard.mobile.social.cleanRoomCode
import dev.sfg.orchard.mobile.ui.theme.CanopyColors
import dev.sfg.orchard.mobile.ui.theme.LocalAccent

@Composable
internal fun FrostedListeningPartyPanel(
    party: PartyState,
    onCreate: () -> Unit,
    onJoin: (String) -> Unit,
    onLeave: () -> Unit,
) {
    val shape = RoundedCornerShape(22.dp)
    val accent = LocalAccent.current

    Surface(shape = shape, color = Color.Transparent, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(vertical = 16.dp, horizontal = 4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Rounded.Groups,
                        contentDescription = null,
                        tint = if (party.isActive) accent else CanopyColors.Muted,
                        modifier = Modifier.size(24.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = "Listening party",
                        style =
                            MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        color = CanopyColors.Text,
                    )
                }
            }

            Spacer(Modifier.height(4.dp))

            if (party.isActive) {
                FrostedActiveParty(party = party, onLeave = onLeave)
            } else {
                FrostedInactiveParty(party = party, onCreate = onCreate, onJoin = onJoin)
            }

            if (party.error.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                FrostedMessageBanner(message = party.error, isError = true)
            }
        }
    }
}

@Composable
private fun FrostedInactiveParty(
    party: PartyState,
    onCreate: () -> Unit,
    onJoin: (String) -> Unit,
) {
    var codeInput by remember { mutableStateOf("") }
    val context = LocalContext.current
    val accent = LocalAccent.current

    Text(
        text = "Listen to the same music together.",
        style = MaterialTheme.typography.bodyMedium,
        color = CanopyColors.Muted,
    )

    Spacer(Modifier.height(14.dp))

    // 6-Character Room Code Field
    TextField(
        value = codeInput,
        onValueChange = { codeInput = cleanRoomCode(it).take(ROOM_CODE_LENGTH) },
        placeholder = { Text("Room code", color = CanopyColors.Muted) },
        singleLine = true,
        leadingIcon = {
            Icon(
                Icons.Rounded.AutoAwesome,
                contentDescription = null,
                tint = if (codeInput.isNotBlank()) accent else CanopyColors.Muted,
            )
        },
        trailingIcon = {
            if (codeInput.isNotBlank()) {
                IconButton(onClick = { codeInput = "" }) {
                    Icon(
                        Icons.Rounded.Close,
                        contentDescription = "Clear",
                        tint = CanopyColors.Muted,
                    )
                }
            } else {
                IconButton(
                    onClick = {
                        getClipboardText(context)?.takeIf(String::isNotBlank)?.let {
                            codeInput = cleanRoomCode(it).take(ROOM_CODE_LENGTH)
                        }
                    }
                ) {
                    Icon(Icons.Rounded.ContentPaste, contentDescription = "Paste", tint = accent)
                }
            }
        },
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
        modifier = Modifier.fillMaxWidth().border(1.dp, CanopyColors.Rule, CircleShape),
    )

    Spacer(Modifier.height(14.dp))

    // Action Buttons Row: Join Party & Start Party
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(
            onClick = { onJoin(codeInput) },
            enabled = codeInput.isNotBlank() && party.status != PartyStatus.CONNECTING,
            shape = CircleShape,
            colors =
                ButtonDefaults.buttonColors(containerColor = accent, contentColor = Color.Black),
            modifier = Modifier.weight(1f).height(44.dp),
        ) {
            Text("Join party", fontWeight = FontWeight.Bold, fontSize = 14.sp)
        }

        OutlinedButton(
            onClick = onCreate,
            enabled = party.status != PartyStatus.CONNECTING,
            shape = CircleShape,
            modifier =
                Modifier.weight(1f).height(44.dp).border(1.dp, CanopyColors.Rule, CircleShape),
        ) {
            Text(
                "Start a party",
                fontWeight = FontWeight.Bold,
                color = CanopyColors.Text,
                fontSize = 14.sp,
            )
        }
    }
}

@Composable
private fun FrostedActiveParty(party: PartyState, onLeave: () -> Unit) {
    val context = LocalContext.current
    val accent = LocalAccent.current

    // Session Status Label
    val statusText =
        when (party.status) {
            PartyStatus.CONNECTING -> "Connecting to room…"
            PartyStatus.OFFLINE -> "Reconnecting to listening party…"
            else ->
                if (party.isHost) "Hosting Session • All listeners follow your queue"
                else "Synced • Following host's playback"
        }

    Text(
        text = statusText,
        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
        color = if (party.status == PartyStatus.CONNECTED) accent else CanopyColors.Muted,
    )

    if (party.code.isNotBlank()) {
        Spacer(Modifier.height(14.dp))

        // Large Room Code Display Card with Copy & Share Actions
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = Color.Transparent,
            modifier =
                Modifier.fillMaxWidth().border(1.dp, CanopyColors.Rule, RoundedCornerShape(16.dp)),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "ROOM CODE",
                    style =
                        MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.2.sp,
                        ),
                    color = CanopyColors.Eyebrow,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = party.code.toCharArray().joinToString("  "),
                    style =
                        MaterialTheme.typography.displayLarge.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 32.sp,
                            letterSpacing = 4.sp,
                            fontFamily = FontFamily.Monospace,
                        ),
                    color = CanopyColors.Text,
                )

                Spacer(Modifier.height(12.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    // Copy Code Button
                    Surface(
                        onClick = {
                            copyToClipboard(context, party.code, label = "Orchard Room Code")
                            Toast.makeText(
                                    context,
                                    "Room code copied to clipboard",
                                    Toast.LENGTH_SHORT,
                                )
                                .show()
                        },
                        shape = CircleShape,
                        color = Color.Transparent,
                        modifier =
                            Modifier.weight(1f)
                                .height(38.dp)
                                .border(1.dp, CanopyColors.Rule, CircleShape),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxSize(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            Icon(
                                Icons.Rounded.ContentCopy,
                                contentDescription = null,
                                tint = accent,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "Copy Code",
                                style =
                                    MaterialTheme.typography.labelMedium.copy(
                                        fontWeight = FontWeight.Bold
                                    ),
                                color = CanopyColors.Text,
                            )
                        }
                    }

                    // Share Link Button
                    Surface(
                        onClick = {
                            val shareUrl =
                                party.room?.let { room -> room.shareUrl.ifBlank { room.joinUrl } }
                                    ?: "orchard-party://join/${party.code}"
                            val sendIntent =
                                Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(
                                        Intent.EXTRA_TEXT,
                                        "Join my Orchard listening party! Room code: ${party.code}\n$shareUrl",
                                    )
                                }
                            context.startActivity(
                                Intent.createChooser(sendIntent, "Share listening party link")
                            )
                        },
                        shape = CircleShape,
                        color = Color.Transparent,
                        modifier =
                            Modifier.weight(1f)
                                .height(38.dp)
                                .border(1.dp, CanopyColors.Rule, CircleShape),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxSize(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            Icon(
                                Icons.Rounded.Share,
                                contentDescription = null,
                                tint = accent,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "Share",
                                style =
                                    MaterialTheme.typography.labelMedium.copy(
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

    // Connected Peer Listeners Roster
    Spacer(Modifier.height(14.dp))
    Text(
        text =
            when (party.peers.size) {
                0 -> "No other listeners in the room yet"
                1 -> "1 other listener"
                else -> "${party.peers.size} other listeners"
            },
        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
        color = CanopyColors.Muted,
    )

    party.peers.forEach { peer ->
        Spacer(Modifier.height(6.dp))
        FrostedPeerRow(peer = peer)
    }

    Spacer(Modifier.height(16.dp))

    // Leave or End Party Button
    OutlinedButton(
        onClick = onLeave,
        shape = CircleShape,
        colors = ButtonDefaults.outlinedButtonColors(contentColor = CanopyColors.Danger),
        modifier =
            Modifier.fillMaxWidth()
                .height(44.dp)
                .border(1.dp, CanopyColors.Danger.copy(alpha = 0.35f), CircleShape),
    ) {
        Text(
            text = if (party.isHost) "End Party (Close Room)" else "Leave Party",
            color = CanopyColors.Danger,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun FrostedPeerRow(peer: PartyPeer) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Color.Transparent,
        modifier =
            Modifier.fillMaxWidth().border(1.dp, CanopyColors.Rule, RoundedCornerShape(12.dp)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Rounded.PhoneAndroid,
                contentDescription = null,
                tint = if (peer.open) LocalAccent.current else CanopyColors.Muted,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = peer.name.ifBlank { "Listener" },
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = CanopyColors.Text,
                modifier = Modifier.weight(1f),
            )

            // WebRTC live sync dot
            Box(
                modifier =
                    Modifier.size(8.dp)
                        .clip(CircleShape)
                        .background(if (peer.open) LocalAccent.current else CanopyColors.Muted)
            )

            if (peer.role == PartyRole.HOST) {
                Spacer(Modifier.width(8.dp))
                Surface(shape = CircleShape, color = LocalAccent.current) {
                    Text(
                        text = "HOST",
                        style =
                            MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 10.sp,
                            ),
                        color = Color.Black,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
        }
    }
}

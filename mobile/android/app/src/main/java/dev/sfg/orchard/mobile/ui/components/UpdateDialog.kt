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

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.sfg.orchard.mobile.MobileUpdateMetadata
import dev.sfg.orchard.mobile.UpdateState
import dev.sfg.orchard.mobile.ui.theme.CanopyColors
import dev.sfg.orchard.mobile.ui.theme.LocalAccent

/**
 * In-app update prompt. The system package installer is only ever launched after the user
 * has agreed here, so an update never arrives as an unexplained "install this package?".
 */
@Composable
fun UpdateDialog(
    state: UpdateState,
    onInstall: (MobileUpdateMetadata) -> Unit,
    onDismiss: () -> Unit,
) {
    when (state) {
        UpdateState.Idle, is UpdateState.ReadyToInstall -> Unit

        is UpdateState.Available -> {
            val metadata = state.metadata
            val heading = buildString {
                append("Orchard ")
                append(metadata.version)
                if (metadata.codename.isNotBlank()) append(" \"${metadata.codename}\"")
            }
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("Update available", color = CanopyColors.Text) },
                text = {
                    Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                        Text(
                            heading,
                            style = MaterialTheme.typography.titleSmall,
                            color = CanopyColors.Text,
                        )
                        if (metadata.releaseNotes.isNotBlank()) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                metadata.releaseNotes.stripMarkdown(),
                                style = MaterialTheme.typography.bodySmall,
                                color = CanopyColors.Muted,
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { onInstall(metadata) }) {
                        Text("Update", color = LocalAccent.current)
                    }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) {
                        Text("Not now", color = CanopyColors.Muted)
                    }
                },
            )
        }

        is UpdateState.Downloading -> AlertDialog(
            onDismissRequest = {},
            title = { Text("Downloading update", color = CanopyColors.Text) },
            text = {
                Column {
                    Text(
                        "Fetching Orchard ${state.version}. You can keep using the app; " +
                            "the installer opens when it finishes.",
                        style = MaterialTheme.typography.bodySmall,
                        color = CanopyColors.Muted,
                    )
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(
                        modifier = Modifier.heightIn(min = 4.dp),
                        color = LocalAccent.current,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = onDismiss) {
                    Text("Hide", color = CanopyColors.Muted)
                }
            },
        )

        is UpdateState.Failed -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Update failed", color = CanopyColors.Text) },
            text = {
                Text(
                    "${state.reason} Orchard ${state.version} was not installed.",
                    style = MaterialTheme.typography.bodySmall,
                    color = CanopyColors.Muted,
                )
            },
            confirmButton = {
                TextButton(onClick = onDismiss) {
                    Text("OK", color = LocalAccent.current)
                }
            },
        )
    }
}

/** The release notes are authored as markdown for the web changelog; flatten the syntax. */
private fun String.stripMarkdown(): String = lineSequence()
    .map { line ->
        line.trim()
            .removePrefix("###").removePrefix("##").removePrefix("#")
            .replace("**", "")
            .replace(Regex("^- "), "• ")
            .trim()
    }
    .filter { it.isNotBlank() }
    .joinToString("\n")

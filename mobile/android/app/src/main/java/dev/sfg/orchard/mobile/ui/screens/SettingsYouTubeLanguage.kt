/*
 * Copyright (C) 2026 SFG545
 *
 * This file is part of Orchard, licensed under the GNU Affero General Public
 * License, version 3 or later. See <https://www.gnu.org/licenses/>.
 */
package dev.sfg.orchard.mobile.ui.screens

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.sfg.orchard.mobile.youtube.youTubeLanguages

@Composable
internal fun YouTubeLanguageRow(language: String, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val choices = remember { listOf("system" to "System default") + youTubeLanguages() }
    val label = choices.firstOrNull { it.first == language }?.second ?: language
    ActionRow(
        title = "YouTube Music language",
        subtitle = "Language for song titles and catalog information",
        icon = Icons.Rounded.Language,
        value = label,
        onClick = { open = true },
    )
    if (open) AlertDialog(
        onDismissRequest = { open = false },
        title = { Text("YouTube Music language") },
        text = {
            LazyColumn(Modifier.heightIn(max = 400.dp)) {
                items(choices, key = { it.first }) { (value, name) ->
                    TextButton(onClick = { onSelect(value); open = false }, modifier = Modifier.fillMaxWidth()) {
                        Text(if (value == language) "✓ $name" else name)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { open = false }) { Text("Close") } },
    )
}

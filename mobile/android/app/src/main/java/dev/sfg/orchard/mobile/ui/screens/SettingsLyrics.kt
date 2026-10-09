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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.sfg.orchard.mobile.OrchardGraph
import dev.sfg.orchard.mobile.lyrics.translation.TranslationNative
import dev.sfg.orchard.mobile.model.LyricTranslationQuality
import dev.sfg.orchard.mobile.model.LyricTranslationProvider
import dev.sfg.orchard.mobile.model.OrchardSettings

/** Lyric translation source, API credentials, and the models already on this phone. */
@Composable
internal fun LyricTranslationSection(
    settings: OrchardSettings,
    onSettings: (OrchardSettings) -> Unit,
    labelIndex: Int,
) {
    val translator = OrchardGraph.from(LocalContext.current).lyricTranslation
    val installed by translator.installed.collectAsStateWithLifecycle()
    val apiKeys by translator.apiKeyState.collectAsStateWithLifecycle()
    val provider = settings.lyricTranslationProvider
    var sourceDialog by remember { mutableStateOf(false) }
    var modelDraft by remember(provider, settings.lyricTranslationModels[provider.key]) {
        mutableStateOf(settings.lyricTranslationModels[provider.key].orEmpty())
    }
    var endpointDraft by remember(settings.lyricTranslationEndpoint) { mutableStateOf(settings.lyricTranslationEndpoint) }
    var keyDraft by remember(provider) { mutableStateOf("") }

    SectionLabel("Lyrics", labelIndex)
    SettingsPanel(index = labelIndex + 1) {
        ToggleRow(
            title = "Translate lyrics to English",
            subtitle = if (provider == LyricTranslationProvider.LOCAL)
                "Runs on this phone. Each language downloads its model the first time you need it."
            else "Sends lyric lines to ${provider.label}; your provider may charge for requests.",
            icon = Icons.Rounded.Translate,
            checked = settings.translateLyrics,
            onChecked = { onSettings(settings.copy(translateLyrics = it)) },
        )
        PanelDivider()
        SettingsRow(
            title = "Translation source",
            subtitle = "Local models are private. API translation also works with other languages.",
        ) {
            SettingsPill(provider.label, onClick = { sourceDialog = true })
        }
        if (provider != LyricTranslationProvider.LOCAL) {
            PanelDivider()
            Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
                RowTitle("Model ID")
                RowSubtitle("Enter a model supported by ${provider.label}.")
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = modelDraft, onValueChange = { modelDraft = it }, modifier = Modifier.fillMaxWidth(),
                    label = { Text("Model ID") }, singleLine = true,
                )
                Spacer(Modifier.height(10.dp))
                SettingsPill("Save model", onClick = {
                    onSettings(settings.copy(lyricTranslationModels = settings.lyricTranslationModels +
                        (provider.key to modelDraft.trim())))
                })
            }
            if (provider == LyricTranslationProvider.CUSTOM) {
                PanelDivider()
                Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
                    RowTitle("API base URL")
                    RowSubtitle("OpenAI compatible; keyless HTTP APIs and localhost are allowed.")
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = endpointDraft, onValueChange = { endpointDraft = it },
                        modifier = Modifier.fillMaxWidth(), label = { Text("https://example.com/v1") },
                        singleLine = true,
                    )
                    Spacer(Modifier.height(10.dp))
                    SettingsPill("Save URL", onClick = {
                        onSettings(settings.copy(lyricTranslationEndpoint = endpointDraft.trim()))
                    })
                }
            }
            PanelDivider()
            Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
                RowTitle("API key")
                RowSubtitle(if (apiKeys.has(provider)) "Saved with Android Keystore encryption."
                    else if (provider == LyricTranslationProvider.CUSTOM) "Optional for a keyless API."
                    else "Required for ${provider.label}; saved with Android Keystore encryption.")
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = keyDraft, onValueChange = { keyDraft = it }, modifier = Modifier.fillMaxWidth(),
                    label = { Text(if (apiKeys.has(provider)) "Replace saved key" else "Paste API key") },
                    singleLine = true, visualTransformation = PasswordVisualTransformation(),
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (keyDraft.isNotBlank()) SettingsPill("Save key", onClick = {
                        translator.saveApiKey(provider, keyDraft)
                        keyDraft = ""
                    })
                    if (apiKeys.has(provider)) SettingsPill("Remove key", destructive = true,
                        onClick = { translator.removeApiKey(provider) })
                }
                if (apiKeys.message.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    RowSubtitle(apiKeys.message)
                }
            }
        }
        if (provider == LyricTranslationProvider.LOCAL && TranslationNative.available) {
            PanelDivider()
            Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    RowIcon(Icons.Rounded.Tune)
                    Column {
                        RowTitle("Translation quality")
                        RowSubtitle(
                            when (settings.lyricTranslationQuality) {
                                LyricTranslationQuality.STANDARD -> "About 20 MB per language and quickest"
                                LyricTranslationQuality.HIGH -> "About 80 MB per language, reads more naturally"
                            },
                        )
                    }
                }
                Spacer(Modifier.height(14.dp))
                SettingsSegmented(
                    options = listOf(LyricTranslationQuality.STANDARD to "Standard", LyricTranslationQuality.HIGH to "High"),
                    selected = settings.lyricTranslationQuality,
                    onSelect = { onSettings(settings.copy(lyricTranslationQuality = it)) },
                )
            }
            installed.forEach { pack ->
                PanelDivider()
                SettingsRow(
                    title = pack.name + if (pack.quality == "high") " (High)" else "",
                    subtitle = "%.0f MB · %s".format(pack.bytes / 1e6, pack.credit),
                ) {
                    SettingsPill(label = "Remove", destructive = true, onClick = { translator.remove(pack) })
                }
            }
        }
    }
    if (sourceDialog) AlertDialog(
        onDismissRequest = { sourceDialog = false },
        title = { Text("Translation source") },
        text = {
            Column {
                LyricTranslationProvider.entries.forEach { choice ->
                    TextButton(onClick = {
                        onSettings(settings.copy(lyricTranslationProvider = choice))
                        sourceDialog = false
                    }, modifier = Modifier.fillMaxWidth()) { Text(choice.label) }
                }
            }
        },
        confirmButton = { TextButton(onClick = { sourceDialog = false }) { Text("Close") } },
    )
}

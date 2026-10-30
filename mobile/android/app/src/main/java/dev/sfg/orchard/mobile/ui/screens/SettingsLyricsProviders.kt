/*
 * Copyright (C) 2026 SFG545
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.sfg.orchard.mobile.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.sfg.orchard.mobile.model.OrchardSettings
import dev.sfg.orchard.mobile.model.normalizeLyricsProviders
import dev.sfg.orchard.mobile.model.visibleLyricsProviders

@Composable
internal fun LyricsProvidersSection(
    settings: OrchardSettings,
    onSettings: (OrchardSettings) -> Unit,
    labelIndex: Int,
) {
    val saved = normalizeLyricsProviders(settings.lyricsProviders)
    val appleConnected = appleMusicConnectionStatus().connected
    val providers = visibleLyricsProviders(saved, appleConnected)
    fun move(index: Int, destination: Int) {
        if (destination !in providers.indices) return
        val from = saved.indexOf(providers[index])
        val to = saved.indexOf(providers[destination])
        val reordered = saved.toMutableList().apply { add(to, removeAt(from)) }
        onSettings(settings.copy(lyricsProviders = reordered))
    }

    SectionLabel("Lyrics providers", labelIndex)
    SettingsPanel(index = labelIndex + 1) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 12.dp)) {
            RowSubtitle("Try enabled providers from top to bottom. The first lyrics found are used. You can also choose a source for one song in the lyrics player.")
        }
        providers.forEachIndexed { index, config ->
            PanelDivider()
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    RowTitle(config.provider.label)
                    RowSubtitle(if (config.enabled) "Priority ${index + 1}" else "Disabled")
                }
                IconButton(onClick = { move(index, index - 1) }, enabled = index > 0) {
                    Icon(Icons.Rounded.ArrowUpward, "Move ${config.provider.label} up", tint = SettingsStyle.Description)
                }
                IconButton(onClick = { move(index, index + 1) }, enabled = index < providers.lastIndex) {
                    Icon(Icons.Rounded.ArrowDownward, "Move ${config.provider.label} down", tint = SettingsStyle.Description)
                }
                SettingsSwitch(
                    checked = config.enabled,
                    onCheckedChange = { enabled ->
                        onSettings(settings.copy(lyricsProviders = saved.toMutableList().apply {
                            set(saved.indexOf(config), config.copy(enabled = enabled))
                        }))
                    },
                    modifier = Modifier.semantics { contentDescription = "Enable ${config.provider.label}" },
                )
            }
        }
    }
}

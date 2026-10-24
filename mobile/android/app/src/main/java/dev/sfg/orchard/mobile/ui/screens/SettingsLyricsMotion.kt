/*
 * Copyright (C) 2026 SFG545
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.sfg.orchard.mobile.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BlurOn
import androidx.compose.material.icons.rounded.Contrast
import androidx.compose.material.icons.rounded.SwapVert
import dev.sfg.orchard.mobile.model.OrchardSettings

/** Appearance toggles for how the lines around the current lyric look and move. */
@Composable
internal fun LyricsMotionSection(
    settings: OrchardSettings,
    onSettings: (OrchardSettings) -> Unit,
    labelIndex: Int,
) {
    SectionLabel("Lyrics motion", labelIndex)
    SettingsPanel(index = labelIndex + 1) {
        ToggleRow(
            title = "Blur distant lines",
            subtitle = "Blur lines further from the current one. Needs Android 12 or newer",
            icon = Icons.Rounded.BlurOn,
            checked = settings.lyricsFocusBlur,
            onChecked = { onSettings(settings.copy(lyricsFocusBlur = it)) },
        )
        PanelDivider()
        ToggleRow(
            title = "Fade distant lines",
            subtitle = "Dim lines further from the current one",
            icon = Icons.Rounded.Contrast,
            checked = settings.lyricsDistanceFade,
            onChecked = { onSettings(settings.copy(lyricsDistanceFade = it)) },
        )
        PanelDivider()
        ToggleRow(
            title = "Staggered scrolling",
            subtitle = "Lines glide into place one after another",
            icon = Icons.Rounded.SwapVert,
            checked = settings.lyricsStaggeredScroll,
            onChecked = { onSettings(settings.copy(lyricsStaggeredScroll = it)) },
        )
    }
}

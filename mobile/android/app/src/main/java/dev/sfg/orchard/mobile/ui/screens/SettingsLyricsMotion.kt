/*
 * Copyright (C) 2026 SFG545
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.sfg.orchard.mobile.ui.screens

import dev.sfg.orchard.mobile.i18n.tr

import androidx.compose.runtime.Composable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BlurOn
import androidx.compose.material.icons.rounded.Contrast
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.SwapVert
import dev.sfg.orchard.mobile.model.OrchardSettings

/** Appearance toggles for how the lines around the current lyric look and move. */
@Composable
internal fun LyricsMotionSection(
    settings: OrchardSettings,
    onSettings: (OrchardSettings) -> Unit,
    labelIndex: Int,
) {
    SectionLabel(tr("Lyrics motion"), labelIndex)
    SettingsPanel(index = labelIndex + 1) {
        ToggleRow(
            title = tr("Lyrics under the title"),
            subtitle = tr("Show the current line below the song title in the player"),
            icon = Icons.Rounded.MusicNote,
            checked = settings.lyricsUnderTitle,
            onChecked = { onSettings(settings.copy(lyricsUnderTitle = it)) },
        )
        PanelDivider()
        ToggleRow(
            title = tr("Blur distant lines"),
            subtitle = tr("Blur lines further from the current one. Needs Android 12 or newer"),
            icon = Icons.Rounded.BlurOn,
            checked = settings.lyricsFocusBlur,
            onChecked = { onSettings(settings.copy(lyricsFocusBlur = it)) },
        )
        PanelDivider()
        ToggleRow(
            title = tr("Fade distant lines"),
            subtitle = tr("Dim lines further from the current one"),
            icon = Icons.Rounded.Contrast,
            checked = settings.lyricsDistanceFade,
            onChecked = { onSettings(settings.copy(lyricsDistanceFade = it)) },
        )
        PanelDivider()
        ToggleRow(
            title = tr("Staggered scrolling"),
            subtitle = tr("Lines glide into place one after another"),
            icon = Icons.Rounded.SwapVert,
            checked = settings.lyricsStaggeredScroll,
            onChecked = { onSettings(settings.copy(lyricsStaggeredScroll = it)) },
        )
    }
}

/*
 * Copyright (C) 2026 SFG545
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.sfg.orchard.mobile.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.sfg.orchard.mobile.OrchardGraph
import dev.sfg.orchard.mobile.model.LoadState
import dev.sfg.orchard.mobile.model.LyricLine
import dev.sfg.orchard.mobile.model.Track
import dev.sfg.orchard.mobile.model.visibleLyricsProviders

@Composable
internal fun PlayerLyrics(
    track: Track,
    lyrics: LoadState<List<LyricLine>>,
    playing: Boolean,
    onSeek: (Long) -> Unit,
    accent: Color,
    contentPadding: PaddingValues = PaddingValues(horizontal = 24.dp, vertical = 24.dp),
) {
    Column(Modifier.fillMaxSize()) {
        if (!track.isLocal) LyricsSourcePicker(track.id, accent)
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            when (lyrics) {
                is LoadState.Content -> LyricLines(
                    lines = lyrics.value, songId = track.id, playing = playing,
                    onSeek = onSeek, accent = accent, contentPadding = contentPadding,
                )
                LoadState.Loading -> LyricsNotice("Finding lyrics…", isLoading = true)
                is LoadState.Empty -> LyricsNotice(lyrics.message, icon = Icons.Rounded.MusicNote)
                is LoadState.Error -> LyricsNotice(lyrics.message, icon = Icons.Rounded.Info)
                LoadState.Idle -> LyricsNotice("Start a song to see its lyrics.", icon = Icons.Rounded.MusicNote)
            }
        }
    }
}

@Composable
private fun LyricsSourcePicker(trackId: String, accent: Color) {
    val graph = OrchardGraph.from(LocalContext.current)
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    val overrides by graph.lyrics.providerOverrides.collectAsStateWithLifecycle()
    val sources by graph.lyrics.sources.collectAsStateWithLifecycle()
    val appleConnected = appleMusicConnectionStatus().connected
    val enabled = visibleLyricsProviders(settings.lyricsProviders, appleConnected).filter { it.enabled }.map { it.provider }
    val selected = overrides[trackId]?.takeIf { it in enabled }
    val automatic = sources[trackId]?.let { "Automatic · ${it.label}" } ?: "Automatic"
    var expanded by remember(trackId) { mutableStateOf(false) }

    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        TextButton(onClick = { expanded = true }) {
            Text("Lyrics source: ${selected?.label ?: automatic}", color = accent)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("Automatic (saved provider order)") },
                onClick = { graph.lyrics.selectProvider(trackId, null); expanded = false },
                trailingIcon = { if (selected == null) Icon(Icons.Rounded.Check, "Selected") },
            )
            enabled.forEach { provider ->
                DropdownMenuItem(
                    text = { Text("${provider.label} for this song") },
                    onClick = { graph.lyrics.selectProvider(trackId, provider); expanded = false },
                    trailingIcon = { if (selected == provider) Icon(Icons.Rounded.Check, "Selected") },
                )
            }
            if (enabled.isEmpty()) DropdownMenuItem(
                text = { Text("No providers enabled") }, onClick = {}, enabled = false,
            )
        }
    }
}

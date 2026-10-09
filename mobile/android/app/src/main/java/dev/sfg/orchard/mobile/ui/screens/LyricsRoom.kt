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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import dev.sfg.orchard.mobile.ui.components.KawarpArtworkBackdrop

/**
 * Backdrop for phone lyrics, after desktop's fullscreen player: the warped cover keeps the song's
 * colour on screen, and ink falls in from the top and bottom so the header and controls stay legible.
 */
@Composable
internal fun LyricsRoom(artworkUrl: String, isPlaying: Boolean, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().background(DeepInk)) {
        // Half resolution: the warp is all soft gradients, so the full-size pass buys nothing visible.
        KawarpArtworkBackdrop(
            artworkUrl = artworkUrl,
            isPlaying = isPlaying,
            modifier = Modifier.fillMaxSize(),
            renderScale = 0.5f,
        )
        Box(Modifier.fillMaxSize().background(Ink.copy(alpha = 0.28f)))
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to Ink.copy(alpha = 0.35f),
                    0.3f to Color.Transparent,
                    0.75f to Color.Transparent,
                    1f to Ink.copy(alpha = 0.6f),
                ),
            ),
        )
    }
}

// Desktop's inkColor, and the darker base it shows before the first warp frame lands.
private val Ink = Color(0xFF0B0D0A)
private val DeepInk = Color(0xFF080908)

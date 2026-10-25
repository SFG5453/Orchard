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

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.sfg.orchard.connect.R
import dev.sfg.orchard.connect.BuildConfig
import dev.sfg.orchard.mobile.model.APPLE_MUSIC_SOURCE
import dev.sfg.orchard.mobile.model.YOUTUBE_SOURCE

private val MarkSize = 16.dp

/** Whether [PlaybackSourceMark] draws anything for [source] in this build. */
internal fun playbackSourceHasMark(source: String): Boolean = when (source.lowercase()) {
    YOUTUBE_SOURCE -> true
    APPLE_MUSIC_SOURCE -> BuildConfig.APPLE_MUSIC_LOGO
    else -> false
}

/** One-color provider icon for the playing track, drawn in [tint]. Draws nothing for local files and providers without a mark. */
@Composable
internal fun PlaybackSourceMark(source: String, tint: Color, modifier: Modifier = Modifier, size: Dp = MarkSize) {
    when (source.lowercase()) {
        YOUTUBE_SOURCE -> Image(
            painter = painterResource(R.drawable.ic_source_youtube_music_mark),
            contentDescription = "Playing from YouTube Music",
            modifier = modifier.size(size),
            colorFilter = ColorFilter.tint(tint),
        )
        // The logo only ships in builds that bundle the local Apple Music artwork.
        APPLE_MUSIC_SOURCE -> if (BuildConfig.APPLE_MUSIC_LOGO) {
            Box(modifier.size(size).semantics { contentDescription = "Playing from Apple Music" }) {
                AppleMusicMark(Modifier.size(size), tint)
            }
        }
    }
}

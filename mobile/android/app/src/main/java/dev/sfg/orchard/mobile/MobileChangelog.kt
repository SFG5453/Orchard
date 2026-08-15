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

package dev.sfg.orchard.mobile

import dev.sfg.orchard.connect.BuildConfig

/**
 * Bundled changelog and current release notes for Orchard Mobile.
 */
object MobileChangelog {
    const val CURRENT_VERSION = BuildConfig.VERSION_NAME
    const val CURRENT_CODENAME = BuildConfig.CODENAME

    val CURRENT_RELEASE_NOTES = """
        ### Features
        - **Home-Screen Widgets**: Add a responsive Now Playing controller or a four-track “Jump back in” widget. Both use album-derived colors and keep working when the app is closed.
        - **Sleep Timer**: Pause playback after 15, 30, 45, 60, or 90 minutes, or at the end of the current track.
        - **Analysis-Aware Bass Handoffs**: Smart Crossfade now uses measured low-frequency changes to place the bass swap on a better beat for each pair of songs.

        ### Fixes
        - **More Reliable Batch Downloads**: Downloads now avoid duplicate queue entries, retry transient failures, use the stream's expected user agent, and reject incomplete ranged responses.
    """.trimIndent()
}

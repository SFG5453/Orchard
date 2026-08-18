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
        ### Added
        - **Audio Equalizer**: A persisted 10-band equalizer with presets, per-band gain, preamp, and bass boost is now available in Settings and applied directly to playback.
        - **Player Artwork Gestures**: Horizontal swipes skip to the previous or next track, and a double-tap likes the current track. Gestures can be disabled in Settings.
        - **Home Screen Layout**: Online and offline Home sections can now be reordered and shown or hidden independently from the new Home Screen Layout settings page.
    """.trimIndent()
}

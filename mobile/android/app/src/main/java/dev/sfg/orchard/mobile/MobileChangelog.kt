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

/** Bundled changelog and current release notes for Orchard Mobile. */
object MobileChangelog {
    const val CURRENT_VERSION = BuildConfig.VERSION_NAME
    const val CURRENT_CODENAME = BuildConfig.CODENAME

    val CURRENT_RELEASE_NOTES =
        """
        ### Added
        - **Connect Reverse Playback**: Paired devices can now send playback commands back to one another with negotiated protocol capabilities.
        - **Collection Search**: Added collection search across mobile library and detail surfaces.
        - **Album Best Mix**: Best Mix is now available from album views while preserving native gapless album playback when requested.

        ### Changed
        - **Media Controls**: External accessory, smartwatch, and notification media controls now prioritize skip actions and respond more quickly.
        - **Playback Persistence**: Playback and widget persistence work moves off the main thread, with redundant layout broadcasts avoided.
        - **Mini Player**: A vertical swipe now dismisses the mini player and clears the playback queue.

        ### Fixed
        - **Connect Compatibility**: Playback state no longer silently renegotiates or downgrades an established Connect session.

        ### Maintenance
        - Added Rust and `cargo-ndk` setup to the Android canary and release build workflows.
        """
            .trimIndent()
}

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
        - **Best Mix**: Added mobile Best Mix with offline audio analysis, cloud synchronization, and transition-aware queue sorting.
        - **Queue Controls**: Added Best Mix, Autoplay, and Sleep Timer controls to the redesigned queue header.
        - **Catalog Browsing**: Expanded search and added multi-section catalog browse pages.
        - **Adaptive Navigation**: The frosted bottom navigation bar now samples colors from the current album artwork.

        ### Changed
        - Mobile now executes the same exact staged transition choreography as desktop.
        - Home prioritizes saved playlists and albums while catalog songs and related shelves play directly.
        - Audio decoding now resamples during decode and reuses vocal-model inference buffers for better performance.

        ### Fixed
        - Improved transition timing, beat-grid agreement, cue boundaries, vocal-collision handling, and audible track-tail protection.
        - Fixed explicit-safe audio version resolution and clipped animated artwork to its container.
        - Removed the duplicate cloud audio-analysis setting.

        ### Maintenance
        - Updated cloud analysis to schema version 12 and bumped `@xmldom/xmldom` to 0.9.12.
        """
            .trimIndent()
}

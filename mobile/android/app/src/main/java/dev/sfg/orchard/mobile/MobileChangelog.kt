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
        - **Square Now Playing Artwork**: Non-animated artwork now appears as a centered square card with rounded corners, a soft shadow, and smooth track transitions.
        - **Shared Rust Audio Analysis**: Mobile audio analysis now runs through the shared Rust/Earmark analyzer while keeping the trained beat-model input contract intact.
        - **Quality-Aware Public Playback**: Public YouTube streams now use NewPipe across all quality tiers while preserving Innertube fallbacks for private and account-only tracks.

        ### Changed
        - **Best Mix Preparation**: Download validation, progress reporting, and local analysis now agree on which tracks have usable files before sorting.

        ### Fixed
        - **Playlist Picker**: Long playlist lists can now be scrolled inside the add-to-playlist sheet.
        - **Stale Downloads**: Missing or empty files no longer remain marked as completed and are automatically eligible for re-download.
        - **Best Mix Resilience**: A decoder or native-analysis failure no longer prevents a collection from playing; Orchard falls back to the original order with a warning.

        ### Maintenance
        - Replaced the retired mobile C++ analysis JNI bridge with the shared Rust/Earmark library and refreshed mobile documentation and screenshots.
        """
            .trimIndent()
}

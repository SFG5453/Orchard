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
        - **Frosted Glass UI**: Redesigned modern frosted glass theme with immersive background blurs, animated artwork backdrops, enhanced player chrome, and updated screens across Home, Devices, and Settings. Design enhancements contributed by Julian-FF2000.
        - **Beta Channel**: Opt in to receive beta builds directly from GitHub releases with prerelease semver update checks and release notes.

        ### Changed
        - Unified upper and bass frequency bands into a single constant-power crossfade curve in the DJ mixing engine.

        ### Maintenance
        - Updated ONNX Runtime to 1.29.0 and markdown library to 0.7.9.
        """
            .trimIndent()
}

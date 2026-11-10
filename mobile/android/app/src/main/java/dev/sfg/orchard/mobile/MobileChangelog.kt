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
        ### Fixes

        - **Saved albums and playlists stay saved**: Saving an album or playlist while signed in adds it to your YouTube Music or Apple Music account library, so the next library refresh keeps it. Album and playlist pages show whether the collection is already saved to your account, and a failed save or removal is undone with a warning. Saves made without signing in stay on the phone.
        - **Apple Music sign-in button**: Connecting Apple Music shows a sign-in button instead of opening Apple's authorization window immediately. A saved Apple session still connects without a prompt.
        """
            .trimIndent()
}

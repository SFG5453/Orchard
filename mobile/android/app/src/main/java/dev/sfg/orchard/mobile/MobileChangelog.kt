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
        ### Fixed
        - **Playback Against Rationed Guest Clients**: Orchard now mints a WebPO proof of origin in a WebView and declares it in the web-family player request, ordering attested clients first; a refused proof is invalidated and its client blacklisted so retries rotate families. Playback and downloads also send explicit bounded ranges, instead of relying on unbounded requests that were answered at a trickle and cut short.
        - **Uploads**: Tracks the catalog flags as privately owned uploads skip the guest client chain and resolve through the signed-in web player, so they report "sign in" rather than "Video unavailable" (basically, they play).
    """.trimIndent()
}

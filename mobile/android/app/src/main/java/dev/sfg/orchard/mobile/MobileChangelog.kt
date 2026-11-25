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

const val DISCORD_INVITE_URL = "https://discord.com/invite/bBSrkYu4yV"

/** Announcement shown above a release's notes. */
data class ReleaseCallout(val title: String, val text: String, val action: String, val url: String)

/** Bundled changelog and current release notes for Orchard Mobile. */
object MobileChangelog {
    const val CURRENT_VERSION = BuildConfig.VERSION_NAME
    const val CURRENT_CODENAME = BuildConfig.CODENAME

    val CURRENT_CALLOUT: ReleaseCallout? = ReleaseCallout(
        title = "Orchard has a Discord",
        text = "Follow Orchard 2.1 and Mobile v2 for Android and iOS, with a new base and Adaptive Mix v2, before release.",
        action = "Join Discord",
        url = DISCORD_INVITE_URL,
    )

    val CURRENT_RELEASE_NOTES =
        """
        ### Community

        - **Orchard has a Discord**: Follow Orchard 2.1 and Mobile v2 for Android and iOS, with a new base and Adaptive Mix v2, before release. Join at https://discord.com/invite/bBSrkYu4yV or from Settings, About.
        - **What's new after updates**: Release notes open once after an update.

        ### Library

        - **Local albums**: Local music groups into albums from file tags. Songs without an album artist group by folder, so compilations and CD subfolders stay whole.

        ### Improved

        - **YouTube playback**: A new native extractor reads the YouTube player faster with less memory.

        ### Fixes

        - **Playback recovery**: A song whose cached audio cannot be read is fetched again, and playback controls reconnect after the playback session ends.
        - **Fewer duplicates**: Mixes and autoplay no longer queue both the album cut and its music video.
        """
            .trimIndent()
}

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
        ### New & improved
        - **Faster, Resumable Downloads**: Orchard can download more tracks at once, resume interrupted transfers, and use bounded parallel ranges for Max-quality audio when the source supports them.
        - **Release Notes and Update Controls**: A redesigned update experience shows structured release notes, exposes the installed version, and adds manual update checks and install actions to Settings.

        ### Fixed
        - **More Reliable Playback and Downloads**: Orchard now rotates through coordinated YouTube client profiles and carries each profile's required request identity into media fetches when a stream is rejected.
        - **Safer Download Recovery**: Downloads validate byte ranges and content lengths, preserve compatible partial files after interruptions, and fall back safely when a server rejects parallel transfers.
        - **Screen Timeout with Animated Artwork**: Animated artwork no longer keeps the display awake when the normal Android screen timeout should turn it off.
    """.trimIndent()
}

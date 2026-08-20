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
        - **Listening Parties**: Join and host real-time listening parties directly from your Android device. Includes room creation, join codes, participant roster in Devices, WebRTC/WebSocket sync with automatic drift correction, and remote playback control.

        ### Changed
        - Updated OkHttp to 5.5.0 and Added WebRTC SDK to 144.7559.12 with release ProGuard rules for WebSocket and JNI reflection.
        """
            .trimIndent()
}

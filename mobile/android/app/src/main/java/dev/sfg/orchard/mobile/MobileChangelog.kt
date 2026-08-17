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
        - **Uploaded Library Playback**: Uploaded and private library tracks now skip guest clients that cannot access them and reach signed-in playback with the account's visitor and channel delegation identity. The same path now retries only when refreshing the stream can help.
        - **Progressive Collection Loading**: Collections show their first page immediately and fill in as more pages arrive. Endless mixes use a bounded paging budget, and opening another collection cancels the previous load.
        - **Private Search History**: Internal song lookups used to resolve playback are now anonymous, so they no longer add searches to the listener's YouTube history.
    """.trimIndent()
}

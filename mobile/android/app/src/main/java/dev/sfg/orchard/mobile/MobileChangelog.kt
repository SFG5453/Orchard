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
        - **Qobuz Lossless Playback**: Connect a Qobuz account from Settings to match catalog recordings and stream CD-quality lossless or 24-bit Hi-Res audio with selectable quality and player quality badges.
        - **Qobuz Account Controls**: Enable or disable Qobuz matching, disconnect the account, and choose the preferred streaming tier without changing Orchard's catalog or fallback behavior.

        ### Changed
        - **Quality-Aware Playback**: At MAX quality, matched recordings can resolve through Qobuz while uploads, unmatched recordings, and authenticated YouTube tracks keep their existing playback paths.

        ### Maintenance
        - **Regression Coverage**: Added Qobuz matching, authentication, CMAF streaming, and segment-cache tests.
        - **Beat Model Benchmarking**: Extended Android instrumentation to benchmark the FP32 Beat This model through the mobile WebGPU provider while retaining the existing mobile model path.
        """
            .trimIndent()
}

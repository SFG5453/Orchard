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
        - **Desktop Transition Parity**: Mobile now runs the shared desktop transition planner with the same cue selection, tempo ratios, choreography, and fallback policy, backed by generated parity fixtures.
        - **Cache Controls**: Added cache size reporting and a confirmation flow to clear temporary artwork, audio, network, and stream caches without touching downloads or library data.
        - **Artist Actions**: Added artist credits in playback and follow/unfollow controls from artist and track surfaces.

        ### Changed
        - **Listening Experience**: Refined Home, Library, detail, queue, now-playing, lyrics, device, integration, and settings screens with consistent responsive surfaces and bundled Inter typography.
        - **Beat Analysis**: Shipped the official `final0` Beat This dynamic INT8 model with fixed 1500-frame windows, CPU execution, versioned extraction, and bounded audio work.
        - **Crossfade Engine**: Shared transition planning now preserves selected cues, tempo ratios, choreography, and fallback behavior while rendered playback keeps its full-song source clock.

        ### Fixed
        - **Rendered Crossfades**: Stabilized transition handoffs, preserved source position and duration, and prevented competing analysis and render jobs from disturbing playback timing.

        ### Maintenance
        - **Android Runtime**: Updated to stock ONNX Runtime Android 1.29.0, kept production APKs ARM64-only, and removed the experimental QNN/HTP packaging path after benchmark validation.
        - **Testing & Tooling**: Added transition parity, timeline, work-limiter, cache, and UI tests plus Beat This quantization and accuracy benchmarks.
        """
            .trimIndent()
}

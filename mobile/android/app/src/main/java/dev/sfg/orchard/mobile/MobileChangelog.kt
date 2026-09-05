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
        - **Offline Collection Fidelity**: Saved playlists and Liked Songs now reconstruct offline views from their actual local membership, preserving playlist order and intentional duplicate rows while showing only completed downloads.
        - **Best Mix Preparation**: Best Mix can download missing tracks for local analysis, preserve autoplay additions while sorting, and keep the live queue usable when analysis completes.
        - **Editable Playlists**: Authenticated editable playlists now support pull-to-refresh, server-backed reordering, and Move Up/Move Down actions.
        - **Autoplay Deduplication**: Autoplay removes duplicate generated recordings without removing intentional duplicates from user-authored queues.

        ### Changed
        - **Library Synchronization**: Liked Music uses YouTube's like/removelike actions with optimistic local state and rollback on failure; saved collection metadata no longer discards cached track membership.
        - **Synchronized Lyrics**: Frame-clock playback interpolation, corrected word parsing, seek-aware timing, and softer word highlighting align mobile lyrics with the desktop presentation.
        - **Smart Crossfade**: Mobile transitions now follow the shared Rust/desktop tempo contract, with the outgoing deck stretched onto the incoming grid and consistent native render timing.

        ### Fixed
        - **Offline Playlist Isolation**: Unknown or incomplete collections no longer fall back to every downloaded track.
        - **Queue Races**: Autoplay refills and Best Mix results no longer overwrite or reinsert tracks that changed while work was in flight.
        - **Tempo Alignment**: Non-identical BPM transitions now use the correct tempo ratios for rendered and live fallback playback.

        ### Maintenance
        - **Android Dependencies**: Updated Coil to 3.6.2, WebRTC to 150.7871.01, and Markdown to 0.7.12.
        - **Regression Coverage**: Added tests for offline membership, autoplay deduplication, playlist actions and reordering, lyrics parsing, and the Smart Crossfade render contract.
        """
            .trimIndent()
}

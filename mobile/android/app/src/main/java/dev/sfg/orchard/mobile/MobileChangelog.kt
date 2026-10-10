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
        - **Local Library**: Play songs and folders from the phone without an account, build local playlists with automatic or custom covers, edit playlist order, and attach .lrc, .srt, or .txt lyrics.
        - **Orchard Connect v2**: Shared C++ core for pairing, remote control, and listening parties across desktop and Android, opened from a Now Playing popup, with desktop-rendered mixes for a phone target.
        - **Music Videos**: New video player with Orchard controls, a Song/Video switch, video search, a quality picker, landscape fullscreen with bar trimming and pinch-to-fill, and SponsorBlock skipping.
        - **AI Song Detection**: Detect fully generated music in the playing song and the next three, with options to mark, skip, or remove flagged songs.
        - **Lyric Translation**: Translate lyrics on device with downloadable packs or through an optional API, with a lyrics chip and Appearance settings.
        - **Qobuz MAX**: Stream Qobuz at MAX quality with Hi-Res and Lossless labels, album quality on detail pages, and format and codec in the Now Playing badge. MAX requires a linked account.
        - **Motion Artwork**: Spotify Canvas loops after a Spotify login, and offline downloads save motion covers for playback without a connection.
        - **Integrations**: Added Chromecast, Last.fm and ListenBrainz scrobbling, and Discord presence through the Social SDK with artist avatars.
        - **Accounts**: Switch between linked accounts without signing out, with Orchard account sign-in through an app callback.
        - **Bug Reports**: Report sheet with a draggable screenshot bubble, photo picker attachments, and an update banner for new issue activity.
        - **Playback Options**: Exponential volume, YouTube history reporting, and artwork source ordering.
        - **Storage**: A "Delete all downloads" action in Storage & updates that also clears orphaned motion covers.
        - **Layouts**: Foldable and wide-screen layouts with a navigation rail, and iOS-style scroll physics.

        ### Changed
        - **Interface**: Rebuilt Home, Library, Search, Settings, artist, album, playlist, and Now Playing screens around frosted glass, a warped artwork backdrop, motion, and a search overlay. Settings is a hub with Audio & playback, Appearance, Connections, and Storage & updates pages.
        - **Adaptive Mix**: Renamed from Smart Crossfade. Mobile runs the desktop mix and Best Mix in process, plans blends from measured bass, vocal, and beat evidence, and keeps the natural boundary for clashing production styles.
        - **Beat Tracking**: Replaced the beat tracker with a pure-DSP implementation shared with desktop and shipped a reduced-op ONNX Runtime, cutting the native library from 33 MB to 12 MB.
        - **Shared Providers**: YouTube and Qobuz run the desktop JavaScript providers through one QuickJS host, including matching, signing, and OAuth.
        - **Best Mix**: Analyzes saver streams like desktop, keeps run state across screens, and allows one run at a time.

        ### Fixed
        - **Cold Start**: The restored song resolves from its saved track so the wrong recording is no longer cached after opening the app.
        - **Opus Decoding**: Honor discard padding and decode in process, fixing garbled song tails and cutting analysis from tens of seconds to about two.
        - **Mixes**: Prepared mixes survive seeks, the transition marker no longer flashes the outgoing song, and the mix indicator hides after manual skips.
        - **Playback Sources**: Reject mismatched audio versions and animated artwork, and isolate cached audio by source so signing in to Qobuz applies without a restart.
        - **Downloads and Playlists**: Restored the collection and playlist download option and fixed playlist additions that used the wrong track version.
        - **YouTube Accounts**: Fixed account switching and profile loading.
        - **Touch Handling**: Taps no longer fall through the full player.

        ### Maintenance
        - **Runtime**: Replaced the Google WebRTC library with libdatachannel, updated Media3 to 1.11.1, and added LiteRT for Beat This and translation.
        - **Builds**: Added an installable x86 canary variant, ProGuard rules for LiteRT, and lint fixes.
        - **Testing & Tooling**: Added on-device Best Mix parity and adaptive mix benchmarks, Discord, Connect, translation, and artwork tests.
        """
            .trimIndent()
}

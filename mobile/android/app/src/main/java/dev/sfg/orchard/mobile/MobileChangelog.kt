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
        ### Apple Music

        - **A second music source**: Connect Apple Music under Settings > Connections, then use the music-source switch at the top of Settings. Home recommendations, search, artists, albums, playlists, and Library follow your selection. Sign-in requires an Orchard account and an Apple Music subscription; songs play through Android's native Widevine support.
        - **Your Apple Music library**: Browse saved songs, albums, artists, playlists, and recently played music. Like or unlike songs, create playlists, and add tracks to editable playlists. Liking a catalog song also adds it to your Apple Music library, and library tracks link to their catalog albums and artists where available.
        - **Stations and Autoplay**: Play song and artist stations, or choose Start Mix on a song to begin a related queue. Station queues keep fetching songs even with ordinary Autoplay switched off. Apple Music song queues can also refill through Autoplay. Live radio streams are not supported.
        - **Atmos on supported phones**: Settings > Playback offers Atmos quality when Apple Music is selected and connected and Android reports a compatible E-AC-3 JOC decoder. Orchard requests the song's Atmos stream and lets the device handle decoding and output. Songs without an available Atmos stream use stereo, and playback errors retry in stereo at the current position. Spatial output depends on the device and audio route.
        - **Atmos and transitions**: Crossfades, including AdaptiveMix, are skipped for Apple Music songs while Atmos quality is active. Choose a stereo quality if you want those transitions. Streaming quality can be selected separately for Wi-Fi and mobile data.
        - **Stereo streaming quality**: Saver and Normal prefer 64 kbps AAC for Apple Music; High prefers 256 kbps AAC, with fallback to an available rendition. Cached audio is separated by quality so a saved low-bitrate stream does not stand in for a higher-quality selection.
        - **Apple music videos**: Watch Apple's associated music video from an Apple Music song. Orchard searches YouTube when Apple has no associated video. Apple videos carry their own audio and video together, and playback remains in Orchard's player.
        - **Motion artwork and editorial pages**: Connected Apple Music accounts can supply Apple's own animated covers, artist imagery, and name artwork. Artist and album descriptions preserve formatting and links. Stations with broken composite artwork can use an artist photo instead.
        - **Playlists and upcoming albums**: Apple Music library playlists display their first page while later tracks load. Upcoming albums show the release date and unreleased track placeholders, while play and queue actions include only available songs.
        - **Listening history**: Save plays to Apple Music history is enabled by default in Settings > Playback and can be turned off. Plays are reported with album context and elapsed listening time so seeking ahead does not count as listening through the skipped portion. Apple Music listening stays out of YouTube Music history.
        - **Changing services**: Switching music source clears an incompatible song and queue playing on the phone, while local files and a remotely controlled device keep playing. Home waits for the saved source at startup and keeps YouTube library shelves out of Apple Music mode. Apple Music download actions are hidden; offline downloads and playback of library-only uploads without a catalog song ID are not supported.

        ### Phone player and navigation

        - **Redesigned Now Playing**: The player uses a blurred artwork backdrop and cover-colored controls. Its bottom dock switches between the cover, lyrics, and queue; the adjacent modes tray groups shuffle, repeat, sleep timer, Autoplay, and output-device selection.
        - **Swipe to close the player**: Drag the expanded player down like a sheet to return to browsing. The dismissal follows the gesture and can complete from a shorter pull or a fling.
        - **Hold to switch between song and video**: Press and hold the artwork or video to switch modes, with a progress ring showing the hold. This replaces the separate song/video toggle on the phone player.
        - **Previous-song swipes**: Swiping back on the cover goes to the previous song, including when controlling a Connect device, rather than restarting the current song after several seconds of playback.
        - **A smaller navigation dock**: The navigation tabs, mini-player, and search button form a split dock. Scrolling down collapses it to leave more room for content; scrolling up or tapping Show tabs expands it. Search stays available in its own button.
        - **Optional Settings tab**: Settings > Appearance lets you put Settings back in the phone navigation dock. It is optional, so you can keep the shorter tab row if you prefer.
        - **Reorganized search and settings**: Search uses a dedicated page with clearer result-category pills and a source-specific search prompt. Settings separates Playback, Mixing, Appearance, Artwork, Lyrics, Storage, Connections, and About, with the music-source selector near the top. The duplicate search button in the phone Home header is removed.

        ### Tablets and foldables

        - **Layouts for wide screens**: Tablets and unfolded devices use a desktop-style layout with a side navigation panel, larger Home shelves, and a persistent bottom player. Album and playlist pages put artwork and actions beside the track list. Now Playing can show artwork alongside lyrics or the queue, with transport controls arranged for the wider screen.
        - **Tabletop playback**: On a supported half-folded device with a horizontal hinge, the player places artwork above the hinge and controls, lyrics, or the queue below it. The layout uses the reported hinge position so controls do not straddle the fold.

        ### Library and discovery improvements

        - **A refreshed Library**: Library brings counts, sorting, grid/list choices, and play/shuffle actions into a clearer toolbar. Create groups new playlists, importing songs, and importing a folder in one sheet; playlists can be kept on the phone or created on the connected service. Playlist subtitles include song counts when available.
        - **Playlist search**: Search for playlists within Library and in the Add to Playlist picker. Matching normalizes the text so finding a playlist does not require scrolling through the full list.
        - **Home recommendations**: Home has a redesigned greeting, a larger discovery carousel, and paged Quick picks. Quick picks can start a mix from a song, and discovery combines available recommendations and personal mixes without duplicating entries.
        - **Start Mix**: The song menu can start a related-song queue directly from the selected YouTube Music or Apple Music song, without waiting for the current queue to run out.
        - **Identify with Shazam**: In builds with Shazam support, the identification button in Search listens through the microphone and searches the selected catalog for the recognized title and artist. It requires microphone permission, an internet connection, and an Orchard account. You can cancel, and listening stops when the app goes into the background.
        - **Playlist loading**: Reopening a playlist can show its cached contents while the latest version loads. Caches are separated by service and account, and YouTube Music pages load in parallel when their continuation data allows it.
        - **Catalog language and Recap**: Choose YouTube Music language in Settings > Playback to change catalog titles and metadata independently of the system default. The account card also links to YouTube Music Recap in the browser. The language option changes catalog content, not Orchard's interface language.
        - **Collection pages and sharing**: Phone album headers use a full-width square cover over an artwork-colored band. Compilation albums offer the participating artists for navigation instead of treating Various Artists as one artist. Sharing produces public Apple Music song, album, artist, and playlist links where available, and direct YouTube Music links for YouTube content.

        ### Lyrics and translation

        - **Choose your lyric sources**: Enable, disable, and reorder lyric providers in Settings > Lyrics, or choose a source for the current song from the lyrics view. When Apple Music is connected, Orchard can use its signed-in, word-timed lyrics in place of unofficial Apple lyric mirrors.
        - **Lyric motion controls**: Lines can glide into place one after another, with optional blur and fading for lines away from the current lyric. Instrumental gaps show progress dots. The Lyrics under the title switch controls the current-line preview in the phone player.
        - **Account translation and languages**: Signed-in users can choose Orchard Account as a lyric-translation provider. Settings shows the remaining daily allowance and reset time; cached translations do not use another allowance. Account and API translation support English, Spanish, Hindi, Korean, Japanese, and French. Downloaded local models continue to translate to English.
        - **Apple translations**: Enable Use Apple Music translations to prefer Apple's own translation when its lyrics include one for your chosen language. Other lyrics, or songs with no matching Apple translation, use the selected translation provider.

        ### Artwork and storage improvements

        - **Control the animated background**: Appearance settings adds speed, intensity, saturation, and brightness controls for the animated background, plus a reset action to restore its default look.
        - **Separate artwork quality settings**: Choose Automatic, Data saver, Balanced, or Highest for Wi-Fi and mobile data separately. Metered Wi-Fi uses the mobile-data preference. These choices apply when multiple video qualities are available; already-downloaded covers keep their saved quality.
        - **Bounded artwork cache**: Storage settings adds a separate animated-artwork cache limit, defaulting to 1 GB. Least-recently-used cached video is evicted to stay within the limit, including motion covers saved for offline playback. Storage accounting treats these covers as cache, and clearing cache removes them while preserving downloaded songs.
        - **Cover display fixes**: Show Whole Cover lets you replace an unsuitable tall motion cover with its square version, and Show Tall Cover restores it. Square animated covers have corrected phone-player sizing. Playlists retain their own cover when no playlist motion artwork exists instead of borrowing the first song's album animation.
        - **Less work offscreen**: Animated artwork and backgrounds pause when hidden or outside the active screen. Concurrent requests for the same artist imagery share one fetch, reducing duplicate requests while several parts of the interface need the same image.

        ### Mixing and playback improvements

        - **More deliberate transitions**: AdaptiveMix plans bass swaps, beatmatched blends, drop-ins, and beatless bridges using tempo, vocal timing, local musical key, and production compatibility. When a pair cannot support a blend, it can use a plain handoff with an explanatory status.
        - **Muffle transitions**: Muffle transitions filter the outgoing song and hand the bass to the incoming one at a suitable beat. They can enter partway into a verse and avoid cutting a trailing vocal over an incoming beatless intro. Tracks titled Intro or Skit are kept out of blends that would skip their opening.
        - **Reusable analysis**: AdaptiveMix and Best Mix retain per-track transition evidence for later use. Local analysis uses Nirovi for beats and Syvenu for vocal presence, with compact CPU models loaded from cached files rather than copied through large Java byte arrays. Vocal analysis handles 44.1 kHz songs at the correct duration.
        - **Optional NPU acceleration**: Settings > Mixing can offer NPU Support on compatible Snapdragon and MediaTek devices. Snapdragon setup downloads a device-specific add-on for you to install; supported MediaTek devices use their system runtime. Initial setup prepares and checks the models, and failed or unavailable NPU setup leaves mixing on the CPU.
        - **Apple Music mixing and Best Mix**: Apple Music stereo playback can use Apple's beat and vocal analysis for AdaptiveMix and Best Mix where supplied. Best Mix no longer asks you to download Apple Music songs before sorting them. Its queue action is shown when AdaptiveMix is enabled and enough upcoming songs are available.
        - **Queue and playback fixes**: The incoming song and artwork remain on screen through a mix handoff. Shuffling a collection generates a fresh order even when shuffle is already on, both locally and on a Connect target. YouTube audio-only matching checks artist identity as well as title and duration to avoid playing a different artist's same-named song.
        - **YouTube AI disclosures**: The existing AI-generated music preference also recognizes YouTube's Made with AI label. Mark, Skip, and Remove use those disclosures. Audio AI analysis is a separate, deprecated option that is off by default, since that detector can miss newer AI-generated music.
        - **Playback overhead**: Encrypted AAC playback uses a synchronous codec adapter to avoid unnecessary callback-thread overhead. The transition audio path also bypasses work when no mix, filter, gain adjustment, or normalization is needed.

        ### Connect and Android Auto

        - **Apple Music on a connected desktop**: Control Apple Music playback on an Orchard desktop that is connected to Apple Music. The desktop switches to the Apple catalog for those tracks, and the phone shows the Apple Music source mark and available tall artwork from the target.
        - **Remote playback in Android media controls**: When controlling another Connect device, Android's media controls show the remote song and send play, pause, seek, and skip commands to that device. The playhead advances between network updates. The remote media session is released when its target no longer has a song.
        - **Android Auto follows your music source**: The car browser uses the selected Apple Music or YouTube Music library. Catalog search, current-song likes, and library refreshes are supported through the media session. Account-related failures provide an Open Orchard action so you can resolve sign-in on the phone.
        - **Clearer system artwork and metadata**: System media controls request higher-resolution covers, and playback on the phone embeds a smaller cover in its media metadata for watches that do not fetch artwork URLs. Explicit-track metadata is also supplied to compatible media clients, and the player's E badge is centered.

        ### Sign-in and compatibility fixes

        - **Qobuz credentials**: Qobuz setup requires the app ID and secret issued to you by Qobuz, plus a user ID and auth token issued for that app. Orchard no longer supplies shared app credentials. Reconnect saved sessions that lack these credentials to keep using Qobuz matching and Max-quality FLAC playback.
        - **Returning from account sign-in**: The Orchard account browser flow provides an Open Orchard return button as well as attempting to reopen the app, avoiding a blank browser page when Android does not follow a custom-scheme redirect.
        - **Spotify sign-in display**: The Spotify login page displays correctly, with Orchard's navigation chrome hidden during sign-in and corrected WebView loading visibility.
        - **Discord presence**: Discord presence clears while playback is paused. Apple Music playback is identified as Apple Music and can link directly to the song. Hosted artwork expires after a day without use and can be uploaded again when needed.
        - **32-bit ARM support**: Android release builds include armeabi-v7a alongside arm64-v8a, extending installation support to compatible 32-bit ARM devices. Android 12 or newer is still required. NPU support and media formats remain dependent on the device.
        - **Smaller APK payloads**: Native libraries and DEX files are compressed in the APK, reducing their download footprint. Native libraries are extracted during installation, so the APK reduction is not an equivalent reduction in installed storage.

        ### Contributors

        - **@SFG5453**: Implemented the main Apple Music integration, native audio and video playback, Android Atmos, Shazam, shared mixing changes, wide-screen layouts, Connect and Android Auto integration, lyric providers and translation, storage controls, compatibility work, and the other fixes described here. Apple Music work was brought together in PR #232.
        - **@Julian-FF2000**: Implemented the phone player and Library redesigns, Home header/carousel/Quick picks, tabletop player, swipe-down dismissal, animated-background controls, lyrics-under-title setting, NPU path, Apple Music library, history reporting, source-switch handling, playlist pagination/caching, and watch-sized media artwork. Apple motion and editorial artwork was co-authored with @SFG5453; later history and artwork fixes were by @SFG5453.
        """
            .trimIndent()
}

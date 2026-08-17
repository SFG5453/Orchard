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
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Orchard. If not, see <https://www.gnu.org/licenses/>.
 */

export const ORCHARD_RELEASES = [
  {
    version: '4.5.2',
    codename: 'Obscurity Defroster',
    date: 'August 16, 2026',
    sections: [
      {
        title: 'Fixed',
        items: [
          'Orchard can now use smaller desktop windows, and Settings adapts its rows, options, actions, navigation, and Connect layout for narrow widths.'
        ]
      }
    ]
  },
  {
    version: '4.5.1',
    codename: 'Hellishly Code',
    date: 'August 16, 2026',
    sections: [
      {
        title: 'New & improved',
        items: [
          'Added a Network setting that lets album art and update checks ignore an unreachable system proxy while preserving system proxy behavior by default.',
          'Orchard Connect now prefers a reachable LAN address and offers alternate network links when the computer has multiple adapters.',
          'The full queue can now be opened directly from the compact queue panel.',
          'Immersive artwork veils adapt to cover colors so light covers remain readable without unnecessarily obscuring darker artwork.'
        ]
      },
      {
        title: 'Fixed',
        items: [
          'Normal playback no longer passes through the DJ crossover filters, preventing phase-related transient clipping while preserving crossover automation for DJ transition styles.',
          'The fullscreen transport and lyrics now fit on short displays instead of being clipped below the viewport.',
          'Copy actions now use the main-process clipboard bridge and browser fallbacks, fixing silent failures inside the desktop app.',
          'Catalog matching now preserves titles and artist names written in non-Latin scripts.'
        ]
      }
    ]
  },
  {
    version: '4.5.0',
    codename: 'Funds Lecterns',
    date: 'August 15, 2026',
    sections: [
      {
        title: 'New & improved',
        items: [
          'Added a persistent Global gain control that adjusts every track and playback path from -24 dB to +6 dB, even while the rest of the Audio Engine is bypassed.'
        ]
      }
    ]
  },
  {
    version: '4.4.1',
    codename: 'Hole Riddance',
    date: 'August 14, 2026',
    sections: [
      {
        title: 'Fixed',
        items: [
          'Restored direct YouTube Music playback after upstream protection changes caused tracks to fail with “no supported source” errors, including on macOS.',
          'Direct audio now uses video-bound proof-of-origin tokens and concrete byte ranges, while HLS remains a fallback for authenticated age-gated playback.',
          'Songs can be added to existing YouTube Music playlists again.'
        ]
      }
    ]
  },
  {
    version: '4.4.0',
    codename: 'Jocosity Jordanians',
    date: 'August 13, 2026',
    sections: [
      {
        title: 'New & improved',
        items: [
          'Smart Crossfade now measures each track\'s low-frequency structure and moves the bass handoff to a suitable beat when it detects an incoming bass entrance or outgoing bass exit.',
          'Added a persistent text and interface size control with scaling from 85% to 150%.',
          'Artists can now be subscribed to or unsubscribed from directly on their artist pages.',
          'The Queue is now available from the sidebar on narrow desktop layouts.'
        ]
      },
      {
        title: 'Fixed',
        items: [
          'Improved Wayland stability by respecting Niri-managed window geometry and allowing narrower tiled windows.',
          'Immersive artwork backgrounds now recover correctly after compositor and viewport resizes.',
          'Interface scaling is applied after the slider is released, avoiding unnecessary resizing while the control is being dragged.',
          'The welcome flow now remembers completed setup across packaged launches, and its Open Orchard, minimize, and close buttons work reliably.'
        ]
      },
      {
        title: 'Changed',
        items: [
          'Removed the redundant setup guide from Settings now that onboarding handles initial configuration.'
        ]
      }
    ]
  },
  {
    version: '4.3.3',
    codename: 'Rundowns Tonic',
    date: 'August 12, 2026',
    sections: [
      {
        title: 'New & improved',
        items: [
          'Added x86_64 and ARM64 Flatpak bundles, plus a native Windows ARM64 installer and Linux ARM64 AppImage with architecture-aware update metadata.'
        ]
      },
      {
        title: 'Fixed',
        items: [
          'Explicit lyrics no longer force songs through the lower-bitrate authenticated stream. Orchard now uses the normal high-quality route unless YouTube returns a real age restriction.',
          'YouTube session identities are refreshed on launch while player and sign-in caches are preserved, fixing "no playable audio format" failures that previously required deleting Orchard\'s local configuration directory.',
          'Authenticated direct and HLS playback are now reserved for confirmed age gates, with recovery state kept out of the persisted queue.'
        ]
      }
    ]
  },
  {
    version: '4.3.2',
    codename: 'Exerted Minimizes',
    date: 'August 11, 2026',
    sections: [
      {
        title: 'New & improved',
        items: [
          'Home and library feeds now load additional continuation pages, making more recommendations, albums, songs, and saved playlists available without manual pagination.',
          'Added a dedicated Playlists entry to the library navigation.'
        ]
      },
      {
        title: 'Fixed',
        items: [
          'Improved handling of newer YouTube Music home, library, and playlist response layouts, including the added pagination paths.',
          'Fixed removing mobile playlist tracks that appear on later playlist pages, and updated the visible playlist immediately after a successful removal.'
        ]
      }
    ]
  },
  {
    version: '4.3.1',
    codename: 'Zane Guessed',
    date: 'August 11, 2026',
    sections: [
      {
        title: 'Fixed',
        items: [
          'Restored playback for age-restricted YouTube tracks using authenticated WEB_REMIX playback, signature deciphering, and Safari HLS fallback.',
        ]
      }
    ]
  },
  {
    version: '4.3.0',
    codename: 'Persian Inklings',
    date: 'August 7, 2026',
    sections: [
      {
        title: 'New & improved',
        items: [
          'Orchard Mobile 1.1.0 is now available on the Orchard Website for Android! Includes age-gated stream support, playlist creation, song sharing, and full remote analysis pairing.',
          'Added Orchard Connect track analysis protocol (v3) enabling paired mobile devices to request track analysis and receive cloud sync results.',
        ]
      }
    ]
  },
  {
    version: '4.2.0',
    codename: 'Praise Perceived',
    date: 'August 4, 2026',
    sections: [
      {
        title: 'New & improved',
        items: [
          'Orchard Mobile 1.0.0 is now available on the Orchard Website for Android! Check it out!',
          'Orchard can now remember the window size and close to tray.',
        ]
      },
      {
        title: 'Fixed',
        items: [
          'Shuffling a playlist now shuffles all songs in the playlist instead of the typical 100 songs',
        ]
      }
    ]
  },
  {
    version: '4.1.0',
    codename: 'Flirty Gateau',
    date: 'August 2, 2026',
    sections: [
      {
        title: 'New & improved',
        items: [
          'Beat-matched transitions now ride a low-pass filter down the outgoing track instead of ducking its mid band. The corner starts above hearing and sweeps toward the bass crossover across the overlap, so the outgoing song thins out and recedes rather than simply getting quieter, and the movement itself is what covers the seam. Vocal activity still steers how far the sweep travels, and the low end is left alone until the existing bass swap hands it over.'
        ]
      }
    ]
  },
  {
    version: '4.0.0',
    codename: 'Nowadays Wrinkly',
    date: 'August 2, 2026',
    sections: [
      {
        title: 'New & improved',
        items: [
          'The new Canopy layout preset introduces a docked player, a compact sticky header, and denser rails to give the page more vertical room. Grove remains the default, and upgrading from 3.x opens a one-time prompt to try Canopy without changing anything else about your setup.',
          'Smart Crossfade has been rebuilt on neural analysis. ONNX models handle beat tracking and vocal separation, Essentia calibrates transition confidence, and a confidence-aware policy decides when a beat-matched blend is appropriate at all.',
          'Beat-matched transitions are rendered by a native, pitch-preserving WSOLA engine. Mixes align on a shared beat grid, hold the low end flat through the overlap before swapping decks, and measure transition length in beats rather than seconds.',
          'Vocal activity is now measured per frame instead of per track, so incoming vocals are kept out of the overlap and transitions no longer talk over themselves.',
          'The optional Continuous queue combines listening history, the current track, and upcoming songs in a single layout. Entries can be jumped to or removed from the queue page, right panel, and fullscreen player, and shared history stays synchronized during listening parties.',
          'Orchard can connect to Spotify and play a track\'s animated Canvas loop in place of static album art, falling back to the usual artwork whenever a Canvas is unavailable.',
          'Graphics settings offer Automatic and Integrated GPU modes, with low-power GPU selection and a guided restart after a mode change.',
          'The welcome flow has been redesigned around interactive layout, artwork, playback, and pairing choices.',
          'Navigation, queue changes, and home shelves now animate between states, and fullscreen artwork crossfades along with the music.',
          'Search supplements broad results with dedicated song and artist matches, then ranks exact matches by relevance and catalog popularity.',
          'The queue and your last open page are persisted through the main process, so a force quit no longer loses them.',
          'YouTube Brand Account switching handles Google\'s redirects, detects account and delegated-session changes, refreshes the active profile immediately, and remembers the selected browser identity across restarts.'
        ]
      },
      {
        title: 'Fixed',
        items: [
          'Gapless handoffs are now reserved for albums actually being played in order. Two tracks from the same album that meet through shuffle, a playlist, a hand-built queue, or a Best Mix reorder are treated as a mix and crossfaded.',
          'Smart Crossfade mutes the incoming deck before play, supports handoffs for music videos, prevents competing transition engines and queue edits from corrupting an active mix, avoids comb filtering at handoffs, preserves supported audio processing, and bounds long pre-rolls and overlaps.',
          'The Canopy transition overlay appears again during smart crossfades, and the bass handover point has been adjusted.',
          'Packages built against a system Electron runtime now locate the audio analyzer and both neural models, which had silently fallen back to heuristic analysis and disabled beat-matched transitions.',
          'Fixed a crash on launch on Apple Silicon Macs by enforcing proper ad-hoc code signing.',
          'Fixed YouTube searches occasionally failing with a "Cannot read properties of undefined" error, and patched album metadata parsing for recent upstream YouTube changes.',
          'Last.fm API calls use POST for authenticated requests and forward the user-agent.',
          'Immersive backgrounds stop rendering on the GPU when animation is disabled, playback is paused, or Orchard is hidden, and Linux restores the original GPU environment when returning to Automatic mode.',
          'Continuous queues keep skipped tracks out of listening history and preserve host-controlled navigation and shared history during listening parties.',
          'Custom artist sound effects play again after the content security policy was widened to allow their audio data.',
          'Account summaries prioritize channel handles and exclude subscriber counts from navigation headers, and long paused lyric lines no longer show a premature ellipsis.',
          'Arch Linux packages generate valid version strings.'
        ]
      },
      {
        title: 'Changed',
        items: [
          'Discord Rich Presence no longer offers the Orchard/YouTube Music card name option; the presence buttons already cover it.'
        ]
      },
      {
        title: 'Maintenance',
        items: [
          'Orchard is relicensed to AGPL-3.0-or-later as of 4.0.0.',
          'Updated Quasar to 2.23.2, and added a continuous integration workflow that runs the test suite and build on Node 26.'
        ]
      }
    ]
  }
];

export const LATEST_CHANGELOG_VERSION = ORCHARD_RELEASES[0]?.version || '';

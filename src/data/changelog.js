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
    version: '5.0.0-beta.9',
    codename: 'Mopping Blimpish',
    date: 'September 10, 2026',
    sections: [
      {
        title: 'New & improved',
        items: [
          'Added normalized Qobuz album and track quality metadata, including bit depth, sample rate, channel count, and streamability, with batched track lookups.',
          'Bundled the desktop transition planner and generated parity fixtures for mobile so beat-matched choreography and fallback behavior stay aligned across platforms.'
        ]
      },
      {
        title: 'Changed',
        items: [
          'Standard non-beatmatched fades now honor the configured 1–12 second duration and keep outgoing and incoming entry cues stable, including on short tracks.'
        ]
      },
      {
        title: 'Fixed',
        items: [
          'Persistent browser sessions now renew automatically for the selected Google account when cookies expire, while rendered and fallback crossfades preserve incoming cue timing and the usable audio window.'
        ]
      },
      {
        title: 'Maintenance',
        items: [
          'Updated @kawarp to 1.2.1, Electron to 43.7.0, Quasar to 2.32.0, Vite to 8.3.0, and Zod to 4.6.1; added Babel tooling for the shared planner build.',
          'Added coverage for browser session renewal, Qobuz catalog metadata and batching, and configured crossfade fallbacks.'
        ]
      }
    ]
  },
  {
    version: '5.0.0-beta.8',
    codename: 'Attained Silts',
    date: 'September 7, 2026',
    sections: [
      {
        title: 'New & improved',
        items: [
          'Added Qobuz account connection and confidently matched CD-quality lossless or Hi-Res playback with selectable quality and YouTube fallback.',
          'Accelerated Beat This analysis with the full FP32 model through ONNX Runtime WebGPU on supported systems, while retaining CPU and WASM fallbacks.'
        ]
      },
      {
        title: 'Changed',
        items: [
          'Reworked persisted audio analysis into a compact SQLite cache with a small hot in-memory LRU, version cleanup, and migration from the legacy JSON cache to reduce memory use.',
          'Made Smart Crossfade and EQ mutually exclusive so equalization cannot interfere with transition analysis, and added Qobuz lossless and Hi-Res playback quality labels.'
        ]
      },
      {
        title: 'Fixed',
        items: [
          'Qobuz matching only replaces a stream when it identifies the same recording confidently, while unsupported WebGPU targets and missing beat models continue through existing fallback paths.'
        ]
      },
      {
        title: 'Maintenance',
        items: [
          'Updated Quasar to 2.30.1, added the Qobuz package to production packaging, and switched the shipped desktop beat model to the self-contained FP32 artifact.',
          'Added coverage for Qobuz authentication and streaming, audio-analysis cache persistence, WebGPU compatibility, playback quality labels, and crossfade/EQ compatibility.'
        ]
      }
    ]
  },
  {
    version: '5.0.0-beta.7',
    codename: 'Tiles Dogcart',
    date: 'September 5, 2026',
    sections: [
      {
        title: 'New & improved',
        items: [
          'Redesigned the fullscreen player with artwork-led visuals, responsive layouts, unified lyrics, a richer queue surface, and virtualized long queues.',
          'Refreshed album detail pages with artwork-derived accent palettes, ambient album and video presentation, and more expressive responsive visuals.',
          'Added customizable Home and sidebar layouts with persistent ordering, visibility controls, and reset actions.',
          'Added Linux Debian/RPM and Windows NSIS installers for Orchard Packages, with managed package upgrades that replace direct Orchard installs.'
        ]
      },
      {
        title: 'Changed',
        items: [
          'Orchard Packages now reports shared, native, and Electron runtime sizes, reuses one downloaded Electron runtime across versions, and supports opening or uninstalling managed releases without removing that runtime.',
          'Unified desktop audio analysis and transition rendering in the Earmark native runtime and expanded platform-native asset collection for packaged releases.'
        ]
      },
      {
        title: 'Fixed',
        items: [
          'Fixed shuffled playlist queues so starting from a track does not discard the remaining tracks, and fixed fullscreen queue performance and artwork synchronization while seeking.',
          'Fixed Windows Orchard Packages launchers by safely terminating app paths and requiring the welcome entry point during installation validation, including legacy beta 6 layouts.'
        ]
      },
      {
        title: 'Maintenance',
        items: [
          'Updated Electron to 43.6.0, Quasar to 2.30.0, HLS.js to 1.7.2, Zod to 4.5.4, and the Undici override to 6.28.0; added Sharp and OGL for artwork visuals and removed the obsolete node-gyp and node-addon-api toolchain.',
          'Added regression coverage for package installation, uninstallation, launchers, Electron runtime handling, legacy layouts, and cross-platform native assets.'
        ]
      }
    ]
  },
  {
    version: '5.0.0-beta.6',
    codename: 'Promos Imbecile',
    date: 'August 29, 2026',
    sections: [
      {
        title: 'New & improved',
        items: [
          'Added offline music downloads for individual songs and full albums, artists, and playlists, with dedicated download management and playback while Orchard is offline.',
          'Improved Browse detail actions with visible labels, descriptive accessible names, and clearer responsive styling across artist and collection views.'
        ]
      },
      {
        title: 'Changed',
        items: [
          'Moved the welcome and setup experience into a standalone renderer so it loads only the onboarding state it needs while preserving preferences, sign-in, Orchard Connect, and diagnostics.'
        ]
      },
      {
        title: 'Fixed',
        items: [
          'Corrected Windows launcher path resolution across installed Orchard Packages layouts and added coverage for package asset and opener behavior.'
        ]
      },
      {
        title: 'Maintenance',
        items: [
          'Removed the retired Rubber Band native renderer and vendored sources now that Earmark handles desktop and mobile transitions, reducing native build and package overhead.',
          'Slimmed production package payloads, improved platform-native asset selection, updated Quasar to 2.28.0 and Zod to 4.5.2, restored complete AGPL headers in the welcome renderer, and refreshed the main and mobile documentation and screenshots.'
        ]
      }
    ]
  },
  {
    version: '5.0.0-beta.5',
    codename: 'Teacup Obeisant',
    date: 'August 28, 2026',
    sections: [
      {
        title: 'New & improved',
        items: [
          'Added Data Saving settings so playback, artwork, artist previews, and other network-heavy features can be controlled together.',
          'Added queue-to-playlist actions throughout the queue and fullscreen player.',
          'Connect now supports reverse playback commands between paired devices and collection search from the remote experience.',
          'Redesigned the fullscreen player and queue with richer artwork presentation, clearer controls, improved responsive behavior, and reduced-motion support.'
        ]
      },
      {
        title: 'Changed',
        items: [
          'Softened the desktop visual hierarchy across the shell, home, search, cards, player bars, overlays, and sidebars.',
          'Reduced immersive background rendering cost while preserving the artwork-driven presentation.'
        ]
      },
      {
        title: 'Fixed',
        items: [
          'Repaired welcome-window controls, sizing, and settings synchronization.',
          'Preserved safe Electron archive symlinks during package installation.'
        ]
      },
      {
        title: 'Maintenance',
        items: [
          'Streamlined desktop packaging and audio runtime handling, removed the obsolete Windows launcher, and updated Vue to 3.5.42.'
        ]
      }
    ]
  },
  {
    version: '5.0.0-beta.4',
    codename: 'Simpatico Gladden',
    date: 'August 24, 2026',
    sections: [
      {
        title: 'Fixed',
        items: [
          'Fixed Orchard Packages beta installs so beta releases are discovered, downloaded, and opened from their GitHub release instead of the stable package service.'
        ]
      }
    ]
  },
  {
    version: '5.0.0-beta.3',
    codename: 'Curates Admixture',
    date: 'August 24, 2026',
    sections: [
      {
        title: 'New & improved',
        items: [
          'Best Mix now ranks tracks by executable DJ choreography, with exact staged transition automation shared across the desktop and mobile playback engines.',
          'Introduced the new Orchard package manager and a GitHub-backed updater with managed Linux package downloads and automatic beta-channel selection.',
          'Improved responsive player, title bar, sidebar, fullscreen, and windowed layouts.'
        ]
      },
      {
        title: 'Fixed',
        items: [
          'Strengthened transition timing, beat-grid alignment, vocal-collision handling, cue boundaries, audible track-tail protection, and WSOLA source timeline mapping.',
          'Reduced repeated transition planning and audio-analysis overhead while rejecting stale cloud analysis results.'
        ]
      },
      {
        title: 'Maintenance',
        items: [
          'Added Arch Linux packaging, repaired Flatpak and Linux x64 builds, bundled the required C++ runtime, and updated Quasar and @xmldom/xmldom dependencies.'
        ]
      }
    ]
  },
  {
    version: '5.0.0-beta.1',
    codename: 'Aerie Hymned',
    date: 'August 20, 2026',
    sections: [
      {
        title: 'New & improved',
        items: [
          'Integrated the earmark transition engine via a native N-API Rust module, bringing high-performance beat grid alignment, energy and loudness analysis, and constraint-based DJ transition planning to desktop crossfades.'
        ]
      },
      {
        title: 'Fixed',
        items: [
          'Beta channel update checks now gracefully handle missing release manifests, reporting when Orchard is up to date or reminding you when the corresponding stable version has released so you can switch to the release channel.'
        ]
      },
      {
        title: 'Maintenance',
        items: [
          'Updated Vite to 8.2.2 and sass-embedded to 1.103.1.'
        ]
      }
    ]
  }
];

export const LATEST_CHANGELOG_VERSION = ORCHARD_RELEASES[0]?.version || '';

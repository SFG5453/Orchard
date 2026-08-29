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

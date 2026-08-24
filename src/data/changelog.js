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

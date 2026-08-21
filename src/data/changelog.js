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

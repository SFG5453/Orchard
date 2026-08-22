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

import assert from 'node:assert/strict';
import test from 'node:test';
import { generateAndroidUpdateMetadata } from '../scripts/generate-android-update-metadata.mjs';

test('generates Android update metadata JSON with release notes and codename', () => {
  const result = generateAndroidUpdateMetadata({
    version: '1.0.0',
    codename: 'Praise Perceived',
    versionCode: 1,
    apkUrl: 'https://downloads.sfg545.dev/orchard/Orchard-1.0.0.apk',
    sha256: 'abc123hash',
    publishedAt: '2026-08-07T00:00:00Z',
    releaseNotes: '## Mobile Release Notes\n- Feature A\n- Feature B',
  });

  const parsed = JSON.parse(result);
  assert.equal(parsed.version, '1.0.0');
  assert.equal(parsed.codename, 'Praise Perceived');
  assert.equal(parsed.versionCode, 1);
  assert.equal(parsed.apkUrl, 'https://downloads.sfg545.dev/orchard/Orchard-1.0.0.apk');
  assert.equal(parsed.sha256, 'abc123hash');
  assert.equal(parsed.releaseNotes, '## Mobile Release Notes\n- Feature A\n- Feature B');
});

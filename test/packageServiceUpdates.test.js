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

import test from 'node:test';
import assert from 'node:assert/strict';
import {
  latestBetaPackageManifest,
  packageServiceTarget,
  resolveUpdateChannel,
  selectPackageServiceRelease
} from '../electron/integrations/packageServiceUpdates.js';

const manifest = {
  schemaVersion: 1,
  releases: [
    { version: '5.1.0', channel: 'stable', native: { 'linux-x64': {} } },
    { version: '5.2.0-beta.1', channel: 'beta', native: { 'linux-x64': {} } }
  ]
};

test('selects package-service updates for the current target and channel', () => {
  const result = selectPackageServiceRelease(manifest, {
    channel: 'stable',
    currentVersion: '5.0.0',
    target: 'linux-x64'
  });
  assert.equal(result.latest.version, '5.1.0');
  assert.equal(result.updateAvailable, true);
});

test('does not offer an unavailable target or the installed version', () => {
  assert.equal(selectPackageServiceRelease(manifest, {
    channel: 'stable', currentVersion: '5.1.0', target: 'linux-x64'
  }).updateAvailable, false);
  assert.equal(selectPackageServiceRelease(manifest, {
    channel: 'stable', currentVersion: '5.0.0', target: 'darwin-arm64'
  }).latest, null);
});

test('normalizes package-service targets', () => {
  assert.equal(packageServiceTarget('linux', 'amd64'), 'linux-x64');
  assert.equal(packageServiceTarget('win32', 'aarch64'), 'win32-arm64');
});

test('selects a complete package manifest from the latest GitHub beta', () => {
  assert.deepEqual(latestBetaPackageManifest([
    { prerelease: true, draft: false, assets: [{ name: 'other.txt', browser_download_url: 'https://github.com/sfg5453/orchard/releases/download/v5.2.0-beta.2/other.txt' }] },
    { prerelease: true, draft: false, assets: [{ name: 'manifest.json', browser_download_url: 'https://github.com/sfg5453/orchard/releases/download/v5.2.0-beta.1/manifest.json' }] }
  ]), {
    manifestUrl: 'https://github.com/sfg5453/orchard/releases/download/v5.2.0-beta.1/manifest.json',
    baseURL: 'https://github.com/sfg5453/orchard/releases/download/v5.2.0-beta.1/'
  });
});

test('a prerelease build resolves to the GitHub beta endpoint whatever is stored', () => {
  assert.equal(resolveUpdateChannel('stable', '5.0.0-beta.2'), 'beta');
  assert.equal(resolveUpdateChannel('beta', '5.0.0-beta.2'), 'beta');
  assert.equal(resolveUpdateChannel('', '5.0.0-beta.2'), 'beta');
});

test('a stable build keeps the stored channel', () => {
  assert.equal(resolveUpdateChannel('stable', '5.0.0'), 'stable');
  assert.equal(resolveUpdateChannel('beta', '5.0.0'), 'beta');
  assert.equal(resolveUpdateChannel('nonsense', '5.0.0'), 'stable');
});

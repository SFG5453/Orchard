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
import { mergeMacUpdateMetadata } from '../scripts/merge-macos-update-metadata.mjs';
import { mergeWindowsUpdateMetadata } from '../scripts/merge-windows-update-metadata.mjs';
import { generateAndroidUpdateMetadata } from '../scripts/generate-android-update-metadata.mjs';

function metadata(version, url, sha512) {
  return `version: ${version}\nfiles:\n  - url: ${url}\n    sha512: ${sha512}\n    size: 123\npath: ${url}\nsha512: ${sha512}\nreleaseNotes: |\n  Notes for ${version}\n`;
}

test('merges x64 and arm64 files into macOS update metadata', () => {
  const result = mergeMacUpdateMetadata(
    metadata('3.1.0', 'Orchard-3.1.0-mac-x64.zip', 'x64hash'),
    metadata('3.1.0', 'Orchard-3.1.0-mac-arm64.zip', 'arm64hash')
  );

  assert.match(result, /files:\n  - url: Orchard-3\.1\.0-mac-arm64\.zip/);
  assert.match(result, /  - url: Orchard-3\.1\.0-mac-x64\.zip/);
  assert.match(result, /path: Orchard-3\.1\.0-mac-x64\.zip/);
  assert.match(result, /releaseNotes: \|\n  Notes for 3\.1\.0/);
});

test('rejects mismatched macOS update versions', () => {
  assert.throws(
    () => mergeMacUpdateMetadata(
      metadata('3.1.0', 'Orchard-3.1.0-mac-x64.zip', 'x64hash'),
      metadata('4.0.0-beta.1', 'Orchard-4.0.0-beta.1-mac-arm64.zip', 'arm64hash')
    ),
    /versions do not match/
  );
});

test('merges x64 and arm64 files into Windows update metadata with x64 first', () => {
  const result = mergeWindowsUpdateMetadata(
    metadata('4.3.2', 'Orchard-Setup-4.3.2.exe', 'x64hash'),
    metadata('4.3.2', 'Orchard-Setup-4.3.2-arm64.exe', 'arm64hash')
  );

  assert.match(result, /files:\n  - url: Orchard-Setup-4\.3\.2\.exe[\s\S]*  - url: Orchard-Setup-4\.3\.2-arm64\.exe/);
  assert.match(result, /path: Orchard-Setup-4\.3\.2\.exe/);
  assert.match(result, /releaseNotes: \|\n  Notes for 4\.3\.2/);
});

test('rejects mismatched Windows update versions', () => {
  assert.throws(
    () => mergeWindowsUpdateMetadata(
      metadata('4.3.2', 'Orchard-Setup-4.3.2.exe', 'x64hash'),
      metadata('4.3.3', 'Orchard-Setup-4.3.3-arm64.exe', 'arm64hash')
    ),
    /versions do not match/
  );
});

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

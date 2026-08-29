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
import { createWriteStream } from 'node:fs';
import { access, chmod, mkdir, mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { pipeline } from 'node:stream/promises';
import { createZstdCompress } from 'node:zlib';
import { c as createTar } from 'tar';
import {
  extractElectronArchive,
  extractPackageArchive,
  formatUpdateBytes,
  launchPreparedPackageServiceUpdate,
  packageServiceLauncherContents,
  packageServiceInstallPaths
} from '../electron/integrations/packageServiceInstaller.js';

const ELECTRON_ZIP = Buffer.from(
  'UEsDBBQAAAAIADmcGl2RdNPMCgAAAAgAAAAQAAAAcnVudGltZS9lbGVjdHJvbkvNSU0uKcrPAwBQSwECFAAUAAAACAA5nBpdkXTTzAoAAAAIAAAAEAAAAAAAAAAAAAAAAAAAAAAAcnVudGltZS9lbGVjdHJvblBLBQYAAAAAAQABAD4AAAA4AAAAAAA=',
  'base64'
);
const ELECTRON_ASAR_ZIP = Buffer.from(
  'UEsDBBQAAAAIADmcGl3agaRpDgAAAAwAAAAaAAAAcmVzb3VyY2VzL2RlZmF1bHRfYXBwLmFzYXJLLE4sUihIrMzJT0wBAFBLAQIUABQAAAAIADmcGl3agaRpDgAAAAwAAAAaAAAAAAAAAAAAAAAAAAAAAAByZXNvdXJjZXMvZGVmYXVsdF9hcHAuYXNhclBLBQYAAAAAAQABAEgAAABGAAAAAAA=',
  'base64'
);

test('uses the major-version slot and shared Electron runtime layout', () => {
  assert.deepEqual(packageServiceInstallPaths({
    appDataDirectory: '/config',
    cacheDirectory: '/cache',
    electronVersion: '43.4.1',
    target: 'linux-x64',
    version: '5.7.2'
  }), {
    versionsRoot: path.join('/config', 'orchard', 'versions'),
    destination: path.join('/config', 'orchard', 'versions', '5.0.0'),
    runtimeDirectory: path.join('/config', 'orchard', 'runtimes', 'electron', '43.4.1', 'linux-x64'),
    cacheRoot: path.join('/cache', 'orchard-updates')
  });
});

test('formats update progress in readable units', () => {
  assert.equal(formatUpdateBytes(98566144), '94.0 MB');
  assert.equal(formatUpdateBytes(101034560), '96.4 MB');
  assert.equal(formatUpdateBytes(1536), '1.5 KB');
});

test('Windows launcher removes the trailing separator from the Electron app path', () => {
  assert.equal(
    packageServiceLauncherContents('win32-x64', '43.4.1'),
    '@echo off\r\n' +
      'set "app_root=%~dp0"\r\n' +
      'set "app_root=%app_root:~0,-1%"\r\n' +
      '"%app_root%\\..\\..\\runtimes\\electron\\43.4.1\\win32-x64\\electron.exe" "%app_root%" %*\r\n'
  );
});

test('extracts tar.zst packages without system tar or zstd', async () => {
  const root = await mkdtemp(path.join(tmpdir(), 'orchard-updater-test-'));
  const source = path.join(root, 'source');
  const output = path.join(root, 'output');
  const archive = path.join(root, 'package.tar.zst');
  await mkdir(path.join(source, 'dist'), { recursive: true });
  await writeFile(path.join(source, 'dist', 'index.html'), 'orchard');
  await pipeline(
    createTar({ cwd: source }, ['.']),
    createZstdCompress(),
    createWriteStream(archive)
  );
  await extractPackageArchive(archive, output);
  assert.equal(await readFile(path.join(output, 'dist', 'index.html'), 'utf8'), 'orchard');
});

test('extracts Electron ZIP files without an external unzip command', async () => {
  const root = await mkdtemp(path.join(tmpdir(), 'orchard-electron-zip-test-'));
  const archive = path.join(root, 'electron.zip');
  const output = path.join(root, 'output');
  await writeFile(archive, ELECTRON_ZIP);
  await extractElectronArchive(archive, output);
  assert.equal(await readFile(path.join(output, 'runtime', 'electron'), 'utf8'), 'electron');
});

test('extracts Electron ASAR payloads as ordinary files and restores ASAR handling', async (t) => {
  const root = await mkdtemp(path.join(tmpdir(), 'orchard-electron-asar-test-'));
  const archive = path.join(root, 'electron.zip');
  const output = path.join(root, 'output');
  const previousNoAsar = process.noAsar;
  t.after(() => {
    process.noAsar = previousNoAsar;
    return rm(root, { recursive: true, force: true });
  });

  process.noAsar = false;
  await writeFile(archive, ELECTRON_ASAR_ZIP);
  await extractElectronArchive(archive, output);

  assert.equal(await readFile(path.join(output, 'resources', 'default_app.asar'), 'utf8'), 'asar payload');
  assert.equal(process.noAsar, false);
});

test('restart helper retains or removes previous versions according to the setting', { skip: process.platform === 'win32' }, async (t) => {
  const root = await mkdtemp(path.join(tmpdir(), 'orchard-update-helper-test-'));
  const versionsRoot = path.join(root, 'versions');
  const destination = path.join(versionsRoot, '5.0.0');
  const launched = path.join(root, 'launched');
  t.after(() => rm(root, { recursive: true, force: true }));

  async function app(directory, version) {
    await mkdir(directory, { recursive: true });
    await writeFile(path.join(directory, '.orchard-package.json'), JSON.stringify({ schemaVersion: 1, version }));
    await writeFile(path.join(directory, 'orchard'), `#!/bin/sh\ntouch "${launched}"\n`);
    await chmod(path.join(directory, 'orchard'), 0o755);
  }
  async function waitFor(candidate) {
    for (let attempt = 0; attempt < 100; attempt += 1) {
      try { await access(candidate); return; } catch { await new Promise((resolve) => setTimeout(resolve, 50)); }
    }
    assert.fail(`Timed out waiting for ${candidate}`);
  }

  await app(destination, '5.0.0');
  const firstSession = path.join(root, 'first-session');
  const firstStaging = path.join(firstSession, 'orchard');
  await app(firstStaging, '5.1.0');
  await launchPreparedPackageServiceUpdate({
    destination,
    session: firstSession,
    staging: firstStaging,
    target: 'linux-x64',
    version: '5.1.0',
    versionsRoot
  }, { keepOldVersions: true, parentPid: 2147483647 });
  await waitFor(path.join(versionsRoot, '5.0.0.previous', '.orchard-package.json'));
  await waitFor(launched);
  assert.equal(JSON.parse(await readFile(path.join(destination, '.orchard-package.json'))).version, '5.1.0');

  await rm(launched, { force: true });
  const secondSession = path.join(root, 'second-session');
  const secondStaging = path.join(secondSession, 'orchard');
  await app(secondStaging, '5.2.0');
  await launchPreparedPackageServiceUpdate({
    destination,
    session: secondSession,
    staging: secondStaging,
    target: 'linux-x64',
    version: '5.2.0',
    versionsRoot
  }, { keepOldVersions: false, parentPid: 2147483647 });
  await waitFor(launched);
  assert.equal(JSON.parse(await readFile(path.join(destination, '.orchard-package.json'))).version, '5.2.0');
  await assert.rejects(access(path.join(versionsRoot, '5.0.0.previous')));
});

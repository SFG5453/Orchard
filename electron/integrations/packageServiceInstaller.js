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

import { createHash, randomUUID } from 'node:crypto';
import { spawn } from 'node:child_process';
import { createReadStream, createWriteStream } from 'node:fs';
import { access, chmod, lstat, mkdir, open, readFile, rename, rm, symlink, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { pipeline } from 'node:stream/promises';
import { createZstdDecompress } from 'node:zlib';
import { x as extractTar } from 'tar';
import { openPromise as openZip } from 'yauzl';

const VERSION_PATTERN = /^\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?$/;
const SHA256_PATTERN = /^[a-f0-9]{64}$/;
const MAX_RUNTIME_BYTES = 512 * 1024 * 1024;

async function exists(candidate) {
  try {
    await access(candidate);
    return true;
  } catch {
    return false;
  }
}

function cleanAsset(asset, baseURL, label) {
  const name = String(asset?.url || '');
  const size = Number(asset?.size);
  const sha256 = String(asset?.sha256 || '').toLowerCase();
  if (!name || name !== path.posix.basename(name)) throw new Error(`${label} has an invalid filename.`);
  if (!Number.isSafeInteger(size) || size <= 0) throw new Error(`${label} has an invalid size.`);
  if (!SHA256_PATTERN.test(sha256)) throw new Error(`${label} has an invalid checksum.`);
  const base = new URL(baseURL);
  const url = new URL(name, base);
  if (url.origin !== base.origin || !url.pathname.startsWith(base.pathname)) {
    throw new Error(`${label} points outside the Orchard package service.`);
  }
  return { name, size, sha256, url: url.toString() };
}

export function packageServiceInstallPaths({ appDataDirectory, cacheDirectory, electronVersion, target, version }) {
  if (!VERSION_PATTERN.test(version) || !VERSION_PATTERN.test(electronVersion)) {
    throw new Error('The Orchard package version is invalid.');
  }
  const orchardRoot = path.join(appDataDirectory, 'orchard');
  const versionsRoot = path.join(orchardRoot, 'versions');
  const slot = `${version.split('.')[0]}.0.0`;
  return {
    versionsRoot,
    destination: path.join(versionsRoot, slot),
    runtimeDirectory: path.join(orchardRoot, 'runtimes', 'electron', electronVersion, target),
    cacheRoot: path.join(cacheDirectory, 'orchard-updates')
  };
}

export function formatUpdateBytes(bytes) {
  const units = ['B', 'KB', 'MB', 'GB'];
  let value = Number(bytes) || 0;
  let unit = 0;
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024;
    unit += 1;
  }
  return `${value >= 100 || unit === 0 ? value.toFixed(0) : value.toFixed(1)} ${units[unit]}`;
}

async function downloadVerifiedFile({ asset, destination, fetchImpl, onProgress }) {
  const temporary = `${destination}.part`;
  await rm(temporary, { force: true });
  const response = await fetchImpl(asset.url, { redirect: 'follow' });
  if (!response.ok || !response.body) throw new Error(`Download failed with HTTP ${response.status}: ${asset.name}`);
  const declared = Number(response.headers.get('content-length') || asset.size || 0);
  const limit = asset.size || MAX_RUNTIME_BYTES;
  if (declared > limit) throw new Error(`${asset.name} is larger than expected.`);

  const output = await open(temporary, 'wx');
  const hash = createHash('sha256');
  let transferred = 0;
  try {
    for await (const chunk of response.body) {
      const bytes = Buffer.from(chunk);
      let offset = 0;
      while (offset < bytes.byteLength) {
        const { bytesWritten } = await output.write(bytes, offset, bytes.byteLength - offset);
        if (!bytesWritten) throw new Error(`${asset.name} stopped writing before it was complete.`);
        offset += bytesWritten;
      }
      hash.update(bytes);
      transferred += bytes.byteLength;
      if (transferred > limit) throw new Error(`${asset.name} is larger than expected.`);
      onProgress?.(transferred, declared || asset.size || 0);
    }
    await output.close();
    if (asset.size && transferred !== asset.size) throw new Error(`${asset.name} did not match its declared size.`);
    if (asset.sha256 && hash.digest('hex') !== asset.sha256) throw new Error(`${asset.name} failed checksum verification.`);
    await rename(temporary, destination);
  } catch (error) {
    await output.close().catch(() => {});
    await rm(temporary, { force: true }).catch(() => {});
    throw error;
  }
}

export async function extractPackageArchive(archivePath, destination) {
  await mkdir(destination, { recursive: true });
  await pipeline(
    createReadStream(archivePath),
    createZstdDecompress(),
    extractTar({ cwd: destination, preservePaths: false, strict: true })
  );
}

function safeZipDestination(root, entryName) {
  const normalized = path.posix.normalize(String(entryName || ''));
  if (!normalized || normalized === '..' || normalized.startsWith('../') || path.posix.isAbsolute(normalized) || /^[A-Za-z]:/.test(normalized)) {
    throw new Error(`Unsafe path in Electron archive: ${entryName}`);
  }
  const destination = path.resolve(root, ...normalized.split('/'));
  if (destination !== root && !destination.startsWith(`${root}${path.sep}`)) {
    throw new Error(`Unsafe path in Electron archive: ${entryName}`);
  }
  return destination;
}

async function assertNoSymlinkParent(root, destination) {
  const relative = path.relative(root, path.dirname(destination));
  let current = root;
  for (const part of relative.split(path.sep).filter(Boolean)) {
    current = path.join(current, part);
    try {
      if ((await lstat(current)).isSymbolicLink()) throw new Error(`Electron archive writes through a symbolic link: ${current}`);
    } catch (error) {
      if (error?.code === 'ENOENT') return;
      throw error;
    }
  }
}

export async function extractElectronArchive(archivePath, destination) {
  await mkdir(destination, { recursive: true });
  const root = path.resolve(destination);
  const archive = await openZip(archivePath, {
    autoClose: false,
    decodeStrings: true,
    strictFileNames: true,
    validateEntrySizes: true
  });
  try {
    for await (const entry of archive.eachEntry()) {
      const output = safeZipDestination(root, entry.fileName);
      await assertNoSymlinkParent(root, output);
      const mode = (entry.externalFileAttributes >>> 16) & 0xffff;
      const kind = mode & 0o170000;
      if (entry.fileName.endsWith('/') || kind === 0o040000) {
        try {
          if ((await lstat(output)).isSymbolicLink()) throw new Error(`Electron archive directory is a symbolic link: ${entry.fileName}`);
        } catch (error) {
          if (error?.code !== 'ENOENT') throw error;
        }
        await mkdir(output, { recursive: true });
        continue;
      }

      await mkdir(path.dirname(output), { recursive: true });
      const input = await archive.openReadStreamPromise(entry);
      if (kind === 0o120000) {
        if (entry.uncompressedSize > 4096) throw new Error(`Electron archive has an invalid symbolic link: ${entry.fileName}`);
        const chunks = [];
        for await (const chunk of input) chunks.push(Buffer.from(chunk));
        const target = Buffer.concat(chunks).toString('utf8');
        const resolvedTarget = path.resolve(path.dirname(output), target);
        if (!target || path.isAbsolute(target) || (resolvedTarget !== root && !resolvedTarget.startsWith(`${root}${path.sep}`))) {
          throw new Error(`Electron archive symbolic link escapes its destination: ${entry.fileName}`);
        }
        await symlink(target, output);
        continue;
      }
      if (kind !== 0 && kind !== 0o100000) throw new Error(`Unsupported entry in Electron archive: ${entry.fileName}`);
      try {
        if ((await lstat(output)).isSymbolicLink()) throw new Error(`Electron archive file is a symbolic link: ${entry.fileName}`);
      } catch (error) {
        if (error?.code !== 'ENOENT') throw error;
      }
      await pipeline(input, createWriteStream(output, { mode: mode & 0o777 || 0o644 }));
    }
  } finally {
    archive.close();
  }
}

function electronArchive(version, target) {
  const name = `electron-v${version}-${target}.zip`;
  return {
    name,
    url: `https://github.com/electron/electron/releases/download/v${version}/${name}`,
    size: 0,
    sha256: ''
  };
}

function electronExecutable(directory, target) {
  if (target.startsWith('win32-')) return path.join(directory, 'electron.exe');
  if (target.startsWith('darwin-')) return path.join(directory, 'Electron.app', 'Contents', 'MacOS', 'Electron');
  return path.join(directory, 'electron');
}

async function fetchElectronChecksum(fetchImpl, version, archiveName) {
  const response = await fetchImpl(`https://github.com/electron/electron/releases/download/v${version}/SHASUMS256.txt`);
  if (!response.ok) throw new Error(`Electron checksums returned HTTP ${response.status}.`);
  const lines = (await response.text()).split(/\r?\n/);
  for (const line of lines) {
    const [checksum, name] = line.trim().split(/\s+/);
    if (String(name || '').replace(/^\*/, '') === archiveName && SHA256_PATTERN.test(checksum || '')) return checksum;
  }
  throw new Error(`Electron's checksums do not contain ${archiveName}.`);
}

async function writeLauncher(directory, target, electronVersion) {
  let name;
  let contents;
  if (target.startsWith('win32-')) {
    name = 'orchard.cmd';
    contents = `@echo off\r\n"%~dp0..\\..\\runtimes\\electron\\${electronVersion}\\${target}\\electron.exe" "%~dp0" %*\r\n`;
  } else if (target.startsWith('darwin-')) {
    name = 'orchard';
    contents = `#!/bin/sh\napp_root=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)\nexec "$app_root/../../runtimes/electron/${electronVersion}/${target}/Electron.app/Contents/MacOS/Electron" "$app_root" "$@"\n`;
  } else {
    name = 'orchard';
    contents = `#!/bin/sh\napp_root=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)\nexec "$app_root/../../runtimes/electron/${electronVersion}/${target}/electron" --disable-setuid-sandbox "$app_root" "$@"\n`;
  }
  const launcher = path.join(directory, name);
  await writeFile(launcher, contents, { mode: 0o755 });
  if (!target.startsWith('win32-')) await chmod(launcher, 0o755);
}

async function validateStagedInstall(directory, version, target) {
  const required = [
    'package.json',
    'dist/index.html',
    'electron/main/index.js',
    '.orchard-package.json',
    `.orchard-native/${target}.json`,
    'native/build/Release/orchard_audio_analysis.node',
    `native-media/build/orchard-system-media-${target}.node`,
    `native-audio-rust/build/orchard-audio-transition-${target}.node`,
    target.startsWith('win32-') ? 'orchard.cmd' : 'orchard'
  ];
  if (target !== 'darwin-x64') {
    const [platform, architecture] = target.split('-');
    required.push(`node_modules/onnxruntime-node/bin/napi-v6/${platform}/${architecture}/onnxruntime_binding.node`);
  }
  for (const relativePath of required) {
    if (!await exists(path.join(directory, relativePath))) {
      throw new Error(`The staged update is incomplete: ${relativePath} is missing.`);
    }
  }
  const metadata = JSON.parse(await readFile(path.join(directory, '.orchard-package.json'), 'utf8'));
  if (metadata?.schemaVersion !== 1 || metadata?.version !== version) {
    throw new Error('The staged update metadata does not match the selected release.');
  }
}

async function ensureElectronRuntime({ electronVersion, fetchImpl, onProgress, runtimeDirectory, session, target }) {
  if (await exists(electronExecutable(runtimeDirectory, target))) return;
  const archive = electronArchive(electronVersion, target);
  archive.sha256 = await fetchElectronChecksum(fetchImpl, electronVersion, archive.name);
  const archivePath = path.join(session, archive.name);
  const staging = path.join(session, 'electron-runtime');
  await downloadVerifiedFile({ asset: archive, destination: archivePath, fetchImpl, onProgress });
  await mkdir(staging, { recursive: true });
  await extractElectronArchive(archivePath, staging);
  if (!await exists(electronExecutable(staging, target))) throw new Error(`Electron ${electronVersion} is incomplete.`);
  await mkdir(path.dirname(runtimeDirectory), { recursive: true });
  await rm(runtimeDirectory, { recursive: true, force: true });
  await rename(staging, runtimeDirectory);
}

export async function preparePackageServiceUpdate({
  appDataDirectory,
  baseURL,
  cacheDirectory,
  fetchImpl,
  onProgress,
  release,
  target
}) {
  if (!VERSION_PATTERN.test(release?.version || '') || !VERSION_PATTERN.test(release?.electronVersion || '')) {
    throw new Error('The selected Orchard release is invalid.');
  }
  const native = release.native?.[target];
  const sharedAsset = cleanAsset(release.shared, baseURL, 'The common package');
  const nativeAsset = cleanAsset(native, baseURL, `The ${target} package`);
  const paths = packageServiceInstallPaths({
    appDataDirectory,
    cacheDirectory,
    electronVersion: release.electronVersion,
    target,
    version: release.version
  });
  const session = path.join(paths.cacheRoot, 'sessions', randomUUID());
  const staging = path.join(session, 'orchard');
  await mkdir(session, { recursive: true });

  try {
    const assets = [sharedAsset, nativeAsset];
    const total = assets.reduce((sum, asset) => sum + asset.size, 0);
    let completed = 0;
    for (const [index, asset] of assets.entries()) {
      const archivePath = path.join(session, asset.name);
      await downloadVerifiedFile({
        asset,
        destination: archivePath,
        fetchImpl,
        onProgress: (transferred) => onProgress?.({
          phase: 'packages',
          label: index === 0 ? 'common package' : `${target} package`,
          transferred: completed + transferred,
          total
        })
      });
      completed += asset.size;
      await extractPackageArchive(archivePath, staging);
    }

    await writeLauncher(staging, target, release.electronVersion);
    await validateStagedInstall(staging, release.version, target);
    await ensureElectronRuntime({
      electronVersion: release.electronVersion,
      fetchImpl,
      runtimeDirectory: paths.runtimeDirectory,
      session,
      target,
      onProgress: (transferred, total) => onProgress?.({
        phase: 'electron',
        label: 'Electron runtime',
        transferred,
        total
      })
    });

    return {
      destination: paths.destination,
      session,
      staging,
      target,
      version: release.version,
      versionsRoot: paths.versionsRoot
    };
  } catch (error) {
    await rm(session, { recursive: true, force: true }).catch(() => {});
    throw error;
  }
}

const UPDATE_HELPER_SOURCE = String.raw`
const fs = require('node:fs');
const path = require('node:path');
const { spawn } = require('node:child_process');
const payload = JSON.parse(process.argv[2]);
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
const alive = pid => { try { process.kill(pid, 0); return true; } catch (error) { return error.code !== 'ESRCH'; } };
const unique = base => { let value = base; for (let i = 2; fs.existsSync(value); i++) value = base + '-' + i; return value; };
const retryRename = async (from, to) => {
  let error;
  for (let i = 0; i < 120; i++) {
    try { fs.renameSync(from, to); return; } catch (caught) { error = caught; await sleep(250); }
  }
  throw error;
};
const launch = directory => {
  const windows = payload.target.startsWith('win32-');
  const launcher = path.join(directory, windows ? 'orchard.cmd' : 'orchard');
  const child = spawn(launcher, [], { detached: true, stdio: 'ignore', windowsHide: true, shell: windows });
  child.unref();
};
(async () => {
  while (alive(payload.parentPid)) await sleep(250);
  await sleep(500);
  fs.mkdirSync(payload.versionsRoot, { recursive: true });
  let previous = '';
  if (fs.existsSync(payload.destination)) {
    let oldVersion = 'previous';
    try { oldVersion = JSON.parse(fs.readFileSync(path.join(payload.destination, '.orchard-package.json'))).version || oldVersion; } catch {}
    previous = payload.keepOldVersions
      ? unique(path.join(payload.versionsRoot, oldVersion + '.previous'))
      : path.join(payload.session, 'previous');
    await retryRename(payload.destination, previous);
  }
  try {
    await retryRename(payload.staging, payload.destination);
  } catch (error) {
    if (previous && fs.existsSync(previous) && !fs.existsSync(payload.destination)) await retryRename(previous, payload.destination);
    throw error;
  }
  if (!payload.keepOldVersions) {
    for (const entry of fs.readdirSync(payload.versionsRoot)) {
      const candidate = path.join(payload.versionsRoot, entry);
      if (candidate !== payload.destination) fs.rmSync(candidate, { recursive: true, force: true });
    }
  }
  launch(payload.destination);
  fs.rmSync(payload.session, { recursive: true, force: true });
})().catch(error => {
  try { fs.writeFileSync(path.join(payload.session, 'update-error.txt'), String(error?.stack || error)); } catch {}
  if (fs.existsSync(payload.destination)) launch(payload.destination);
  process.exitCode = 1;
});
`;

export async function launchPreparedPackageServiceUpdate(prepared, { keepOldVersions = false, parentPid = process.pid } = {}) {
  const helper = path.join(prepared.session, 'apply-update.cjs');
  await writeFile(helper, UPDATE_HELPER_SOURCE, { mode: 0o700 });
  await chmod(helper, 0o700);
  const payload = JSON.stringify({
    ...prepared,
    keepOldVersions: keepOldVersions === true,
    parentPid
  });
  await new Promise((resolve, reject) => {
    const child = spawn(process.execPath, [helper, payload], {
      detached: true,
      env: { ...process.env, ELECTRON_RUN_AS_NODE: '1' },
      stdio: 'ignore',
      windowsHide: true
    });
    child.once('spawn', () => {
      child.unref();
      resolve();
    });
    child.once('error', reject);
  });
}

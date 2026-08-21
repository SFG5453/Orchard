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
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
 * A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Orchard. If not, see <https://www.gnu.org/licenses/>.
 */

import { existsSync, readFileSync } from 'node:fs';
import path from 'node:path';
import semver from 'semver';

const PACKAGE_DETAILS = Object.freeze({
  arch: Object.freeze({ label: 'Arch Linux package', installHint: 'Install it with your Arch package manager.' }),
  deb: Object.freeze({ label: 'Debian package', installHint: 'Open the downloaded DEB with your package manager.' }),
  flatpak: Object.freeze({ label: 'Flatpak bundle', installHint: 'Open the downloaded Flatpak bundle to update Orchard.' }),
  rpm: Object.freeze({ label: 'RPM package', installHint: 'Open the downloaded RPM with your package manager.' })
});

function normalizedPackageType(value) {
  const raw = String(value || '').trim().toLowerCase();
  if (!raw) return '';
  if (raw === 'pacman' || raw.includes('arch')) return 'arch';
  if (raw === 'deb' || raw.includes('debian') || raw.includes('ubuntu')) return 'deb';
  if (raw === 'rpm' || raw.includes('fedora') || raw.includes('red hat') || raw.includes('opensuse')) return 'rpm';
  if (raw.includes('flatpak')) return 'flatpak';
  return '';
}

function packageInfo(type, explicitLabel = '') {
  const details = PACKAGE_DETAILS[type];
  if (!details) return null;
  return {
    type,
    label: explicitLabel || details.label,
    installHint: details.installHint
  };
}

export function normalizedUpdateArchitecture(value) {
  const arch = String(value || '').trim().toLowerCase();
  if (arch === 'x64' || arch === 'x86_64' || arch === 'amd64') return 'x64';
  if (arch === 'arm64' || arch === 'aarch64') return 'arm64';
  return arch;
}

export function detectManagedUpdatePackage({
  platform = process.platform,
  architecture = process.arch,
  environment = process.env,
  resourcesPath = process.resourcesPath,
  fileExists = existsSync,
  readTextFile = (filePath) => readFileSync(filePath, 'utf8')
} = {}) {
  if (platform !== 'linux') return null;

  const explicitLabel = String(environment.ORCHARD_DISTRIBUTION_PACKAGE || '').trim();
  const explicitType = normalizedPackageType(explicitLabel);
  if (explicitType) {
    return { ...packageInfo(explicitType), arch: normalizedUpdateArchitecture(architecture) };
  }

  if (environment.FLATPAK_ID || fileExists('/.flatpak-info')) {
    return { ...packageInfo('flatpak'), arch: normalizedUpdateArchitecture(architecture) };
  }

  const packageTypePath = resourcesPath ? path.join(resourcesPath, 'package-type') : '';
  if (packageTypePath && fileExists(packageTypePath)) {
    try {
      const type = normalizedPackageType(readTextFile(packageTypePath));
      if (type) return { ...packageInfo(type), arch: normalizedUpdateArchitecture(architecture) };
    } catch {
      // A missing or unreadable marker falls back to Orchard's regular updater.
    }
  }

  // AppImages are intentionally absent: electron-updater can replace them
  // safely in place and should keep using Orchard's normal update flow.
  return null;
}

function safeAsset(asset) {
  if (!asset || typeof asset !== 'object') return null;
  const type = normalizedPackageType(asset.type);
  const arch = normalizedUpdateArchitecture(asset.arch);
  const name = path.basename(String(asset.name || '').trim());
  const sha256 = String(asset.sha256 || '').trim().toLowerCase();
  const size = Number(asset.size || 0);
  let url;

  try {
    url = new URL(String(asset.url || ''));
  } catch {
    return null;
  }

  if (!type || !arch || !name || url.protocol !== 'https:' || !/^[a-f0-9]{64}$/.test(sha256)) return null;
  if (!Number.isSafeInteger(size) || size <= 0) return null;
  const lowerName = name.toLowerCase();
  const hasExpectedExtension = (
    (type === 'arch' && /\.pkg\.tar\.(?:zst|xz)$/.test(lowerName)) ||
    (type === 'deb' && lowerName.endsWith('.deb')) ||
    (type === 'flatpak' && lowerName.endsWith('.flatpak')) ||
    (type === 'rpm' && lowerName.endsWith('.rpm'))
  );
  if (!hasExpectedExtension) return null;
  return {
    type,
    arch,
    name,
    url: url.toString(),
    sha256,
    size
  };
}

export function cleanManagedUpdateManifest(value) {
  if (!value || typeof value !== 'object' || Array.isArray(value)) {
    throw new Error('The desktop update manifest was not valid.');
  }
  if (value.schemaVersion !== 1) throw new Error('The desktop update manifest uses an unsupported schema.');

  const version = semver.valid(semver.clean(String(value.version || '')) || String(value.version || ''));
  if (!version) throw new Error('The desktop update manifest has an invalid version.');

  return {
    version,
    channel: value.channel === 'beta' ? 'beta' : 'stable',
    releaseDate: String(value.releaseDate || ''),
    releaseNotes: Array.isArray(value.releaseNotes)
      ? value.releaseNotes.map((note) => String(note || '').trim()).filter(Boolean)
      : String(value.releaseNotes || '').split(/\r?\n/).map((note) => note.trim()).filter(Boolean),
    assets: Array.isArray(value.assets) ? value.assets.map(safeAsset).filter(Boolean) : []
  };
}

export function selectManagedUpdateAsset(manifest, packageType, architecture) {
  const type = normalizedPackageType(packageType);
  const arch = normalizedUpdateArchitecture(architecture);
  return manifest?.assets?.find((asset) => asset.type === type && asset.arch === arch) || null;
}

export function managedUpdateAvailable(currentVersion, availableVersion) {
  const current = semver.valid(semver.clean(String(currentVersion || '')) || String(currentVersion || ''));
  const available = semver.valid(semver.clean(String(availableVersion || '')) || String(availableVersion || ''));
  return Boolean(current && available && semver.gt(available, current));
}

export function latestBetaManifestUrl(releases) {
  if (!Array.isArray(releases)) return '';

  for (const release of releases) {
    if (!release?.prerelease || release?.draft) continue;
    const asset = Array.isArray(release.assets)
      ? release.assets.find((candidate) => candidate?.name === 'latest-desktop.json')
      : null;
    try {
      const url = new URL(String(asset?.browser_download_url || ''));
      if (url.protocol === 'https:' && url.hostname === 'github.com') return url.toString();
    } catch {
      // Continue to an older desktop prerelease when this one has no valid manifest.
    }
  }

  return '';
}

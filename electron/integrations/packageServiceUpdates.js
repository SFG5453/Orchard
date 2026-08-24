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

import semver from 'semver';

export function packageServiceTarget(platform = process.platform, architecture = process.arch) {
  const normalizedPlatform = platform === 'win32' ? 'win32' : platform;
  const normalizedArchitecture = architecture === 'x64' || architecture === 'amd64'
    ? 'x64'
    : architecture === 'arm64' || architecture === 'aarch64'
      ? 'arm64'
      : architecture;
  return `${normalizedPlatform}-${normalizedArchitecture}`;
}

export function selectPackageServiceRelease(manifest, { channel, currentVersion, target }) {
  if (manifest?.schemaVersion !== 1 || !Array.isArray(manifest.releases)) {
    throw new Error('The Orchard package manifest is invalid.');
  }

  const releases = manifest.releases
    .filter((release) => release?.channel === channel && release?.native?.[target])
    .filter((release) => semver.valid(release.version))
    .sort((left, right) => semver.rcompare(left.version, right.version));
  const latest = releases[0] || null;
  const installedVersion = semver.valid(currentVersion) || semver.coerce(currentVersion)?.version || '0.0.0';
  return {
    latest,
    updateAvailable: Boolean(latest && semver.gt(latest.version, installedVersion))
  };
}

/**
 * Locates the manifest for a channel among GitHub releases, which are the only
 * place Orchard packages are published. Assets sit beside the manifest in the
 * same release, so the manifest's own URL is the base for everything it lists.
 */
export function latestPackageManifest(releases, channel = 'stable') {
  if (!Array.isArray(releases)) return null;
  const wantsPrerelease = channel === 'beta';
  for (const release of releases) {
    if (release?.draft) continue;
    if (Boolean(release?.prerelease) !== wantsPrerelease) continue;
    const asset = Array.isArray(release.assets)
      ? release.assets.find((candidate) => candidate?.name === 'manifest.json')
      : null;
    try {
      const manifestUrl = new URL(String(asset?.browser_download_url || ''));
      if (manifestUrl.protocol !== 'https:' || manifestUrl.hostname !== 'github.com') continue;
      return {
        manifestUrl: manifestUrl.toString(),
        baseURL: new URL('.', manifestUrl).toString()
      };
    } catch {
      // Continue to an older release with a complete package manifest.
    }
  }
  return null;
}

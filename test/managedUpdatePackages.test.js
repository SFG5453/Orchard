/*
 * Copyright (C) 2026 SFG545
 *
 * This file is part of Orchard.
 *
 * Orchard is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 */

import assert from 'node:assert/strict';
import test from 'node:test';
import {
  cleanManagedUpdateManifest,
  detectManagedUpdatePackage,
  latestBetaManifestUrl,
  managedUpdateAvailable,
  selectManagedUpdateAsset
} from '../electron/integrations/managedUpdatePackages.js';

const SHA256 = 'a'.repeat(64);

test('detects explicitly managed Arch and Flatpak packages', () => {
  assert.deepEqual(detectManagedUpdatePackage({
    platform: 'linux',
    architecture: 'x64',
    environment: { ORCHARD_DISTRIBUTION_PACKAGE: 'Arch Linux' },
    fileExists: () => false
  }), {
    type: 'arch',
    label: 'Arch Linux package',
    installHint: 'Install it with your Arch package manager.',
    arch: 'x64'
  });

  assert.equal(detectManagedUpdatePackage({
    platform: 'linux',
    architecture: 'arm64',
    environment: { FLATPAK_ID: 'dev.sfg.orchard' },
    fileExists: () => false
  })?.type, 'flatpak');
});

test('detects electron-builder DEB and RPM package markers', () => {
  for (const type of ['deb', 'rpm', 'pacman']) {
    const detected = detectManagedUpdatePackage({
      platform: 'linux',
      architecture: 'amd64',
      environment: {},
      resourcesPath: '/opt/orchard/resources',
      fileExists: (filePath) => filePath.endsWith('package-type'),
      readTextFile: () => type
    });
    assert.equal(detected?.type, type === 'pacman' ? 'arch' : type);
    assert.equal(detected?.arch, 'x64');
  }
});

test('leaves AppImage and non-Linux builds on the self updater', () => {
  assert.equal(detectManagedUpdatePackage({
    platform: 'linux',
    environment: { APPIMAGE: '/apps/Orchard.AppImage' },
    fileExists: () => false
  }), null);
  assert.equal(detectManagedUpdatePackage({ platform: 'darwin' }), null);
});

test('cleans a manifest and selects the package matching type and architecture', () => {
  const manifest = cleanManagedUpdateManifest({
    schemaVersion: 1,
    version: '4.7.3',
    channel: 'stable',
    releaseNotes: ['Package notifications'],
    assets: [
      {
        type: 'deb',
        arch: 'amd64',
        name: 'Orchard-4.7.3-amd64.deb',
        url: 'https://downloads.sfg545.dev/orchard/Orchard-4.7.3-amd64.deb',
        sha256: SHA256,
        size: 42
      },
      {
        type: 'deb',
        arch: 'arm64',
        name: 'Orchard-4.7.3-arm64.deb',
        url: 'https://downloads.sfg545.dev/orchard/Orchard-4.7.3-arm64.deb',
        sha256: SHA256,
        size: 43
      }
    ]
  });

  assert.equal(manifest.version, '4.7.3');
  assert.equal(selectManagedUpdateAsset(manifest, 'deb', 'x86_64')?.size, 42);
  assert.equal(selectManagedUpdateAsset(manifest, 'deb', 'aarch64')?.size, 43);
  assert.equal(managedUpdateAvailable('4.7.2', manifest.version), true);
  assert.equal(managedUpdateAvailable('4.7.3', manifest.version), false);
});

test('rejects unsafe artifact metadata', () => {
  const manifest = cleanManagedUpdateManifest({
    schemaVersion: 1,
    version: '4.7.3',
    assets: [{
      type: 'deb',
      arch: 'x64',
      name: '../../Orchard.deb',
      url: 'http://downloads.example.test/Orchard.deb',
      sha256: SHA256
    }]
  });
  assert.deepEqual(manifest.assets, []);
});

test('finds the newest desktop beta manifest and skips mobile-only releases', () => {
  const url = latestBetaManifestUrl([
    {
      prerelease: true,
      draft: false,
      assets: [{ name: 'latest-android.json', browser_download_url: 'https://github.com/example/mobile' }]
    },
    {
      prerelease: true,
      draft: false,
      assets: [{
        name: 'latest-desktop.json',
        browser_download_url: 'https://github.com/SFG5453/Orchard/releases/download/v4.8.0-beta.1/latest-desktop.json'
      }]
    }
  ]);

  assert.equal(url, 'https://github.com/SFG5453/Orchard/releases/download/v4.8.0-beta.1/latest-desktop.json');
});

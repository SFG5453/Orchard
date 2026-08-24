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
import path from 'node:path';
import test from 'node:test';

import {
  detectLegacyInstall,
  linuxRemovalCommand,
  parseDpkgOwner,
  parseRpmOwner,
  parseWindowsUninstallEntries,
  runsFromPackageService,
  selectWindowsLegacyEntry
} from '../electron/integrations/legacyInstall.js';

const WINDOWS_REGISTRY_OUTPUT = [
  'HKEY_CURRENT_USER\\Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\7zip',
  '    DisplayName    REG_SZ    7-Zip 24.09',
  '    UninstallString    REG_SZ    C:\\Program Files\\7-Zip\\Uninstall.exe',
  '',
  'HKEY_CURRENT_USER\\Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\c0ffee-orchard',
  '    DisplayName    REG_SZ    Orchard',
  '    DisplayVersion    REG_SZ    5.0.0-beta.1',
  '    InstallLocation    REG_EXPAND_SZ    C:\\Users\\sfg\\AppData\\Local\\Programs\\orchard',
  '    UninstallString    REG_SZ    C:\\Users\\sfg\\AppData\\Local\\Programs\\orchard\\Uninstall Orchard.exe',
  ''
].join('\r\n');

test('the package-service gate only matches installs beneath the versions root', () => {
  const versionsRoot = path.join('/home/sfg/.config', 'orchard', 'versions');

  assert.equal(runsFromPackageService(path.join(versionsRoot, '5.0.0', 'resources', 'app'), versionsRoot), true);
  assert.equal(runsFromPackageService('/opt/Orchard/resources/app', versionsRoot), false);
  // A sibling directory that merely shares the prefix is not the package service.
  assert.equal(runsFromPackageService(`${versionsRoot}-backup/5.0.0`, versionsRoot), false);
  assert.equal(runsFromPackageService('', versionsRoot), false);
});

test('the Windows uninstall entry is picked out of the full registry dump', () => {
  const entries = parseWindowsUninstallEntries(WINDOWS_REGISTRY_OUTPUT);
  assert.equal(entries.length, 2);

  const orchard = selectWindowsLegacyEntry(entries);
  assert.equal(orchard.displayName, 'Orchard');
  assert.equal(orchard.displayVersion, '5.0.0-beta.1');
  assert.equal(orchard.uninstallString, 'C:\\Users\\sfg\\AppData\\Local\\Programs\\orchard\\Uninstall Orchard.exe');

  assert.equal(selectWindowsLegacyEntry([{ displayName: 'Orchard', uninstallString: '' }]), null);
  assert.equal(selectWindowsLegacyEntry([{ displayName: 'Orchestra', uninstallString: 'x' }]), null);
});

test('Windows detection reports the native uninstaller as the removal path', async () => {
  const state = await detectLegacyInstall({
    platform: 'win32',
    exists: async () => false,
    runCommand: async (file, args) => ({
      ok: args[1].startsWith('HKCU'),
      stdout: args[1].startsWith('HKCU') ? WINDOWS_REGISTRY_OUTPUT : ''
    })
  });

  assert.equal(state.present, true);
  assert.equal(state.kind, 'windows-installer');
  assert.equal(state.label, 'Orchard 5.0.0-beta.1');
  assert.equal(state.removal.mode, 'uninstaller');
});

test('package ownership is read back as a package-manager removal command', () => {
  assert.equal(parseDpkgOwner('orchard: /opt/Orchard'), 'orchard');
  assert.equal(parseDpkgOwner('no path found matching pattern /opt/Orchard'), '');
  assert.equal(parseRpmOwner('orchard-5.0.0-1.x86_64'), 'orchard');
  assert.equal(parseRpmOwner('file /opt/Orchard is not owned by any package'), '');
  assert.equal(linuxRemovalCommand('dpkg', 'orchard'), 'sudo apt-get remove orchard');
  assert.equal(linuxRemovalCommand('rpm', 'orchard'), 'sudo dnf remove orchard');
  assert.equal(linuxRemovalCommand('dpkg', ''), '');
});

test('a package-managed Linux install is never offered as a file deletion', async () => {
  const state = await detectLegacyInstall({
    platform: 'linux',
    exists: async (target) => target === '/opt/Orchard',
    runCommand: async (file) => (file === 'dpkg-query'
      ? { ok: true, stdout: 'orchard: /opt/Orchard' }
      : { ok: false, stdout: '' })
  });

  assert.equal(state.kind, 'linux-package');
  assert.equal(state.removal.mode, 'command');
  assert.equal(state.removal.command, 'sudo apt-get remove orchard');
});

test('an unowned Linux directory falls back to revealing it', async () => {
  const state = await detectLegacyInstall({
    platform: 'linux',
    exists: async (target) => target === '/opt/Orchard',
    runCommand: async () => ({ ok: false, stdout: '' })
  });

  assert.equal(state.kind, 'linux-directory');
  assert.equal(state.removal.mode, 'reveal');
  assert.equal(state.removal.target, '/opt/Orchard');
});

test('detection reports nothing when no legacy install is on disk', async () => {
  const absent = { exists: async () => false, runCommand: async () => ({ ok: false, stdout: '' }) };

  assert.equal((await detectLegacyInstall({ platform: 'linux', ...absent })).present, false);
  assert.equal((await detectLegacyInstall({ platform: 'darwin', ...absent })).present, false);
  assert.equal((await detectLegacyInstall({ platform: 'freebsd', ...absent })).present, false);
});

test('macOS detection points at the Applications copy', async () => {
  const state = await detectLegacyInstall({
    platform: 'darwin',
    exists: async (target) => target === '/Applications/Orchard.app',
    runCommand: async () => ({ ok: false, stdout: '' })
  });

  assert.equal(state.kind, 'macos-application');
  assert.equal(state.removal.mode, 'reveal');
});

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

// Finds the pre-package-service Orchard left behind by the 5.0.0-beta.2 crossover
// build so the renderer can offer a one-time removal prompt. Removal always runs
// through the platform's own uninstaller -- Orchard never deletes those files
// itself, because package-managed installs must be retired by their manager.
import path from 'node:path';
import { execFile } from 'node:child_process';
import { access, readFile, writeFile } from 'node:fs/promises';

const WINDOWS_UNINSTALL_ROOTS = Object.freeze([
  'HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall',
  'HKLM\\Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall'
]);

const LINUX_INSTALL_DIRECTORIES = Object.freeze(['/opt/Orchard', '/opt/orchard', '/usr/lib/orchard']);

const MACOS_APPLICATION_PATH = '/Applications/Orchard.app';

const COMMAND_TIMEOUT_MS = 8_000;

function normalizePath(value) {
  return path.normalize(String(value || '').trim()).replace(/[\\/]+$/, '');
}

/**
 * True when the running app was installed by the Orchard package service, which
 * is the only situation where a separate legacy install is a leftover rather
 * than the copy the user is currently running.
 */
export function runsFromPackageService(appPath, versionsRoot) {
  const application = normalizePath(appPath);
  const root = normalizePath(versionsRoot);
  if (!application || !root) return false;
  const caseSensitive = process.platform !== 'win32';
  const left = caseSensitive ? application : application.toLowerCase();
  const right = caseSensitive ? root : root.toLowerCase();
  return left === right || left.startsWith(`${right}${path.sep}`);
}

export function parseWindowsUninstallEntries(output) {
  const entries = [];
  let current = null;

  for (const rawLine of String(output || '').split(/\r?\n/)) {
    const line = rawLine.trim();
    if (!line) continue;

    if (/^HKEY_/i.test(line)) {
      if (current) entries.push(current);
      current = { key: line, displayName: '', displayVersion: '', uninstallString: '', installLocation: '' };
      continue;
    }

    if (!current) continue;
    const value = line.match(/^(\S+)\s+REG_(?:SZ|EXPAND_SZ)\s+(.*)$/i);
    if (!value) continue;

    const name = value[1].toLowerCase();
    const data = value[2].trim();
    if (name === 'displayname') current.displayName = data;
    else if (name === 'displayversion') current.displayVersion = data;
    else if (name === 'uninstallstring') current.uninstallString = data;
    else if (name === 'installlocation') current.installLocation = data;
  }

  if (current) entries.push(current);
  return entries;
}

export function selectWindowsLegacyEntry(entries) {
  return (entries || []).find((entry) => (
    /^orchard\b/i.test(entry?.displayName || '') && Boolean(entry?.uninstallString)
  )) || null;
}

export function parseDpkgOwner(output) {
  const match = String(output || '').match(/^([^\s:]+):/m);
  return match ? match[1] : '';
}

export function parseRpmOwner(output) {
  const line = String(output || '').split(/\r?\n/).find((candidate) => candidate.trim());
  if (!line || /not owned by/i.test(line)) return '';
  return line.trim().replace(/-\d[\w.+]*-\d[\w.+]*\.[^-]+$/, '');
}

export function linuxRemovalCommand(manager, packageName) {
  if (!packageName) return '';
  if (manager === 'dpkg') return `sudo apt-get remove ${packageName}`;
  if (manager === 'rpm') return `sudo dnf remove ${packageName}`;
  return '';
}

function runCommandWith(execFileImpl) {
  return (file, args) => new Promise((resolve) => {
    execFileImpl(file, args, { timeout: COMMAND_TIMEOUT_MS, windowsHide: true }, (error, stdout) => {
      resolve({ ok: !error, stdout: String(stdout || '') });
    });
  });
}

async function detectWindowsLegacyInstall(runCommand) {
  for (const root of WINDOWS_UNINSTALL_ROOTS) {
    const { ok, stdout } = await runCommand('reg', ['query', root, '/s']);
    if (!ok) continue;

    const entry = selectWindowsLegacyEntry(parseWindowsUninstallEntries(stdout));
    if (!entry) continue;

    return {
      present: true,
      kind: 'windows-installer',
      label: entry.displayVersion ? `${entry.displayName} ${entry.displayVersion}` : entry.displayName,
      location: entry.installLocation || '',
      removal: { mode: 'uninstaller', target: entry.uninstallString, command: '' }
    };
  }
  return { present: false };
}

async function detectLinuxLegacyInstall(runCommand, exists) {
  for (const directory of LINUX_INSTALL_DIRECTORIES) {
    if (!await exists(directory)) continue;

    const dpkg = await runCommand('dpkg-query', ['-S', directory]);
    const packageName = dpkg.ok ? parseDpkgOwner(dpkg.stdout) : '';
    if (packageName) {
      return {
        present: true,
        kind: 'linux-package',
        label: packageName,
        location: directory,
        removal: { mode: 'command', target: '', command: linuxRemovalCommand('dpkg', packageName) }
      };
    }

    const rpm = await runCommand('rpm', ['-qf', directory]);
    const rpmName = rpm.ok ? parseRpmOwner(rpm.stdout) : '';
    if (rpmName) {
      return {
        present: true,
        kind: 'linux-package',
        label: rpmName,
        location: directory,
        removal: { mode: 'command', target: '', command: linuxRemovalCommand('rpm', rpmName) }
      };
    }

    return {
      present: true,
      kind: 'linux-directory',
      label: 'Orchard',
      location: directory,
      removal: { mode: 'reveal', target: directory, command: '' }
    };
  }
  return { present: false };
}

async function detectMacosLegacyInstall(exists) {
  if (!await exists(MACOS_APPLICATION_PATH)) return { present: false };
  return {
    present: true,
    kind: 'macos-application',
    label: 'Orchard',
    location: MACOS_APPLICATION_PATH,
    removal: { mode: 'reveal', target: MACOS_APPLICATION_PATH, command: '' }
  };
}

export async function detectLegacyInstall({
  platform = process.platform,
  exists,
  runCommand
} = {}) {
  if (platform === 'win32') return detectWindowsLegacyInstall(runCommand);
  if (platform === 'darwin') return detectMacosLegacyInstall(exists);
  if (platform === 'linux') return detectLinuxLegacyInstall(runCommand, exists);
  return { present: false };
}

const EMPTY_STATE = Object.freeze({
  status: 'absent',
  kind: '',
  label: '',
  location: '',
  removal: null,
  error: ''
});

export function setupLegacyInstallNotice({
  app,
  ipcMain,
  shell,
  channels,
  platform = process.platform,
  execFileImpl = execFile,
  exists = async (target) => { try { await access(target); return true; } catch { return false; } }
}) {
  const runCommand = runCommandWith(execFileImpl);
  const noticePath = path.join(app.getPath('userData'), 'legacy-install.json');
  const versionsRoot = path.join(app.getPath('appData'), 'orchard', 'versions');
  let state = { ...EMPTY_STATE, status: 'idle' };
  let detection = null;

  async function dismissed() {
    try {
      return JSON.parse(await readFile(noticePath, 'utf8'))?.dismissed === true;
    } catch {
      return false;
    }
  }

  async function markDismissed() {
    await writeFile(noticePath, JSON.stringify({ dismissed: true }, null, 2));
  }

  async function refresh() {
    // The crossover build itself is a legacy-style install, so the prompt stays
    // silent until Orchard is running from the package service.
    if (!runsFromPackageService(app.getAppPath(), versionsRoot)) {
      state = { ...EMPTY_STATE };
      return state;
    }

    if (await dismissed()) {
      state = { ...EMPTY_STATE, status: 'dismissed' };
      return state;
    }

    try {
      detection = await detectLegacyInstall({ platform, exists, runCommand });
      state = detection?.present
        ? { ...EMPTY_STATE, ...detection, status: 'present' }
        : { ...EMPTY_STATE };
    } catch (error) {
      state = { ...EMPTY_STATE, status: 'error', error: error.message || String(error) };
    }
    return state;
  }

  async function remove() {
    const removal = detection?.removal;
    if (!removal) return state;

    try {
      if (removal.mode === 'uninstaller') {
        const { ok } = await runCommand('cmd', ['/c', 'start', '', removal.target]);
        if (!ok) throw new Error('The Orchard uninstaller could not be started.');
      } else if (removal.mode === 'reveal') {
        shell.showItemInFolder(removal.target);
      } else {
        return state;
      }
    } catch (error) {
      state = { ...state, error: error.message || String(error) };
      return state;
    }

    await markDismissed();
    state = { ...EMPTY_STATE, status: 'dismissed' };
    return state;
  }

  ipcMain.handle(channels.GET_STATE, () => (state.status === 'idle' ? refresh() : state));
  ipcMain.handle(channels.REFRESH, () => refresh());
  ipcMain.handle(channels.REMOVE, () => remove());
  ipcMain.handle(channels.DISMISS, async () => {
    await markDismissed();
    state = { ...EMPTY_STATE, status: 'dismissed' };
    return state;
  });

  return { refresh, getState: () => state };
}

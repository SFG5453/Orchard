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

import test from 'node:test';
import assert from 'node:assert/strict';
import {
  ORCHARD_APP_USER_MODEL_ID,
  setWindowsAppDetails,
  windowsRelaunchCommand
} from '../electron/platform/windowsAppIdentity.js';

test('builds a direct Electron taskbar relaunch command for paths containing spaces', () => {
  assert.equal(
    windowsRelaunchCommand({
      executablePath: 'C:\\Users\\A User\\AppData\\Roaming\\orchard\\runtimes\\electron\\43.6.0\\win32-x64\\electron.exe',
      appPath: 'C:\\Users\\A User\\AppData\\Roaming\\orchard\\versions\\5.0.0'
    }),
    '"C:\\Users\\A User\\AppData\\Roaming\\orchard\\runtimes\\electron\\43.6.0\\win32-x64\\electron.exe" "C:\\Users\\A User\\AppData\\Roaming\\orchard\\versions\\5.0.0"'
  );
});

test('sets the Orchard identity and pin metadata on Windows windows', () => {
  let details;
  setWindowsAppDetails({ setAppDetails(value) { details = value; } }, {
    appPath: 'C:\\Orchard\\versions\\5.0.0',
    appIconPath: 'C:\\Orchard\\resources\\icon.ico',
    executablePath: 'C:\\Orchard\\electron.exe',
    platform: 'win32'
  });

  assert.deepEqual(details, {
    appId: ORCHARD_APP_USER_MODEL_ID,
    appIconPath: 'C:\\Orchard\\resources\\icon.ico',
    appIconIndex: 0,
    relaunchCommand: '"C:\\Orchard\\electron.exe" "C:\\Orchard\\versions\\5.0.0"',
    relaunchDisplayName: 'Orchard'
  });
});

test('does not set Windows shell metadata on other platforms', () => {
  setWindowsAppDetails({ setAppDetails() { assert.fail('unexpected Windows metadata'); } }, {
    platform: 'linux'
  });
});

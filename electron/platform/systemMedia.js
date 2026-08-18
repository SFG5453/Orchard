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

// Owns platform media-session integration and releases the native controls from
// `stop()`. MPRIS, SMTC and Now Playing all live in the native-media addon; this
// module only marshals state to it and commands back out.
import { createRequire } from 'node:module';
import { IPC_CHANNELS } from '../../shared/ipcChannels.js';

const require = createRequire(import.meta.url);
const { SYSTEM_MEDIA } = IPC_CHANNELS;

const DBUS_NAME = 'Orchard';
const DISPLAY_NAME = 'Orchard';
const DESKTOP_ENTRY = 'dev.sfg.orchard';

function loadAddon() {
  return require('../../native-media/index.cjs');
}

/**
 * The addon reports a command as `{ type, numberValue, boolValue, stringValue }`
 * because N-API has no untyped union. `handleSystemMediaCommand()` in the
 * renderer switches on `type` and reads a single `value`, so collapse it here
 * rather than teaching the renderer about the wire shape.
 */
function toRendererCommand(command) {
  const value = command.numberValue ?? command.boolValue ?? command.stringValue;
  return value === undefined || value === null
    ? { type: command.type }
    : { type: command.type, value };
}

/**
 * Windows needs the HWND to attach SMTC to. Electron hands it over as a raw
 * buffer whose width follows the platform pointer size.
 */
function windowHandle(window) {
  if (process.platform !== 'win32' || !window) return undefined;

  try {
    const handle = window.getNativeWindowHandle();
    if (!handle || handle.length === 0) return undefined;
    return handle.length === 8
      ? Number(handle.readBigUInt64LE(0))
      : handle.readUInt32LE(0);
  } catch {
    // A destroyed window has no handle; the caller retries on the next publish.
    return undefined;
  }
}

export function createSystemMediaService({
  emitCommand,
  getWindow = () => null,
  loadNativeMedia = loadAddon
}) {
  let controls = null;
  let unavailable = false;

  function start() {
    if (controls || unavailable) return controls;

    try {
      const { SystemMediaControls } = loadNativeMedia();
      const hwnd = windowHandle(getWindow());

      // Without an HWND there is nothing for SMTC to attach to, so defer rather
      // than burning the one-shot `unavailable` flag on a window that simply is
      // not open yet.
      if (process.platform === 'win32' && hwnd === undefined) return null;

      controls = new SystemMediaControls(
        {
          displayName: DISPLAY_NAME,
          dbusName: DBUS_NAME,
          desktopEntry: DESKTOP_ENTRY,
          hwnd
        },
        (command) => emitCommand(toRendererCommand(command))
      );
    } catch (error) {
      console.warn(`System media integration disabled: ${error.message}`);
      unavailable = true;
      controls = null;
    }

    return controls;
  }

  return {
    async publish(state) {
      if (!start()) return false;

      try {
        controls.setState(state);
        return true;
      } catch (error) {
        console.warn(`System media update failed: ${error.message}`);
        return false;
      }
    },
    stop() {
      if (!controls) return;

      try {
        controls.stop();
      } catch {
        // Teardown races with window destruction during quit.
      }

      controls = null;
    }
  };
}

export function setupSystemMediaHandlers({ ipcMain, app, getWindow }) {
  const systemMedia = createSystemMediaService({
    getWindow,
    emitCommand: (command) => {
      const window = getWindow();

      if (command.type === 'raise') {
        window?.show();
        window?.focus();
        return;
      }

      if (command.type === 'quit') {
        app.quit();
        return;
      }

      window?.webContents.send(SYSTEM_MEDIA.COMMAND, command);
    }
  });

  ipcMain.handle(SYSTEM_MEDIA.SET_STATE, async (_event, state) => {
    return systemMedia.publish(state);
  });

  return systemMedia;
}

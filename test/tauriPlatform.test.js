import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';

import { installTauriPlatform } from '../src/platform/desktop/tauri.js';
import { createTauriTransport } from '../src/platform/desktop/tauriTransport.js';

function emitWithReply(transport, event, payload = {}) {
  return new Promise((resolve) => transport.emit(event, payload, resolve));
}

test('Tauri graphics mode bridge returns the lifecycle state contract', async () => {
  const target = {};
  await installTauriPlatform(target);

  assert.deepEqual(await target.orchardApp.graphicsMode('integrated'), {
    selectedMode: 'integrated',
    appliedMode: 'integrated',
    integratedGpuSupported: false,
    platform: 'linux'
  });
});

test('Tauri session state is available synchronously after platform installation', async () => {
  const originalWindow = globalThis.window;
  const calls = [];
  globalThis.window = {
    __TAURI_INTERNALS__: {
      invoke(command, args) {
        calls.push({ command, args });
        return Promise.resolve(command === 'session_state_all'
          ? { 'orchard:last-page': '{"view":"home"}' }
          : true);
      }
    }
  };

  try {
    const target = {};
    await installTauriPlatform(target);
    assert.equal(target.orchardSessionState.get('orchard:last-page'), '{"view":"home"}');
    assert.equal(target.orchardSessionState.set('orchard:playback-state', '{"queue":[]}'), true);
    assert.equal(target.orchardSessionState.get('orchard:playback-state'), '{"queue":[]}');
    await new Promise((resolve) => setTimeout(resolve, 0));
  } finally {
    if (originalWindow === undefined) delete globalThis.window;
    else globalThis.window = originalWindow;
  }

  assert.deepEqual(calls, [
    { command: 'session_state_all', args: {} },
    {
      command: 'session_state_set',
      args: { key: 'orchard:playback-state', value: '{"queue":[]}' }
    }
  ]);
});

test('Tauri window bridge invokes every native window command', async () => {
  const calls = [];
  const originalWindow = globalThis.window;
  globalThis.window = {
    __TAURI_INTERNALS__: {
      invoke(command, args) {
        calls.push({ command, args });
        return Promise.resolve(true);
      }
    }
  };

  try {
    const target = {};
    await installTauriPlatform(target);
    assert.equal(await target.orchardWindow.minimize(), true);
    assert.equal(await target.orchardWindow.toggleMaximize(), true);
    assert.equal(await target.orchardWindow.setFullscreen(true), true);
    assert.equal(await target.orchardWindow.close(), true);
  } finally {
    if (originalWindow === undefined) delete globalThis.window;
    else globalThis.window = originalWindow;
  }

  assert.deepEqual(calls, [
    { command: 'session_state_all', args: {} },
    { command: 'window_minimize', args: {} },
    { command: 'window_toggle_maximize', args: {} },
    { command: 'window_set_fullscreen', args: { fullscreen: true } },
    { command: 'window_close', args: {} }
  ]);
});

test('Tauri Discord bridge invokes the native rich-presence commands', async () => {
  const calls = [];
  const originalWindow = globalThis.window;
  globalThis.window = {
    __TAURI_INTERNALS__: {
      invoke(command, args) {
        calls.push({ command, args });
        return Promise.resolve();
      }
    }
  };

  try {
    const target = {};
    const presence = { title: 'Signal', artist: 'Orchard' };
    await installTauriPlatform(target);
    await target.orchardDiscord.setPresence(presence);
    await target.orchardDiscord.clearPresence();
  } finally {
    if (originalWindow === undefined) delete globalThis.window;
    else globalThis.window = originalWindow;
  }

  assert.deepEqual(calls, [
    { command: 'session_state_all', args: {} },
    { command: 'discord_set_presence', args: { presence: { title: 'Signal', artist: 'Orchard' } } },
    { command: 'discord_clear_presence', args: {} }
  ]);
});

test('Tauri custom title bars are native drag regions with drag permission', async () => {
  const [groveTitlebar, canopyTitlebar, capability] = await Promise.all([
    readFile(new URL('../src/components/chrome/WindowTitlebar.vue', import.meta.url), 'utf8'),
    readFile(new URL('../src/components/chrome/CanopyTitlebar.vue', import.meta.url), 'utf8'),
    readFile(new URL('../linux/capabilities/default.json', import.meta.url), 'utf8')
  ]);

  assert.match(groveTitlebar, /<header[^>]+data-tauri-drag-region="deep"/);
  assert.match(canopyTitlebar, /<header[^>]+data-tauri-drag-region="deep"/);
  assert.ok(JSON.parse(capability).permissions.includes('core:window:allow-start-dragging'));
});

test('Tauri bypasses WebKitGTK wheel interpolation', async () => {
  const [commands, manifest] = await Promise.all([
    readFile(new URL('../linux/src/commands.rs', import.meta.url), 'utf8'),
    readFile(new URL('../linux/Cargo.toml', import.meta.url), 'utf8')
  ]);

  assert.match(commands, /set_enable_smooth_scrolling\(false\)/);
  assert.match(manifest, /webkit2gtk\s*=\s*"=2\.0\.2"/);
});

test('Tauri auth keeps the native cookie collector but updates the browser YouTube service', async () => {
  const calls = [];
  const originalInternals = globalThis.__TAURI_INTERNALS__;
  globalThis.__TAURI_INTERNALS__ = {
    invoke(command) {
      calls.push(command);
      return Promise.resolve({ cookie: command === 'auth_logout' ? '' : 'SAPISID=browser-session' });
    }
  };

  try {
    const transport = createTauriTransport();
    const signedIn = await emitWithReply(transport, 'auth:status');
    const signedOut = await emitWithReply(transport, 'auth:logout');

    assert.equal(signedIn.ok, true);
    assert.equal(signedIn.data.signedIn, true);
    assert.equal(signedOut.ok, true);
    assert.equal(signedOut.data.signedIn, false);
    assert.deepEqual(calls, ['auth_status', 'auth_logout']);
  } finally {
    if (originalInternals === undefined) delete globalThis.__TAURI_INTERNALS__;
    else globalThis.__TAURI_INTERNALS__ = originalInternals;
  }
});

test('Tauri transport publishes completed home shelves after the first response', async () => {
  const expectedUpdate = { home: { sections: [{ key: 'continued' }] } };
  const transport = createTauriTransport(async (request, { publish }) => {
    assert.equal(request.event, 'music:home');
    queueMicrotask(() => publish('music:home:update', expectedUpdate));
    return { home: { sections: [{ key: 'initial' }] } };
  });
  const updates = [];
  transport.on('music:home:update', (data) => updates.push(data));

  const response = await emitWithReply(transport, 'music:home');
  await new Promise((resolve) => setTimeout(resolve, 0));

  assert.equal(response.ok, true);
  assert.equal(response.data.home.sections[0].key, 'initial');
  assert.deepEqual(updates, [expectedUpdate]);
});

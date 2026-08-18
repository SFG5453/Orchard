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

// The MPRIS/SMTC/Now Playing protocol work lives in the native-media addon and
// is covered by building it; what is worth pinning here is the marshalling this
// module does around it. Every test injects a fake addon -- loading the real one
// would claim a D-Bus name and hold the event loop open.

import assert from 'node:assert/strict';
import test from 'node:test';

import { createSystemMediaService } from '../electron/platform/systemMedia.js';

function fakeAddon({ onConstruct } = {}) {
  const calls = { states: [], stopped: 0, options: null };
  let emit = null;

  class SystemMediaControls {
    constructor(options, onCommand) {
      calls.options = options;
      emit = onCommand;
      onConstruct?.();
    }

    setState(state) {
      calls.states.push(state);
    }

    stop() {
      calls.stopped += 1;
    }
  }

  return {
    load: () => ({ SystemMediaControls }),
    calls,
    emit: (command) => emit(command)
  };
}

const SAMPLE_STATE = {
  track: { id: 'track-1', title: 'Track', artist: 'A', artists: ['A'], album: '', thumbnail: '' },
  isPlaying: true,
  canGoNext: true,
  canGoPrevious: false,
  canSeek: true,
  currentTime: 1,
  durationSeconds: 100,
  volume: 1,
  repeatMode: 'off',
  shuffleEnabled: false
};

async function withPlatform(platform, run) {
  const original = Object.getOwnPropertyDescriptor(process, 'platform');
  Object.defineProperty(process, 'platform', { value: platform, configurable: true });

  try {
    await run();
  } finally {
    Object.defineProperty(process, 'platform', original);
  }
}

test('state reaches the addon and the controls are constructed once', async () => {
  const addon = fakeAddon();
  const service = createSystemMediaService({
    emitCommand: () => {},
    loadNativeMedia: addon.load
  });

  assert.equal(await service.publish(SAMPLE_STATE), true);
  assert.equal(await service.publish(SAMPLE_STATE), true);

  assert.equal(addon.calls.states.length, 2);
  assert.equal(addon.calls.states[0].track.id, 'track-1');
  assert.equal(addon.calls.options.dbusName, 'Orchard');
  assert.equal(addon.calls.options.desktopEntry, 'dev.sfg.orchard');
});

test('commands are flattened to the { type, value } shape the renderer switches on', async () => {
  const addon = fakeAddon();
  const received = [];
  const service = createSystemMediaService({
    emitCommand: (command) => received.push(command),
    loadNativeMedia: addon.load
  });

  await service.publish(SAMPLE_STATE);

  addon.emit({ type: 'next' });
  addon.emit({ type: 'seek', numberValue: 30 });
  addon.emit({ type: 'set-repeat-mode', stringValue: 'one' });

  assert.deepEqual(received, [
    { type: 'next' },
    { type: 'seek', value: 30 },
    { type: 'set-repeat-mode', value: 'one' }
  ]);
});

test('falsy command values survive the flattening', async () => {
  const addon = fakeAddon();
  const received = [];
  const service = createSystemMediaService({
    emitCommand: (command) => received.push(command),
    loadNativeMedia: addon.load
  });

  await service.publish(SAMPLE_STATE);

  // Muting and disabling shuffle both carry falsy payloads, so collapsing the
  // three typed fields must use ?? rather than || or they arrive as undefined.
  addon.emit({ type: 'set-volume', numberValue: 0 });
  addon.emit({ type: 'set-shuffle', boolValue: false });

  assert.deepEqual(received, [
    { type: 'set-volume', value: 0 },
    { type: 'set-shuffle', value: false }
  ]);
});

test('a failing addon degrades to a no-op instead of throwing', async () => {
  const warnings = [];
  const originalWarn = console.warn;
  console.warn = (message) => warnings.push(message);

  try {
    const service = createSystemMediaService({
      emitCommand: () => {},
      loadNativeMedia: () => {
        throw new Error('addon missing');
      }
    });

    assert.equal(await service.publish(SAMPLE_STATE), false);
    assert.equal(await service.publish(SAMPLE_STATE), false);
  } finally {
    console.warn = originalWarn;
  }

  // Once, not once per publish -- this is called on every timeupdate.
  assert.equal(warnings.length, 1);
  assert.match(warnings[0], /System media integration disabled/);
});

test('Windows defers startup until a window handle exists', async () => {
  await withPlatform('win32', async () => {
    const addon = fakeAddon();
    let window = null;

    const service = createSystemMediaService({
      emitCommand: () => {},
      getWindow: () => window,
      loadNativeMedia: addon.load
    });

    assert.equal(await service.publish(SAMPLE_STATE), false);
    assert.equal(addon.calls.options, null);

    // SMTC has nothing to attach to before the window exists, so the attempt
    // must be retried rather than disabling the integration permanently.
    window = { getNativeWindowHandle: () => Buffer.alloc(8, 7) };
    assert.equal(await service.publish(SAMPLE_STATE), true);
    assert.equal(typeof addon.calls.options.hwnd, 'number');
  });
});

test('stop releases the controls and is safe to call twice', async () => {
  const addon = fakeAddon();
  const service = createSystemMediaService({
    emitCommand: () => {},
    loadNativeMedia: addon.load
  });

  await service.publish(SAMPLE_STATE);
  service.stop();
  service.stop();

  assert.equal(addon.calls.stopped, 1);
});

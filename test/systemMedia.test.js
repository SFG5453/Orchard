import assert from 'node:assert/strict';
import test from 'node:test';

import { createSystemMediaService } from '../electron/platform/systemMedia.js';

class FakeInterface {
  constructor(name) {
    this.name = name;
  }

  static configureMembers() {}
  static emitPropertiesChanged() {}
}

function fakeDbus(events, { onRequestName } = {}) {
  const bus = {
    disconnect() {},
    export(path, playerInterface) {
      events.push(`export:${path}:${playerInterface.name}`);
    },
    requestName(name) {
      onRequestName?.();
      events.push(`request-name:${name}`);
      return Promise.resolve();
    },
    unexport() {}
  };

  return {
    interface: {
      Interface: FakeInterface,
      ACCESS_READ: Symbol('read'),
      ACCESS_READWRITE: Symbol('readwrite')
    },
    sessionBus: () => bus,
    Variant: class FakeVariant {
      constructor(signature, value) {
        this.signature = signature;
        this.value = value;
      }
    }
  };
}

test('MPRIS interfaces are exported before the service name is announced', async () => {
  const events = [];
  const service = createSystemMediaService({
    emitCommand: () => {},
    loadDbus: () => fakeDbus(events),
    platform: 'linux'
  });

  await service.publish({
    track: { id: 'track-1', title: 'Track' },
    isPlaying: true
  });

  assert.deepEqual(events, [
    'export:/org/mpris/MediaPlayer2:org.mpris.MediaPlayer2',
    'export:/org/mpris/MediaPlayer2:org.mpris.MediaPlayer2.Player',
    'request-name:org.mpris.MediaPlayer2.Orchard'
  ]);
});

test('initial playback state is available before the MPRIS service name is announced', async () => {
  const events = [];
  let player;
  const dbus = fakeDbus(events, {
    onRequestName: () => {
      assert.equal(player.PlaybackStatus, 'Playing');
      assert.equal(player.Metadata['xesam:title'].value, 'Track');
      assert.equal(player.CanPlay, true);
    }
  });
  const originalExport = dbus.sessionBus().export;
  dbus.sessionBus().export = (path, playerInterface) => {
    if (playerInterface.name === 'org.mpris.MediaPlayer2.Player') player = playerInterface;
    originalExport(path, playerInterface);
  };
  const service = createSystemMediaService({
    emitCommand: () => {},
    loadDbus: () => dbus,
    platform: 'linux'
  });

  await service.publish({
    track: { id: 'track-1', title: 'Track' },
    isPlaying: true
  });

  assert.ok(player);
});

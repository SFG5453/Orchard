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

function fakeDbus(events) {
  const bus = {
    disconnect() {},
    export(path, playerInterface) {
      events.push(`export:${path}:${playerInterface.name}`);
    },
    requestName(name) {
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

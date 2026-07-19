import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';

import { IPC_CHANNEL_VALUES } from '../shared/ipcChannels.js';

const preloadSource = await readFile(
  new URL('../electron/preload/index.cjs', import.meta.url),
  'utf8'
);

function channelLiterals(source) {
  return [...source.matchAll(/['"]([a-z][a-z0-9-]*:[a-z0-9-]+)['"]/g)]
    .map((match) => match[1]);
}

test('IPC channel values are unique', () => {
  assert.equal(new Set(IPC_CHANNEL_VALUES).size, IPC_CHANNEL_VALUES.length);
});

test('sandboxed preload mirrors the shared IPC contract', () => {
  const preloadChannels = [...new Set(channelLiterals(preloadSource))].sort();
  assert.deepEqual(preloadChannels, [...IPC_CHANNEL_VALUES].sort());
});

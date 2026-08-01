import assert from 'node:assert/strict';
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import test from 'node:test';

import {
  createSessionStateStore,
  readSessionState,
  writeSessionState
} from '../electron/main/sessionState.js';

function scratchFile() {
  const directory = mkdtempSync(path.join(tmpdir(), 'orchard-session-'));
  return {
    filePath: path.join(directory, 'nested', 'session-state.json'),
    cleanup: () => rmSync(directory, { force: true, recursive: true })
  };
}

test('a missing or corrupt session file reads as empty rather than throwing', () => {
  const { filePath, cleanup } = scratchFile();
  try {
    assert.deepEqual(readSessionState(filePath), {});

    writeSessionState(filePath, { 'orchard:last-page': { view: 'home' } });
    writeFileSync(filePath, '{"truncated":');
    assert.deepEqual(readSessionState(filePath), {});

    // A JSON array is valid JSON but not a state object.
    writeFileSync(filePath, '[1,2,3]');
    assert.deepEqual(readSessionState(filePath), {});
  } finally {
    cleanup();
  }
});

test('the state file is created with its directory and read back whole', () => {
  const { filePath, cleanup } = scratchFile();
  try {
    const state = { 'orchard:playback-state': { queue: [{ id: 'a' }] } };
    writeSessionState(filePath, state);
    assert.deepEqual(readSessionState(filePath), state);
    // Written atomically, so no temporary file is left behind.
    assert.equal(readFileSync(filePath, 'utf8').endsWith('\n'), true);
  } finally {
    cleanup();
  }
});

test('writes are coalesced but flush puts them on disk immediately', async () => {
  const { filePath, cleanup } = scratchFile();
  try {
    const store = createSessionStateStore({ filePath, writeDelayMs: 5000 });
    store.set('orchard:last-page', { view: 'search' });
    store.set('orchard:playback-state', { queue: [] });

    // Nothing has hit disk yet: the coalescing timer is still pending.
    assert.deepEqual(readSessionState(filePath), {});

    assert.equal(store.flush(), true);
    assert.deepEqual(readSessionState(filePath), {
      'orchard:last-page': { view: 'search' },
      'orchard:playback-state': { queue: [] }
    });

    // Nothing left pending, so a second flush is a no-op.
    assert.equal(store.flush(), false);
  } finally {
    cleanup();
  }
});

test('the coalescing timer writes on its own without a flush', async () => {
  const { filePath, cleanup } = scratchFile();
  try {
    const store = createSessionStateStore({ filePath, writeDelayMs: 1 });
    store.set('orchard:last-page', { view: 'pins' });
    await new Promise((resolve) => setTimeout(resolve, 30));

    assert.deepEqual(readSessionState(filePath), { 'orchard:last-page': { view: 'pins' } });
    store.flush();
  } finally {
    cleanup();
  }
});

test('a store opens on the state the last session left', () => {
  const { filePath, cleanup } = scratchFile();
  try {
    writeSessionState(filePath, { 'orchard:last-page': { view: 'replay' } });
    const store = createSessionStateStore({ filePath });

    assert.deepEqual(store.get('orchard:last-page'), { view: 'replay' });
    assert.equal(store.get('orchard:playback-state'), null);
    assert.deepEqual(store.get(''), { 'orchard:last-page': { view: 'replay' } });
  } finally {
    cleanup();
  }
});

test('rewriting an unchanged value does not dirty the store', () => {
  const { filePath, cleanup } = scratchFile();
  try {
    const store = createSessionStateStore({ filePath, writeDelayMs: 5000 });
    store.set('orchard:last-page', { view: 'home' });
    store.flush();

    // The renderer re-persists on every mutation, including ones that leave the
    // stored shape identical; those must not cost a file write.
    assert.equal(store.set('orchard:last-page', { view: 'home' }), true);
    assert.equal(store.flush(), false);
  } finally {
    cleanup();
  }
});

test('an oversized or unusable key is refused instead of stored', () => {
  const { filePath, cleanup } = scratchFile();
  try {
    const store = createSessionStateStore({ filePath, writeDelayMs: 5000 });

    assert.equal(store.set('', { view: 'home' }), false);
    assert.equal(store.set(null, { view: 'home' }), false);
    assert.equal(store.set('orchard:huge', { blob: 'x'.repeat(600 * 1024) }), false);
    assert.equal(store.flush(), false);
    assert.equal(store.get('orchard:huge'), null);
  } finally {
    cleanup();
  }
});

test('stored values are copied, so a later renderer mutation cannot alter them', () => {
  const { filePath, cleanup } = scratchFile();
  try {
    const store = createSessionStateStore({ filePath, writeDelayMs: 5000 });
    const entry = { view: 'browse', browseDetail: { browseId: 'MPLA1' } };
    store.set('orchard:last-page', entry);
    entry.view = 'settings';
    entry.browseDetail.browseId = 'changed';

    store.flush();
    assert.deepEqual(readSessionState(filePath), {
      'orchard:last-page': { view: 'browse', browseDetail: { browseId: 'MPLA1' } }
    });
  } finally {
    cleanup();
  }
});

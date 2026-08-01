import assert from 'node:assert/strict';
import test from 'node:test';

import {
  clearSessionValue,
  readSessionValue,
  writeSessionValue
} from '../src/app/core/sessionStore.js';

function fakeLocalStorage(seed = {}) {
  const entries = new Map(Object.entries(seed));
  return {
    getItem: (key) => (entries.has(key) ? entries.get(key) : null),
    setItem: (key, value) => entries.set(key, String(value)),
    removeItem: (key) => entries.delete(key),
    entries
  };
}

function withWindow(window, run) {
  const original = globalThis.window;
  globalThis.window = window;
  try {
    return run();
  } finally {
    globalThis.window = original;
  }
}

test('the durable bridge is preferred over localStorage when present', () => {
  const calls = [];
  const localStorage = fakeLocalStorage({ 'orchard:last-page': '{"view":"stale"}' });
  const window = {
    localStorage,
    orchardSessionState: {
      get: (key) => { calls.push(['get', key]); return '{"view":"fresh"}'; },
      set: (key, value) => calls.push(['set', key, value])
    }
  };

  withWindow(window, () => {
    assert.deepEqual(readSessionValue('orchard:last-page'), { view: 'fresh' });
    writeSessionValue('orchard:last-page', { view: 'pins' });
  });

  assert.deepEqual(calls, [
    ['get', 'orchard:last-page'],
    ['set', 'orchard:last-page', '{"view":"pins"}']
  ]);
  // The stale localStorage copy is left untouched rather than being written to
  // by both paths.
  assert.equal(localStorage.entries.get('orchard:last-page'), '{"view":"stale"}');
});

test('a proxied value crosses the bridge serialized, not as a live object', () => {
  // The IPC channel behind the bridge structured-clones its arguments, which
  // throws on the reactive proxies app state is made of. Serializing first is
  // what keeps a click on an artist from failing with "could not be cloned".
  const sent = [];
  const entry = new Proxy({ view: 'browse', searchResult: { sections: [] } }, {});
  const window = {
    localStorage: fakeLocalStorage(),
    orchardSessionState: {
      get: () => null,
      set: (key, value) => {
        assert.equal(typeof value, 'string');
        sent.push(value);
      }
    }
  };

  withWindow(window, () => writeSessionValue('orchard:last-page', entry));

  assert.deepEqual(JSON.parse(sent[0]), { view: 'browse', searchResult: { sections: [] } });
});

test('a value that will not serialize is dropped rather than thrown', () => {
  const cyclic = { view: 'home' };
  cyclic.self = cyclic;
  const window = {
    localStorage: fakeLocalStorage(),
    orchardSessionState: { get: () => null, set: () => { throw new Error('unreachable'); } }
  };

  withWindow(window, () => writeSessionValue('orchard:last-page', cyclic));
});

test('a corrupt durable value falls back instead of throwing', () => {
  const window = {
    localStorage: fakeLocalStorage(),
    orchardSessionState: { get: () => '{oops', set: () => {} }
  };

  withWindow(window, () => {
    assert.deepEqual(readSessionValue('orchard:last-page', { view: 'home' }), { view: 'home' });
  });
});

test('an existing install migrates: the old localStorage value is read once', () => {
  const localStorage = fakeLocalStorage({ 'orchard:last-page': '{"view":"replay"}' });
  const window = {
    localStorage,
    // A fresh durable store has nothing for this key yet.
    orchardSessionState: { get: () => null, set: () => {} }
  };

  withWindow(window, () => {
    assert.deepEqual(readSessionValue('orchard:last-page'), { view: 'replay' });
  });
});

test('without the bridge it falls back to localStorage in both directions', () => {
  const localStorage = fakeLocalStorage();
  const window = { localStorage };

  withWindow(window, () => {
    assert.equal(readSessionValue('orchard:playback-state', 'fallback'), 'fallback');
    writeSessionValue('orchard:playback-state', { queue: [{ id: 'a' }] });
    assert.deepEqual(readSessionValue('orchard:playback-state'), { queue: [{ id: 'a' }] });

    clearSessionValue('orchard:playback-state');
    assert.equal(readSessionValue('orchard:playback-state'), null);
  });
});

test('a corrupt localStorage entry falls back instead of throwing', () => {
  const window = { localStorage: fakeLocalStorage({ 'orchard:last-page': '{oops' }) };

  withWindow(window, () => {
    assert.equal(readSessionValue('orchard:last-page'), null);
    assert.equal(readSessionValue('orchard:last-page', { view: 'home' }).view, 'home');
  });
});

test('clearing removes both copies so a disabled setting really forgets', () => {
  const cleared = [];
  const localStorage = fakeLocalStorage({ 'orchard:playback-state': '{"queue":[]}' });
  const window = {
    localStorage,
    orchardSessionState: { get: () => null, set: (key, value) => cleared.push([key, value]) }
  };

  withWindow(window, () => clearSessionValue('orchard:playback-state'));

  assert.deepEqual(cleared, [['orchard:playback-state', null]]);
  assert.equal(localStorage.entries.has('orchard:playback-state'), false);
});

test('storage failures never propagate to the caller', () => {
  const window = {
    localStorage: {
      getItem: () => { throw new Error('denied'); },
      setItem: () => { throw new Error('denied'); },
      removeItem: () => { throw new Error('denied'); }
    }
  };

  withWindow(window, () => {
    assert.equal(readSessionValue('orchard:last-page', null), null);
    writeSessionValue('orchard:last-page', { view: 'home' });
    clearSessionValue('orchard:last-page');
  });
});

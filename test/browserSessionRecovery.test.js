import assert from 'node:assert/strict';
import test from 'node:test';
import { createBrowserSessionRecovery } from '../electron/auth/browserSessionRecovery.js';

function fixture({ load = async () => {}, url = 'https://music.youtube.com/', timeoutMs, accountIndex = 0 } = {}) {
  const windows = [];
  const captures = [];
  let time = 0;
  class BrowserWindow {
    constructor(options) {
      this.options = options;
      this.destroyed = false;
      this.webContents = {
        getURL: () => url,
        setWindowOpenHandler: (handler) => { this.openHandler = handler; }
      };
      windows.push(this);
    }
    loadURL(url) { this.url = url; return load(); }
    isDestroyed() { return this.destroyed; }
    destroy() { this.destroyed = true; }
  }
  const recover = createBrowserSessionRecovery({
    BrowserWindow, partition: 'persist:test',
    capturePageAuth: async (contents) => captures.push(contents),
    getAccountIndex: () => accountIndex, now: () => time, timeoutMs
  });
  return { recover, windows, captures, advance: () => { time += 60_000; } };
}

test('silently renews the persistent session and captures page auth before cleanup', async () => {
  const { recover, windows, captures } = fixture();
  await recover();
  assert.equal(windows.length, 1);
  assert.equal(windows[0].options.show, false);
  assert.equal(windows[0].options.webPreferences.partition, 'persist:test');
  assert.equal(windows[0].url, 'https://music.youtube.com/');
  assert.deepEqual(windows[0].openHandler(), { action: 'deny' });
  assert.deepEqual(captures, [windows[0].webContents]);
  assert.equal(windows[0].destroyed, true);
});

test('coalesces concurrent attempts and throttles subsequent retries', async () => {
  let finish;
  const f = fixture({ load: () => new Promise((resolve) => { finish = resolve; }) });
  const first = f.recover();
  assert.equal(f.recover(), first);
  finish();
  await first;
  await f.recover();
  assert.equal(f.windows.length, 1);
  f.advance();
  const second = f.recover();
  finish();
  await second;
  assert.equal(f.windows.length, 2);
});

test('does not capture Google login pages when the session cannot be renewed', async () => {
  const f = fixture({ url: 'https://accounts.google.com/ServiceLogin' });
  await f.recover();
  assert.equal(f.captures.length, 0);
  assert.equal(f.windows[0].destroyed, true);
});

test('cleans up failed and timed-out windows and permits later retries', async () => {
  for (const load of [async () => { throw new Error('offline'); }, () => new Promise(() => {})]) {
    const f = fixture({ load, timeoutMs: 5 });
    await assert.rejects(f.recover(), /offline|timed out/);
    assert.equal(f.windows[0].destroyed, true);
    f.advance();
    await assert.rejects(f.recover());
    assert.equal(f.windows.length, 2);
    assert.equal(f.windows[1].destroyed, true);
  }
});

test('renews the selected Google account rather than the default account', async () => {
  const f = fixture({ accountIndex: 2 });
  await f.recover();
  assert.equal(f.windows[0].url, 'https://music.youtube.com/?authuser=2');
});

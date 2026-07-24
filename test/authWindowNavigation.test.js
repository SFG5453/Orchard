import assert from 'node:assert/strict';
import test from 'node:test';
import {
  isAuthSwitchDestinationUrl,
  isSupersededAuthNavigation,
  isTrustedAuthUrl,
  loadAuthWindowUrl,
  observeAuthSwitchIdentity
} from '../electron/auth/authWindowNavigation.js';

function fakeWindow({ currentUrl = '', error } = {}) {
  return {
    isDestroyed: () => false,
    loadURL: async () => {
      if (error) throw error;
    },
    webContents: {
      getURL: () => currentUrl
    }
  };
}

test('auth navigation accepts only HTTPS YouTube and Google pages', () => {
  assert.equal(isTrustedAuthUrl('https://www.youtube.com/channel_switcher'), true);
  assert.equal(isTrustedAuthUrl('https://accounts.google.com/AccountChooser'), true);
  assert.equal(isTrustedAuthUrl('http://www.youtube.com/channel_switcher'), false);
  assert.equal(isTrustedAuthUrl('https://youtube.com.example.test/channel_switcher'), false);
});

test('regular YouTube is recognized as the post-switch destination', () => {
  assert.equal(isAuthSwitchDestinationUrl('https://www.youtube.com/'), true);
  assert.equal(isAuthSwitchDestinationUrl('https://www.youtube.com/feed/you'), true);
  assert.equal(isAuthSwitchDestinationUrl('https://www.youtube.com/channel_switcher'), false);
  assert.equal(isAuthSwitchDestinationUrl('https://accounts.google.com/AccountChooser'), false);
});

test('superseded YouTube auth navigation is recognized as benign', () => {
  const error = { code: 'ERR_ABORTED', errno: -3 };
  assert.equal(
    isSupersededAuthNavigation(
      error,
      'https://www.youtube.com/channel_switcher',
      'https://www.youtube.com/channel_switcher?themeRefresh=1'
    ),
    true
  );
});

test('the account chooser load establishes the switch baseline without closing', () => {
  const observed = observeAuthSwitchIdentity({}, 'chooser-page-identity');

  assert.deepEqual(observed, {
    baseline: 'chooser-page-identity',
    ready: true,
    completed: false
  });
});

test('only an identity change after the chooser load completes the switch', () => {
  const baseline = observeAuthSwitchIdentity({}, 'chooser-page-identity');
  const unchanged = observeAuthSwitchIdentity(baseline, 'chooser-page-identity');
  const switched = observeAuthSwitchIdentity(unchanged, 'brand-account-identity');

  assert.equal(unchanged.completed, false);
  assert.equal(switched.completed, true);
  assert.equal(switched.baseline, 'chooser-page-identity');
});

test('loadAuthWindowUrl ignores a trusted ERR_ABORTED redirect', async () => {
  const loaded = await loadAuthWindowUrl(fakeWindow({
    currentUrl: 'https://www.youtube.com/channel_switcher?themeRefresh=1',
    error: { code: 'ERR_ABORTED', errno: -3 }
  }), 'https://www.youtube.com/channel_switcher');

  assert.equal(loaded, false);
});

test('loadAuthWindowUrl preserves real navigation failures', async () => {
  await assert.rejects(
    loadAuthWindowUrl(fakeWindow({
      currentUrl: 'https://www.youtube.com/channel_switcher',
      error: { code: 'ERR_CONNECTION_REFUSED', errno: -102 }
    }), 'https://www.youtube.com/channel_switcher'),
    { code: 'ERR_CONNECTION_REFUSED' }
  );
});

test('loadAuthWindowUrl does not hide an abort into an untrusted page', async () => {
  await assert.rejects(
    loadAuthWindowUrl(fakeWindow({
      currentUrl: 'https://example.test/phishing',
      error: { code: 'ERR_ABORTED', errno: -3 }
    }), 'https://www.youtube.com/channel_switcher'),
    { code: 'ERR_ABORTED' }
  );
});

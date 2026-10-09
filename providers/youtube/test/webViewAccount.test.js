import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';

const qml = readFileSync(new URL('../../../app/qml/views/login/AuthWebView.qml', import.meta.url), 'utf8');
const requestBody = qml.split('function requestAccount(token, authorization) {')[1]
  .split('\n    Timer {')[0].replace(/\}\s*$/, '');

function request(origin = 'https://music.youtube.com', delegatedId = '') {
  let script;
  const host = { token: '42', authorization: 'SAPISIDHASH test', JSON,
    webView: { runJavaScript(value) { script = value; } },
    accountPollTimer: { start() {} } };
  vm.runInNewContext(requestBody, host);
  let xhr;
  class XHR {
    constructor() { xhr = this; this.headers = {}; }
    open(method, url) { this.method = method; this.url = url; }
    setRequestHeader(key, value) { this.headers[key] = value; }
    send(body) { this.body = JSON.parse(body); }
  }
  const page = { location: { origin }, window: { ytcfg: { get(key) {
    return { DELEGATED_SESSION_ID: delegatedId, SESSION_INDEX: 2, INNERTUBE_CONTEXT: { client: { clientName: 'WEB_REMIX', clientVersion: 'test' } } }[key];
  } } }, XMLHttpRequest: XHR };
  vm.runInNewContext(script, page);
  return { page, xhr };
}

test('WebView account request stays same-origin and uses browser credentials', () => {
  const { page, xhr } = request();
  assert.equal(xhr.url, '/youtubei/v1/account/accounts_list?prettyPrint=false&alt=json');
  assert.equal(xhr.method, 'POST');
  assert.equal(xhr.withCredentials, true);
  assert.equal(xhr.headers.Cookie, undefined);
  assert.equal(xhr.headers.Authorization, 'SAPISIDHASH test');
  assert.equal(xhr.headers['X-Goog-AuthUser'], '2');
  assert.equal(xhr.body.context.client.clientVersion, 'test');
  xhr.status = 200;
  xhr.responseText = '{"accountItemRenderer":{}}';
  xhr.onload();
  assert.equal(page.window.__orchardAccount.status, 200);
  assert.equal(page.window.__orchardAccount.token, '42');
});

test('WebView uses the channel-switcher account query for personal and delegated accounts', () => {
  for (const delegatedId of ['', 'test-page']) {
    const { xhr } = request('https://music.youtube.com', delegatedId);
    assert.equal(xhr.body.requestType, 'ACCOUNTS_LIST_REQUEST_TYPE_CHANNEL_SWITCHER');
    assert.equal(xhr.body.callCircumstance, 'SWITCHING_USERS_FULL');
    assert.equal(xhr.body.context.user?.onBehalfOfUser, delegatedId || undefined);
    assert.equal(xhr.headers['X-Goog-PageId'], delegatedId || undefined);
  }
});

test('WebView request ignores other origins and stale responses', () => {
  assert.equal(request('https://accounts.google.com').xhr, undefined);
  const { page, xhr } = request();
  page.window.__orchardAccount = { token: '43', pending: true };
  xhr.onload();
  assert.equal(page.window.__orchardAccount.token, '43');
  assert.equal(page.window.__orchardAccount.pending, true);
});

test('WebView request reports rejection and timeout instead of a profile', () => {
  for (const event of ['onload', 'ontimeout', 'onerror']) {
    const { page, xhr } = request();
    xhr.status = event === 'onload' ? 401 : 0;
    xhr.responseText = 'denied';
    xhr[event]();
    assert.equal(page.window.__orchardAccount.status, xhr.status);
    assert.equal(page.window.__orchardAccount.data, null);
    assert.equal(page.window.__orchardAccount.pending, undefined);
  }
});

const profileScript = vm.runInNewContext(
  qml.match(/readonly property string profileScript:\s*(`[^`]*`)/)[1]
);
const extractScript = vm.runInNewContext(
  qml.match(/readonly property string extractScript:\s*(`[^`]*`)/)[1]
);

test('initial page capture includes the signed-in navigation avatar', () => {
  const page = {
    window: { ytcfg: { get() { return ''; } } },
    location: { origin: 'https://music.youtube.com' },
    document: {
      cookie: 'SAPISID=signing',
      scripts: [],
      querySelector() { return { currentSrc: 'https://example.com/avatar' }; }
    }
  };
  const captured = JSON.parse(vm.runInNewContext(extractScript, page));
  assert.equal(captured.avatar, 'https://example.com/avatar');
  assert.equal(captured.cookie, 'SAPISID=signing');
});

test('page capture waits for a real avatar URL', () => {
  const page = {
    window: { ytcfg: { get() { return ''; } } },
    location: { origin: 'https://music.youtube.com' },
    document: {
      cookie: 'SAPISID=signing', scripts: [],
      querySelector() { return { src: 'data:image/gif;base64,R0lGODlhAQABAAAAACw=' }; }
    }
  };
  assert.equal(JSON.parse(vm.runInNewContext(extractScript, page)).avatar, '');
});

test('v2-style profile probe waits for the menu and clicks the avatar only once', () => {
  let clicks = 0;
  let menuReady = false;
  const avatar = { src: 'https://example.com/avatar' };
  const button = {
    closest() { return this; },
    click() { clicks++; },
    querySelector() { return avatar; },
    matches() { return false; }
  };
  const menu = {
    innerText: 'Account\nAda Lovelace\n@ada\nManage your Google account\nSign out',
    querySelector(selector) {
      return selector === 'img[src]' ? avatar : { href: 'https://www.youtube.com/@ada' };
    }
  };
  const page = {
    window: {}, location: { origin: 'https://music.youtube.com' },
    document: { querySelector(selector) {
      return selector.startsWith('#avatar-btn') ? button : menuReady ? menu : null;
    } }
  };
  assert.deepEqual(JSON.parse(vm.runInNewContext(profileScript, page)), {});
  assert.equal(clicks, 1);
  assert.equal(JSON.parse(vm.runInNewContext(profileScript, page)).avatarUrl, avatar.src);
  assert.equal(clicks, 1);
  menuReady = true;
  const profile = JSON.parse(vm.runInNewContext(profileScript, page));
  assert.equal(profile.name, 'Ada Lovelace');
  assert.equal(profile.handle, '@ada');
  assert.equal(profile.avatarUrl, avatar.src);
  assert.equal(clicks, 1);
});

test('profile probe reads the selected channel on the YouTube switch destination', () => {
  const avatar = { src: 'https://example.com/brand-avatar' };
  const menu = {
    innerText: 'Account\nBrand Channel\n@brand\nSwitch account',
    querySelector(selector) {
      return selector === 'img[src]' ? avatar : { href: 'https://www.youtube.com/@brand' };
    }
  };
  const page = {
    window: { __orchardProfileClicked: true },
    location: { origin: 'https://www.youtube.com' },
    document: { querySelector(selector) {
      return selector.startsWith('#avatar-btn') ? null : menu;
    } }
  };
  const profile = JSON.parse(vm.runInNewContext(profileScript, page));
  assert.equal(profile.name, 'Brand Channel');
  assert.equal(profile.handle, '@brand');
  assert.equal(profile.avatarUrl, avatar.src);
});

test('profile probe ignores pages outside YouTube', () => {
  const page = {
    location: { origin: 'https://accounts.google.com' },
    document: { querySelector() { assert.fail('must not inspect a different origin'); } }
  };
  assert.deepEqual(JSON.parse(vm.runInNewContext(profileScript, page)), {});
});

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
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
 * A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Orchard. If not, see <https://www.gnu.org/licenses/>.
 */

import assert from 'node:assert/strict';
import { beforeEach, afterEach, it, mock } from 'node:test';
import { readFileSync, readdirSync } from 'node:fs';
import { DatabaseSync } from 'node:sqlite';
import worker from '../src/index.js';
import { signJwt } from '../src/jwt.js';
import { TRANSLATION_MODEL, purgeTranslations } from '../src/translation.js';
import { createD1 } from './d1.js';

let env, token, calls;
const now = () => Math.floor(Date.now() / 1000);
async function session(user = 'user-1', device = 'device-1') {
  env.DB.raw.prepare('INSERT OR IGNORE INTO users (id, created_at, updated_at) VALUES (?, 0, 0)').run(user);
  env.DB.raw.prepare(`INSERT OR IGNORE INTO devices
    (id, user_id, name, platform, refresh_hash, rotated_at, created_at, last_seen_at)
    VALUES (?, ?, 'Desk', 'linux', ?, 0, 0, ?)`).run(device, user, `hash-${device}`, now());
  return signJwt({ iss: env.PUBLIC_URL, aud: 'orchard', sub: user, did: device, iat: now(), exp: now() + 900 }, env.SIGNING_KEY);
}
function translate(song = 'song-1', { auth = token, ip = '203.0.113.7', lines = ['Hola'], extra = {} } = {}) {
  return worker.fetch(new Request('https://account.test/lyrics/translate', {
    method: 'POST', headers: { authorization: `Bearer ${auth}`, ...(ip ? { 'cf-connecting-ip': ip } : {}) },
    body: JSON.stringify({ song_id: song, lines, ...extra }),
  }), env);
}
function usage(scope = 'account', subject = 'user-1') {
  return env.DB.raw.prepare('SELECT used FROM translation_daily_usage WHERE scope = ? AND subject = ? AND day = ?')
    .get(scope, subject, Math.floor(now() / 86400))?.used ?? 0;
}
function quota({ auth = token, ip = '203.0.113.7' } = {}) {
  return worker.fetch(new Request('https://account.test/lyrics/quota', {
    headers: { authorization: `Bearer ${auth}`, ...(ip ? { 'cf-connecting-ip': ip } : {}) },
  }), env);
}
beforeEach(async () => {
  const { privateKey } = await crypto.subtle.generateKey({ name: 'Ed25519' }, true, ['sign', 'verify']);
  calls = 0;
  env = { DB: createD1(), PUBLIC_URL: 'https://account.test',
    SIGNING_KEY: JSON.stringify({ ...(await crypto.subtle.exportKey('jwk', privateKey)), kid: 'test' }),
    AI: { async run(model, payload) {
      calls++;
      assert.equal(model, TRANSLATION_MODEL);
      assert.equal(payload.stream, false);
      const input = JSON.parse(payload.messages[1].content);
      return { choices: [{ message: { content: JSON.stringify(input.map(line => `English: ${line}`)) } }] };
    } },
  };
  token = await session();
});
afterEach(() => mock.restoreAll());

it('reports authenticated per-account quota without charging or running inference', async () => {
  assert.equal((await quota({ auth: '' })).status, 401);
  assert.equal((await quota({ ip: '' })).status, 403);
  const response = await quota();
  assert.equal(response.headers.get('cache-control'), 'no-store');
  assert.deepEqual((await response.json()).quota, {
    daily_limit: 10, account_remaining: 10, ip_remaining: 10, remaining: 10,
    resets_at: (Math.floor(now() / 86400) + 1) * 86400,
  });
  assert.equal(usage(), 0);
  assert.equal(calls, 0);
  env.DB.raw.prepare('DELETE FROM devices WHERE id = ?').run('device-1');
  assert.equal((await quota()).status, 401);
});
it('reports current quota on new translations, cache hits, failures, and exhausted admission', async () => {
  assert.equal((await (await translate()).json()).quota.remaining, 9);
  assert.equal((await (await translate()).json()).quota.remaining, 9);
  env.AI.run = async () => { throw new Error('3040: busy'); };
  const failed = await translate('failed');
  assert.equal(failed.status, 503);
  assert.equal((await failed.json()).quota.remaining, 8);
  env.DB.raw.prepare('UPDATE users SET translation_daily_limit = 2 WHERE id = ?').run('user-1');
  const rejected = await translate('extra');
  assert.equal(rejected.status, 429);
  const data = await rejected.json();
  assert.equal(data.quota.daily_limit, 2);
  assert.equal(data.quota.remaining, 0);
  assert.equal((await (await translate()).json()).quota.remaining, 0);
});
it('reports shared IP exhaustion and elevated allowances for the requesting account', async () => {
  for (let i = 0; i < 10; i++) await translate(`song-${i}`);
  const other = await session('user-2', 'device-2');
  assert.deepEqual((await (await quota({ auth: other })).json()).quota, {
    daily_limit: 10, account_remaining: 10, ip_remaining: 0, remaining: 0,
    resets_at: (Math.floor(now() / 86400) + 1) * 86400,
  });
  env.DB.raw.prepare('UPDATE users SET translation_daily_limit = 50 WHERE id = ?').run('user-1');
  const raised = (await (await quota()).json()).quota;
  assert.equal(raised.daily_limit, 50);
  assert.equal(raised.remaining, 40);
  assert.equal(raised.ip_remaining, null);
  assert.equal((await (await translate('extra')).json()).quota.remaining, 39);
  assert.equal((await (await quota({ ip: '203.0.113.99' })).json()).quota.remaining, 39);
  env.DB.raw.prepare('UPDATE users SET translation_daily_limit = 10 WHERE id = ?').run('user-1');
  assert.equal((await (await quota()).json()).quota.remaining, 0);
});
it('reports a fresh allowance and reset timestamp at UTC midnight', async () => {
  const midnight = (Math.floor(Date.now() / 86400000) + 1) * 86400000;
  mock.method(Date, 'now', () => midnight - 1000);
  token = await session();
  await translate();
  assert.equal((await (await quota()).json()).quota.remaining, 9);
  mock.method(Date, 'now', () => midnight);
  const fresh = (await (await quota()).json()).quota;
  assert.equal(fresh.remaining, 10);
  assert.equal(fresh.resets_at, midnight / 1000 + 86400);
  assert.equal((await (await translate()).json()).quota.remaining, 10);
});

it('requires a live session and a Cloudflare IP even for cache reads', async () => {
  assert.equal((await translate('one', { auth: '' })).status, 401);
  assert.equal((await translate('one', { ip: '' })).status, 403);
  assert.equal((await translate()).status, 200);
  env.DB.raw.prepare('DELETE FROM devices WHERE id = ?').run('device-1');
  assert.equal((await translate()).status, 401);
  assert.equal(calls, 1);
});
it('stores song IDs in D1 and shares cache across accounts without charging replays', async () => {
  const first = await (await translate()).json();
  assert.deepEqual(first.lines, ['English: Hola']);
  assert.equal(first.cached, false);
  assert.equal(env.DB.raw.prepare('SELECT song_id FROM lyric_translations').get().song_id, 'song-1');
  const other = await session('user-2', 'device-2');
  const replay = await (await translate('song-1', { auth: other })).json();
  assert.equal(replay.cached, true);
  assert.equal(calls, 1);
  assert.equal(usage(), 1);
  assert.equal(usage('account', 'user-2'), 0);
  assert.equal((await translate('song-2')).status, 200);
  assert.equal(calls, 2);
});
it('charges lyric changes under the same ID and ignores client quota/model fields', async () => {
  for (let i = 0; i < 10; i++) {
    assert.equal((await translate('same-id', { lines: [`Hola ${i}`],
      extra: { account_id: 'fake', ip: `fake-${i}`, day: i, cached: true, model: 'fake', translation_daily_limit: 50 } })).status, 200);
  }
  assert.equal((await translate('same-id', { lines: ['changed'] })).status, 429);
  assert.equal(calls, 10);
});
it('enforces an account limit across devices and IPs, while cached songs remain available', async () => {
  const secondDevice = await session('user-1', 'device-2');
  for (let i = 0; i < 10; i++) {
    assert.equal((await translate(`song-${i}`, { auth: i % 2 ? token : secondDevice, ip: `203.0.113.${i}` })).status, 200);
  }
  const rejected = await translate('eleventh', { ip: '203.0.113.99' });
  assert.equal(rejected.status, 429);
  assert.ok(Number(rejected.headers.get('retry-after')) > 0);
  assert.equal((await translate('song-0')).status, 200);
  assert.equal(calls, 10);
});
it('enforces the IP limit across accounts', async () => {
  for (let i = 0; i < 10; i++) {
    const auth = await session(`user-${i}`, `device-${i}`);
    assert.equal((await translate(`song-${i}`, { auth })).status, 200);
  }
  const other = await session('user-new', 'device-new');
  assert.equal((await translate('extra', { auth: other })).status, 429);
  assert.equal(usage('account', 'user-new'), 0);
});
it('admits at most ten simultaneous misses', async () => {
  const results = await Promise.all(Array.from({ length: 30 }, (_, i) => translate(`song-${i}`)));
  assert.equal(results.filter(response => response.status === 200).length, 10);
  assert.equal(results.filter(response => response.status === 429).length, 20);
  assert.equal(calls, 10);
});
it('lets a donor start 50 songs across devices and IPs without consuming the shared IP quota', async () => {
  env.DB.raw.prepare('UPDATE users SET translation_daily_limit = 50 WHERE id = ?').run('user-1');
  const secondDevice = await session('user-1', 'device-2');
  for (let i = 0; i < 50; i++) {
    assert.equal((await translate(`donor-${i}`, {
      auth: i % 2 ? token : secondDevice, ip: i % 2 ? '203.0.113.7' : '203.0.113.8',
    })).status, 200);
  }
  const rejected = await translate('donor-extra', { ip: '203.0.113.99' });
  assert.equal(rejected.status, 429);
  assert.ok(Number(rejected.headers.get('retry-after')) > 0);
  assert.equal(usage(), 50);
  assert.equal((await translate('donor-0')).status, 200);
  const other = await session('user-2', 'device-3');
  for (let i = 0; i < 10; i++) {
    assert.equal((await translate(`ordinary-${i}`, { auth: other })).status, 200);
  }
  assert.equal((await translate('ordinary-extra', { auth: other })).status, 429);
  assert.equal(usage('account', 'user-2'), 10);
});
it('honors an increased allowance immediately even on an exhausted IP', async () => {
  for (let i = 0; i < 10; i++) assert.equal((await translate(`song-${i}`)).status, 200);
  assert.equal((await translate('extra')).status, 429);
  env.DB.raw.prepare('UPDATE users SET translation_daily_limit = 50 WHERE id = ?').run('user-1');
  for (let i = 10; i < 50; i++) assert.equal((await translate(`song-${i}`)).status, 200);
  assert.equal((await translate('extra')).status, 429);
  assert.equal(usage(), 50);
  const other = await session('user-2', 'device-2');
  assert.equal((await translate('other-extra', { auth: other })).status, 429);
  assert.equal(usage('account', 'user-2'), 0);
});
it('admits at most 50 concurrent donor misses and renews the allowance at UTC midnight', async () => {
  const midnight = (Math.floor(Date.now() / 86400000) + 1) * 86400000;
  mock.method(Date, 'now', () => midnight - 1000);
  token = await session();
  env.DB.raw.prepare('UPDATE users SET translation_daily_limit = 50 WHERE id = ?').run('user-1');
  const results = await Promise.all(Array.from({ length: 60 }, (_, i) => translate(`donor-${i}`)));
  assert.equal(results.filter(response => response.status === 200).length, 50);
  assert.equal(results.filter(response => response.status === 429).length, 10);
  assert.equal(usage(), 50);
  mock.method(Date, 'now', () => midnight);
  assert.equal((await translate('next-day')).status, 200);
  assert.equal(usage(), 1);
  assert.equal(env.DB.raw.prepare('SELECT translation_daily_limit FROM users WHERE id = ?').get('user-1').translation_daily_limit, 50);
});
it('restores the default limit immediately without erasing usage', async () => {
  env.DB.raw.prepare('UPDATE users SET translation_daily_limit = 50 WHERE id = ?').run('user-1');
  for (let i = 0; i < 12; i++) assert.equal((await translate(`donor-${i}`)).status, 200);
  env.DB.raw.prepare('UPDATE users SET translation_daily_limit = 10 WHERE id = ?').run('user-1');
  assert.equal((await translate('extra', { ip: '203.0.113.99' })).status, 429);
  assert.equal(usage(), 12);
  assert.equal((await translate('donor-0')).status, 200);
});
it('preserves account, cache, and usage data when migrating existing quotas', () => {
  const db = new DatabaseSync(':memory:');
  try {
    db.exec('PRAGMA foreign_keys = ON');
    const dir = new URL('../migrations/', import.meta.url);
    for (const file of readdirSync(dir).sort().filter(name => name < '0007')) {
      db.exec(readFileSync(new URL(file, dir), 'utf8'));
    }
    db.exec("INSERT INTO users (id, email, created_at, updated_at) VALUES ('donor', 'donor@example.test', 0, 0)");
    for (let i = 0; i < 10; i++) {
      db.prepare(`INSERT INTO lyric_translations
        (song_id, revision, lyrics_hash, lease_token, expires_at, account_id, ip_hash, day)
        VALUES (?, 'v1', 'hash', 'lease', 9999999999, 'donor', 'shared-ip', 1)`).run(`song-${i}`);
    }
    db.exec(readFileSync(new URL('0007_translation_allowances.sql', dir), 'utf8'));
    assert.equal(db.prepare('SELECT translation_daily_limit FROM users').get().translation_daily_limit, 10);
    assert.equal(db.prepare('SELECT count(*) AS count FROM lyric_translations').get().count, 10);
    assert.deepEqual(db.prepare('SELECT used FROM translation_daily_usage ORDER BY scope').all().map(row => row.used), [10, 10]);
    db.exec("UPDATE users SET translation_daily_limit = 50 WHERE id = 'donor'");
    db.exec(`INSERT INTO lyric_translations
      (song_id, revision, lyrics_hash, lease_token, expires_at, account_id, ip_hash, day)
      VALUES ('extra', 'v1', 'hash', 'lease', 9999999999, 'donor', 'shared-ip', 1)`);
    assert.deepEqual(db.prepare('SELECT used FROM translation_daily_usage ORDER BY scope').all().map(row => row.used), [11, 10]);
  } finally { db.close(); }
});
it('coalesces simultaneous requests for a song', async () => {
  let finish;
  const blocked = new Promise(resolve => { finish = resolve; });
  const original = env.AI.run;
  env.AI.run = async (...args) => { await blocked; return original(...args); };
  const pending = translate();
  // Yield until the reservation has been written.
  while (!env.DB.raw.prepare('SELECT 1 FROM lyric_translations').get()) await new Promise(resolve => setImmediate(resolve));
  assert.equal((await translate()).status, 409);
  finish();
  assert.equal((await pending).status, 200);
  assert.equal(calls, 1);
  assert.equal(usage(), 1);
});
it('resets exactly at UTC midnight without relying on scheduled cleanup', async () => {
  const midnight = (Math.floor(Date.now() / 86400000) + 1) * 86400000;
  mock.method(Date, 'now', () => midnight - 1000);
  token = await session();
  for (let i = 0; i < 10; i++) assert.equal((await translate(`song-${i}`)).status, 200);
  assert.equal((await translate('extra')).status, 429);
  mock.method(Date, 'now', () => midnight);
  assert.equal((await translate('extra')).status, 200);
  assert.equal(usage(), 1);
});
it('validates bounded input before admission and does not cache malformed model output', async () => {
  for (const lines of [[], [''], [42], ['x'.repeat(1025)], Array(201).fill('Hola')]) {
    assert.equal((await translate('bad', { lines })).status, 400);
  }
  assert.equal((await translate('huge', { lines: ['x'.repeat(50000)] })).status, 413);
  assert.equal(usage(), 0);
  env.AI.run = async () => ({ choices: [{ message: { content: '[]' } }] });
  for (let i = 0; i < 10; i++) assert.equal((await translate()).status, 502);
  assert.equal((await translate()).status, 429);
  assert.equal(env.DB.raw.prepare('SELECT count(*) AS count FROM lyric_translations').get().count, 0);
});
it('translates a full song in bounded batches and cleans up expired cache/usage', async () => {
  const lines = Array.from({ length: 49 }, (_, i) => `Hola ${i}`);
  const result = await (await translate('long-song', { lines })).json();
  assert.deepEqual(result.lines, lines.map(line => `English: ${line}`));
  assert.equal(calls, 3);
  assert.equal(usage(), 1);
  await purgeTranslations(env, now() + 91 * 86400);
  assert.equal(env.DB.raw.prepare('SELECT count(*) AS count FROM lyric_translations').get().count, 0);
  assert.equal(env.DB.raw.prepare('SELECT count(*) AS count FROM translation_daily_usage').get().count, 0);
});

it('reports provider capacity and timeout errors separately without leaking upstream text', async () => {
  env.AI.run = async () => { throw new Error('3040: private upstream details'); };
  const busy = await translate('busy');
  assert.equal(busy.status, 503);
  assert.equal((await busy.json()).error, 'translation_busy');
  assert.equal(busy.headers.get('retry-after'), '5');
  env.AI.run = async () => { throw new Error('3007: private upstream details'); };
  const timedOut = await translate('timeout');
  assert.equal(timedOut.status, 504);
  assert.equal((await timedOut.json()).error, 'translation_timeout');
  assert.equal(usage(), 2);
  assert.equal(env.DB.raw.prepare('SELECT count(*) AS count FROM lyric_translations').get().count, 0);
});

it('keys shared cached translations by target and validates targets before charging', async () => {
  for (const [target, name] of [['spa', 'Spanish'], ['hin', 'Hindi'], ['kor', 'Korean'], ['jpn', 'Japanese'], ['fra', 'French']]) {
    env.AI.run = async (_, payload) => {
      calls++;
      assert.ok(payload.messages[0].content.includes(`natural ${name}`));
      assert.ok(payload.messages[0].content.includes(`Keep ${name} lines unchanged`));
      return { response: JSON.stringify([`${name} output`]) };
    };
    const options = { lines: ['I love you'], extra: { target_language: target } };
    const first = await (await translate('same', options)).json();
    assert.equal(first.target_language, target);
    assert.deepEqual(first.lines, [`${name} output`]);
    assert.equal(first.cached, false);
    assert.equal((await (await translate('same', options)).json()).cached, true);
  }
  assert.equal(calls, 5);
  assert.equal(usage(), 5);
  for (const target of ['invalid', 'English', '__proto__', null, {}, 42]) {
    assert.equal((await translate('bad', { extra: { target_language: target } })).status, 400);
  }
  assert.equal(usage(), 5);
});
it('skips songs already in the target language without inference, cache writes, or admission', async () => {
  const examples = [
    ['eng', ["I can't stop thinking about you", 'Starlight']],
    ['spa', ['Te quiero más que a mi vida', 'Despacito']],
    ['hin', ['मैं तुमसे प्यार करता हूँ']], ['kor', ['오늘 밤 너와 춤추고 싶어']],
    ['jpn', ['今夜は君と踊りたい', '永遠']], ['fra', ['Je ne veux pas vivre sans toi']],
  ];
  env.AI = null;
  env.DB.raw.prepare('UPDATE users SET translation_daily_limit = 0').run();
  for (const [target, lines] of examples) {
    const response = await translate('same-language', { lines, extra: { target_language: target } });
    assert.equal(response.status, 200);
    assert.deepEqual((await response.json()).lines, lines);
  }
  assert.equal(calls, 0);
  assert.equal(usage(), 0);
  assert.equal(env.DB.raw.prepare('SELECT count(*) AS count FROM lyric_translations').get().count, 0);
});
it('translates only foreign lines and restores their positions in a mixed-language song', async () => {
  env.AI.run = async (_, payload) => {
    calls++;
    assert.deepEqual(JSON.parse(payload.messages[1].content), ['I love you', '사랑해']);
    return { response: '["Te amo", "Te quiero"]' };
  };
  const lines = ['Te quiero más que a mi vida', 'I love you', '사랑해', '🎵'];
  const result = await (await translate('mixed', { lines, extra: { target_language: 'spa' } })).json();
  assert.deepEqual(result.lines, [lines[0], 'Te amo', 'Te quiero', '🎵']);
  assert.equal(usage(), 1);
});

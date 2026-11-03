import assert from 'node:assert/strict';
import test from 'node:test';
import { createAppleMusic } from '../src/index.js';

function music(status = 200) {
  const calls = [];
  const client = createAppleMusic({
    session: () => ({ developerToken: 'developer', userToken: 'user', storefront: 'gb', origin: 'https://example.test' }),
    fetchImpl: async (url, init) => {
      calls.push({ url, init, body: JSON.parse(init.body) });
      return { ok: status < 400, status };
    }
  });
  return { client, calls };
}

test('a finished play posts MusicKit\'s play-end item with the user token', async () => {
  const { client, calls } = music();
  const result = await client.playEnded({
    id: '1440857781', durationMs: 200000, startPositionMs: 0, endPositionMs: 199000,
    reason: 'natural', container: { kind: 'album', id: '1440857780' }
  });
  assert.equal(result.recorded, true);
  const [{ url, init, body }] = calls;
  assert.equal(url, 'https://universal-activity-service.itunes.apple.com/play');
  assert.equal(init.headers['X-Apple-Music-User-Token'], 'user');
  assert.equal(init.headers.Authorization, 'Bearer developer');
  assert.equal(init.headers.Origin, 'https://example.test');
  assert.equal(body.client_id, 'JSCLIENT');
  const [item] = body.data;
  assert.equal(item['event-type'], 0);
  assert.equal(item.type, 1);
  assert.equal(item['milliseconds-since-play'], 199000);
  assert.equal(item['end-reason-type'], 7);
  assert.deepEqual(item.ids, { 'subscription-adam-id': '1440857781' });
  assert.deepEqual(item['container-ids'], { 'album-adam-id': '1440857780' });
  assert.equal(item['end-position-in-milliseconds'], 199000);
  assert.equal(item['store-front'], 'gb');
});

test('a play start omits end fields', async () => {
  const { client, calls } = music();
  await client.playStarted({ id: '5', durationMs: 1000 });
  const [item] = calls[0].body.data;
  assert.equal(item['event-type'], 1);
  assert.equal('end-reason-type' in item, false);
});

test('non-catalog ids are skipped and HTTP errors surface', async () => {
  const { client, calls } = music();
  assert.deepEqual(await client.playEnded({ id: 'i.abc' }), { recorded: false });
  assert.equal(calls.length, 0);
  await assert.rejects(music(401).client.playEnded({ id: '5' }), { status: 401 });
});

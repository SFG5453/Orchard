import assert from 'node:assert/strict';
import test from 'node:test';
import { createAppleMusic } from '../src/index.js';

const COMPOSITE = 'https://is1-ssl.mzstatic.com/image/thumb/Features221/v4/a/mza_1.jpeg/{w}x{h}AM.RSMA04.jpg?imgLeft=x&imgRight=y';
const PLAIN = 'https://is1-ssl.mzstatic.com/image/thumb/Features/v4/b/plain.png/{w}x{h}bb.{f}';

const station = (id, url) => ({ type: 'stations', id, attributes: { name: `S${id}`, isLive: false, artwork: { url, width: 2400, height: 2400 } } });

function music(routes) {
  const calls = [];
  const client = createAppleMusic({
    session: () => ({ developerToken: 'developer', userToken: 'user', storefront: 'us' }),
    fetchImpl: async (url) => {
      calls.push(url);
      const route = routes.find(([pattern]) => pattern.test(url));
      assert.ok(route, `Unexpected URL ${url}`);
      const [, status, body] = route;
      return { ok: status < 400, status, json: async () => body };
    }
  });
  return { client, calls };
}

const shelf = (contents) => ({ data: [{ id: 'r', type: 'personal-recommendation', attributes: { title: { stringForDisplay: 'Stations' } }, relationships: { contents: { data: contents } } }] });
const artists = { data: [{ id: '1419227', type: 'artists', attributes: { artwork: { url: 'https://is1-ssl.mzstatic.com/image/thumb/Artist/{w}x{h}bb.{f}', width: 1000, height: 1000 } } }] };

test('composite artist station covers become the artist photo', async () => {
  const { client, calls } = music([
    [/me\/recommendations/, 200, shelf([station('ra.1419227', COMPOSITE), station('ra.u-me', PLAIN)])],
    [/catalog\/us\/artists\?ids=1419227$/, 200, artists]
  ]);
  const home = await client.home();
  const [composite, plain] = home.sections[0].items;
  assert.match(composite.thumbnail, /Artist\/600x600bb\.jpg$/);
  assert.match(plain.thumbnail, /plain\.png\/600x600bb\.jpg$/);
  assert.equal(calls.filter((url) => url.includes('/artists')).length, 1);
});

test('a failed artist lookup keeps the station cover', async () => {
  const { client } = music([
    [/me\/recommendations/, 200, shelf([station('ra.1419227', COMPOSITE)])],
    [/catalog\/us\/artists/, 500, {}]
  ]);
  const home = await client.home();
  assert.match(home.sections[0].items[0].thumbnail, /imgRight=y/);
});

import assert from 'node:assert/strict';
import test from 'node:test';
import { createAppleMusic } from '../src/index.js';

const album = (id) => ({
  id, type: 'albums', attributes: { name: `Album ${id}`, artistName: 'Artist' },
  relationships: { artists: { data: [{ id: '42', type: 'artists', attributes: { name: 'Artist' } }] } }
});
const recommendation = (id, data, next = '') => ({
  id, type: 'personal-recommendation',
  attributes: { title: { stringForDisplay: `For you ${id}` } },
  relationships: { contents: { data, next } }
});

function music(routes, userToken = 'user') {
  const calls = [];
  const client = createAppleMusic({
    session: () => ({ developerToken: 'developer', userToken, storefront: 'gb' }),
    fetchImpl: async (url, init) => {
      calls.push({ url, headers: init.headers });
      const route = routes.find(([pattern]) => pattern.test(url));
      assert.ok(route, `Unexpected URL ${url}`);
      const [, status, body] = route;
      return { ok: status < 400, status, json: async () => body };
    }
  });
  return { client, calls };
}

test('Home normalizes MusadoraKit recommendation titles and contents with the user token', async () => {
  const { client, calls } = music([[/me\/recommendations/, 200, { data: [recommendation('one', [album('1'), { type: 'stations', id: 'radio' }])] }]]);
  const home = await client.home();
  assert.equal(home.source, 'apple_music');
  assert.equal(home.sections[0].title, 'For you one');
  assert.equal(home.sections[0].items.length, 1);
  assert.equal(home.sections[0].items[0].browseId, 'am.albums.1');
  assert.deepEqual(home.sections[0].items[0].artistBrowseIds, ['am.artists.42']);
  assert.deepEqual(home.sections[0].items[0].artists, ['Artist']);
  assert.equal(calls[0].headers['Music-User-Token'], 'user');
  assert.match(calls[0].url, /include=contents/);
  assert.equal(new URL(calls[0].url).searchParams.get('include[albums]'), 'artists');
});

test('Home follows recommendation pages and nested groups without repeating a shelf', async () => {
  const child = recommendation('child', [album('2')]);
  const root = recommendation('root', [child]);
  const { client } = music([
    [/offset=1/, 200, { data: [child] }],
    [/me\/recommendations/, 200, { data: [root], next: '/v1/me/recommendations?offset=1' }],
  ]);
  const home = await client.home();
  assert.deepEqual(home.sections.map((s) => s.title), ['For you child']);
});

test('empty supported recommendations fall back to the listener storefront charts', async () => {
  const { client, calls } = music([
    [/me\/recommendations/, 200, { data: [recommendation('radio', [{ id: 's', type: 'stations' }])] }],
    [/catalog\/gb\/charts/, 200, { results: { albums: [{ name: 'Top Albums', chart: 'most-played', data: [album('3')] }] } }],
  ]);
  const home = await client.home();
  assert.equal(home.sections[0].title, 'Top Albums');
  assert.equal(home.sections[0].items[0].source, 'apple_music');
  assert.deepEqual(home.sections[0].items[0].artistBrowseIds, ['am.artists.42']);
  assert.equal(new URL(calls[1].url).searchParams.get('include[albums]'), 'artists');
  assert.equal(calls.length, 2);
});

test('empty charts and recommendations report failure so the host can fall back', async () => {
  const { client } = music([[/recommendations/, 200, { data: [] }], [/charts/, 200, { results: {} }]]);
  await assert.rejects(client.home(), (error) => error.code === 'empty_home');
});

test('Home rejects disconnected and expired sessions without silently using charts', async () => {
  const guest = music([], '');
  await assert.rejects(guest.client.home(), (error) => error.code === 'not_signed_in');
  assert.equal(guest.calls.length, 0);
  const expired = music([[/recommendations/, 403, {}]]);
  await assert.rejects(expired.client.home(), (error) => error.code === 'user_token');
});

test('See all recommendation contents follow pagination and preserve the dotted id', async () => {
  const { client, calls } = music([
    [/offset=1/, 200, { data: [album('2')] }],
    [/recommendations\/one.two\/contents/, 200, { data: [album('1')], next: '/v1/me/recommendations/one.two/contents?offset=1' }],
  ]);
  const cards = await client.homeSection('am.recommendations.one.two');
  assert.deepEqual(cards.map((card) => card.browseId), ['am.albums.1', 'am.albums.2']);
  assert.deepEqual(cards.map((card) => card.artistBrowseIds), [['am.artists.42'], ['am.artists.42']]);
  for (const { url } of calls) assert.equal(new URL(url).searchParams.get('include[albums]'), 'artists');
  assert.deepEqual(await client.homeSection('UCyoutube'), []);
});

test('the personal mix shelf has a stable key for daily discover', async () => {
  const mix = (id, name) => ({ id, type: 'playlists', attributes: { name, playlistType: 'personal-mix', playParams: { id, kind: 'playlist' } } });
  const { client } = music([[/me\/recommendations/, 200, { data: [
    recommendation('mixes', [mix('pl.pm-1', 'New Music'), mix('pl.pm-2', 'Chill')]),
    recommendation('albums', [album('4')])
  ] }]]);
  const home = await client.home();
  assert.deepEqual(home.sections.map((section) => section.key), ['apple-personal-mixes', 'apple-recommendation-albums']);
  assert.deepEqual(home.sections[0].items.map((item) => item.browseId), ['am.playlists.pl.pm-1', 'am.playlists.pl.pm-2']);
});

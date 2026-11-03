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
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Orchard. If not, see <https://www.gnu.org/licenses/>.
 */

import assert from 'node:assert/strict';
import test from 'node:test';

import { AppleMusicError, apiUrl, artworkUrl, browseId, createAppleMusic, heroVideo, parseBrowseId, releaseInfo } from '../src/index.js';

const art = { url: 'https://is1.mzstatic.com/image/{w}x{h}{c}.{f}', width: 3000, height: 3000 };

function song(id, name, extra = {}) {
  return {
    id,
    type: 'songs',
    attributes: {
      name,
      artistName: 'SZA',
      albumName: 'SOS',
      durationInMillis: 180500,
      contentRating: 'explicit',
      artwork: art,
      playParams: { id, kind: 'song' },
      previews: [{ url: `https://audio.example/${id}.m4a` }],
      ...extra
    },
    relationships: {
      artists: { data: [{ id: '605800394', type: 'artists', attributes: { name: 'SZA' } }] },
      albums: { data: [{ id: '1657869377', type: 'albums' }] }
    }
  };
}

const albumResource = (id, name, releaseDate) => ({
  id, type: 'albums', attributes: { name, artistName: 'SZA', releaseDate, artwork: art, contentRating: 'explicit' },
  relationships: { artists: { data: [{ id: '605800394', type: 'artists', attributes: { name: 'SZA' } }] } }
});

const artistResponse = {
  data: [{
    id: '605800394',
    type: 'artists',
    attributes: {
      name: 'SZA',
      genreNames: ['R&B/Soul'],
      artwork: art,
      editorialNotes: { short: 'Short note.' },
      artistBio: 'Born in St. Louis.',
      url: 'https://music.apple.com/us/artist/sza/605800394',
      editorialArtwork: {
        musicContentColorLogoTrimmed: { url: 'https://is1.mzstatic.com/logo/{w}x{h}bb.jpg', width: 2586, height: 960 },
        centeredFullscreenBackground: { url: 'https://is1.mzstatic.com/wide/{w}x{h}{c}.{f}', width: 4320, height: 1080 },
        vipSquare: { url: 'https://is1.mzstatic.com/vip/{w}x{h}{c}.{f}', width: 3000, height: 3000 }
      },
      editorialVideo: {
        motionArtistSquare1x1: { video: 'https://video.example/square.m3u8' },
        motionArtistWide16x9: { video: 'https://video.example/wide.m3u8' },
        motionArtistFullscreen16x9: {
          video: 'https://video.example/full.m3u8',
          previewFrame: { url: 'https://is1.mzstatic.com/frame/{w}x{h}bb.jpg', width: 3840, height: 2160, bgColor: 'a6abb4' }
        }
      }
    },
    views: {
      'top-songs': { attributes: { title: 'Top Songs' }, data: [1, 2, 3, 4, 5, 6].map((n) => song(`s${n}`, `Song ${n}`)) },
      'full-albums': {
        attributes: { title: 'Albums' },
        next: '/v1/catalog/us/artists/605800394/view/full-albums?offset=1',
        data: [albumResource('1657869377', 'SOS', '2022-12-09')]
      },
      singles: { attributes: { title: 'Singles & EPs' }, data: [albumResource('1', 'Saturn - Single', '2024-02-22')] },
      'similar-artists': { attributes: { title: 'Similar Artists' }, data: [{ id: '2', type: 'artists', attributes: { name: 'Summer Walker', artwork: art } }] },
      'live-albums': { attributes: { title: 'Live Albums' }, data: [] }
    }
  }]
};

function fakeFetch(routes, calls = []) {
  return async (url, init) => {
    calls.push({ url, headers: init?.headers || {} });
    const route = routes.find(([pattern]) => pattern.test(url));
    if (!route) return { ok: false, status: 404, json: async () => ({}) };
    const [, status, body] = route;
    return { ok: status < 400, status, json: async () => body };
  };
}

function appleMusic(routes, session = {}, calls = []) {
  return createAppleMusic({
    fetchImpl: fakeFetch(routes, calls),
    session: () => ({ developerToken: 'dev', storefront: 'us', ...session })
  });
}

test('browse ids keep dots inside Apple ids', () => {
  assert.equal(browseId('playlists', 'pl.u-abc.def'), 'am.playlists.pl.u-abc.def');
  assert.deepEqual(parseBrowseId('am.playlists.pl.u-abc.def'), { type: 'playlists', id: 'pl.u-abc.def' });
  assert.equal(parseBrowseId('UCabc'), null);
  assert.equal(parseBrowseId('am.artists.'), null);
});

test('artwork templates and release suffixes', () => {
  assert.equal(artworkUrl(art, 600), 'https://is1.mzstatic.com/image/600x600bb.jpg');
  assert.equal(artworkUrl({ url: 'https://a/{w}x{h}.jpg', width: 300, height: 300 }, 600), 'https://a/300x300.jpg');
  assert.deepEqual(releaseInfo({ name: 'Saturn - Single' }), { title: 'Saturn', releaseType: 'Single' });
  assert.deepEqual(releaseInfo({ name: 'Ctrl - EP' }), { title: 'Ctrl', releaseType: 'EP' });
  assert.deepEqual(releaseInfo({ name: 'SOS' }), { title: 'SOS', releaseType: 'Album' });
});

test('api urls append to existing queries', () => {
  assert.equal(apiUrl('/v1/x?offset=5', { limit: 25 }), 'https://api.music.apple.com/v1/x?offset=5&limit=25');
  assert.equal(apiUrl('/v1/x', { term: 'a b', empty: '' }), 'https://api.music.apple.com/v1/x?term=a%20b');
});

test('hero video prefers the fullscreen cut music.apple.com plays', () => {
  assert.equal(heroVideo(artistResponse.data[0].attributes.editorialVideo), 'https://video.example/full.m3u8');
  assert.equal(heroVideo({ motionArtistSquare1x1: { video: 'https://video.example/square.m3u8' }, motionArtistWide16x9: { video: 'https://video.example/wide.m3u8' } }), 'https://video.example/wide.m3u8');
  assert.equal(heroVideo(null), '');
});

test('artist page maps views onto the shared artist shape', async () => {
  const calls = [];
  const page = await appleMusic([[/\/artists\/605800394\?/, 200, artistResponse]], { origin: 'https://appassets.androidplatform.net' }, calls)
    .artistPage('am.artists.605800394');
  assert.equal(page.kind, 'artist');
  assert.equal(page.title, 'SZA');
  assert.equal(page.description, 'Born in St. Louis.');
  assert.equal(page.heroVideo, 'https://video.example/full.m3u8');
  assert.equal(page.heroPoster, 'https://is1.mzstatic.com/frame/1920x1080bb.jpg');
  assert.equal(page.heroTint, 'a6abb4');
  assert.equal(page.logo, 'https://is1.mzstatic.com/logo/1200x445bb.png');
  assert.equal(page.heroArtwork, 'https://is1.mzstatic.com/wide/2400x600bb.jpg');
  assert.equal(page.thumbnail, 'https://is1.mzstatic.com/vip/1200x1200bb.jpg');
  assert.equal(page.tracks.length, 6);
  assert.deepEqual(page.tracks[0], {
    id: 'am.songs.s1', type: 'song', appleId: 's1', source: 'apple_music', title: 'Song 1', artist: 'SZA', artists: ['SZA'],
    artistBrowseIds: ['am.artists.605800394'], album: 'SOS', albumId: 'am.albums.1657869377',
    thumbnail: 'https://is1.mzstatic.com/image/600x600bb.jpg', durationSeconds: 180.5, duration: '3:01', year: '', explicit: true,
    isrc: '', previewUrl: 'https://audio.example/s1.m4a', unplayable: false, index: '1'
  });
  assert.deepEqual(page.sections.map((section) => section.key), ['full-albums', 'singles', 'similar-artists']);
  assert.equal(page.latestRelease, null);
  assert.deepEqual(page.sections[0].browsePayload, { browseId: 'am.artists.605800394', params: 'view:full-albums' });
  assert.equal(page.sections[1].browsePayload, null);
  assert.equal(page.sections[1].items[0].title, 'Saturn');
  assert.equal(page.sections[1].items[0].subtitle, 'Single • SZA • 2024');
  assert.deepEqual(page.sections[0].items[0].artistBrowseIds, ['am.artists.605800394']);
  assert.deepEqual(page.sections[0].items[0].artists, ['SZA']);
  assert.equal(new URL(calls[0].url).searchParams.get('include[albums]'), 'artists');
  assert.equal(calls[0].headers.Origin, 'https://appassets.androidplatform.net');
  assert.equal(calls[0].headers['Music-User-Token'], undefined);
  assert.match(calls[0].url, /^https:\/\/api\.music\.apple\.com\/.*extend=artistBio%2CeditorialVideo/);
});

test('a rejected extend retries without it', async () => {
  const calls = [];
  const plain = { data: [{ ...artistResponse.data[0], attributes: { name: 'SZA' } }] };
  const page = await appleMusic([[/extend=/, 400, {}], [/\/artists\/605800394\?/, 200, plain]], {}, calls)
    .artistPage('am.artists.605800394');
  assert.equal(page.title, 'SZA');
  assert.equal(page.heroVideo, '');
  assert.equal(page.logo, '');
  assert.equal(calls.length, 2);
});

test('artist view pages follow next links', async () => {
  const calls = [];
  const routes = [
    [/offset=25/, 200, { data: [albumResource('b', 'Ctrl', '2017-06-09')] }],
    [/view\/full-albums/, 200, { data: [albumResource('a', 'SOS', '2022-12-09')], next: '/v1/catalog/us/artists/605800394/view/full-albums?offset=25' }]
  ];
  const items = await appleMusic(routes, {}, calls).artistView('am.artists.605800394', 'view:full-albums');
  assert.deepEqual(items.map((item) => item.title), ['SOS', 'Ctrl']);
  assert.deepEqual(items.map((item) => item.artistBrowseIds), [['am.artists.605800394'], ['am.artists.605800394']]);
  for (const { url } of calls) assert.equal(new URL(url).searchParams.get('include[albums]'), 'artists');
  assert.deepEqual(await appleMusic(routes).artistView('am.artists.605800394', 'view:../unknown'), []);
});

test('artist matching requires an exact name', async () => {
  const results = { results: { artists: { data: [
    { id: '9', type: 'artists', attributes: { name: 'SZA Tribute Band' } },
    { id: '605800394', type: 'artists', attributes: { name: 'SZA' } }
  ] } } };
  const music = appleMusic([[/\/search\?/, 200, results]]);
  assert.deepEqual(await music.matchArtist('sza'), { browseId: 'am.artists.605800394', title: 'SZA' });
  assert.equal(await music.matchArtist('Beyonce'), null);
});

test('album pages include every track page', async () => {
  const albumBody = { data: [{
    ...albumResource('1657869377', 'SOS', '2022-12-09'),
    attributes: { ...albumResource('1657869377', 'SOS', '2022-12-09').attributes, trackCount: 2, editorialNotes: { standard: 'Notes.' } },
    relationships: {
      artists: { data: [{ id: '605800394', type: 'artists' }] },
      tracks: { data: [song('t1', 'Kill Bill')], next: '/v1/catalog/us/albums/1657869377/tracks?offset=1' }
    }
  }] };
  const routes = [[/tracks\?offset=1/, 200, { data: [song('t2', 'Snooze', { playParams: undefined })] }], [/\/albums\/1657869377\?/, 200, albumBody]];
  const page = await appleMusic(routes).albumPage('am.albums.1657869377');
  assert.equal(page.releaseType, 'Album');
  assert.equal(page.description, 'Notes.');
  assert.equal(page.artistBrowseId, 'am.artists.605800394');
  assert.deepEqual(page.artistBrowseIds, ['am.artists.605800394']);
  assert.deepEqual(page.artists, ['SZA']);
  assert.deepEqual(page.tracks.map((t) => [t.title, t.unplayable]), [['Kill Bill', false], ['Snooze', true]]);
});

test('album search preserves individual artist targets and leaves missing identities empty', async () => {
  const collaboration = albumResource('duet', 'Duet', '2026-01-01');
  collaboration.attributes.artistName = 'SZA & Kendrick Lamar';
  collaboration.relationships.artists.data.push(
    { type: 'artists', attributes: { name: 'Missing identity' } },
    { id: '368183298', type: 'artists', attributes: { name: 'Kendrick Lamar' } }
  );
  const unknown = albumResource('unknown', 'Unknown', '2026-01-01');
  delete unknown.relationships;
  const calls = [];
  const results = { results: { albums: { data: [collaboration, unknown] } } };
  const page = await appleMusic([[/\/search\?/, 200, results]], {}, calls).search('Duet', 'albums');
  const [duet, missing] = page.sections[0].items;
  assert.deepEqual(duet.artistBrowseIds, ['am.artists.605800394', 'am.artists.368183298']);
  assert.deepEqual(duet.artists, ['SZA', 'Kendrick Lamar']);
  assert.equal(duet.artistBrowseId, 'am.artists.605800394');
  assert.deepEqual(missing.artistBrowseIds, []);
  assert.equal(missing.artistBrowseId, '');
  assert.equal(new URL(calls[0].url).searchParams.get('include[albums]'), 'artists');
});

test('personal requests need a user token and errors carry codes', async () => {
  const guest = appleMusic([[/me\/storefront/, 200, { data: [{ id: 'gb' }] }]]);
  assert.equal(await guest.storefront('ca'), 'ca');
  const signedIn = appleMusic([[/me\/storefront/, 200, { data: [{ id: 'gb' }] }]], { userToken: 'mut' });
  assert.equal(await signedIn.storefront('ca'), 'gb');
  const expired = appleMusic([[/artists/, 403, {}]], { userToken: 'mut' });
  await assert.rejects(expired.artistPage('am.artists.1'), (error) => error instanceof AppleMusicError && error.code === 'user_token');
  const disconnected = createAppleMusic({ fetchImpl: fakeFetch([]), session: () => null });
  await assert.rejects(disconnected.search('sza'), (error) => error.code === 'not_connected');
});


test('artist pages order catalog shelves independently of API object keys and retain unfamiliar views', async () => {
  const resource = structuredClone(artistResponse.data[0]);
  resource.views = {
    'similar-artists': resource.views['similar-artists'],
    'live-albums': { attributes: { title: 'Live Albums' }, data: [albumResource('live', 'Live', '2025-01-01')] },
    'full-albums': resource.views['full-albums'],
    'special-releases': {
      attributes: { title: 'Special Releases' },
      data: [albumResource('special', 'Special', '2026-01-01')], next: '/more'
    },
    'latest-release': { attributes: { title: 'Latest Release' }, data: [albumResource('latest', 'Latest', '2026-10-01')] },
    'top-songs': resource.views['top-songs'],
    singles: resource.views.singles,
  };
  const entries = Object.entries(resource.views);
  for (const views of [Object.fromEntries(entries), Object.fromEntries(entries.toReversed())]) {
    const music = appleMusic([
      [/\/artists\/605800394\?/, 200, { data: [{ ...resource, views }] }],
      [/view\/special-releases/, 200, { data: [albumResource('special', 'Special', '2026-01-01')] }],
    ]);
    const page = await music.artistPage('am.artists.605800394');
    assert.deepEqual(page.sections.map(({ title }) => title), [
      'Latest Release', 'Albums', 'Singles & EPs', 'Live Albums', 'Special Releases', 'Similar Artists'
    ]);
    assert.equal(page.latestRelease.browseId, 'am.albums.latest');
    assert.equal(page.sections[3].items[0].browseId, 'am.albums.live');
    assert.deepEqual(page.sections[4].browsePayload, { browseId: 'am.artists.605800394', params: 'view:special-releases' });
    assert.equal((await music.artistView('am.artists.605800394', 'view:special-releases'))[0].browseId, 'am.albums.special');
  }
});

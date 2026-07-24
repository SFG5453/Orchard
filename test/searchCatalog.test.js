import assert from 'node:assert/strict';
import test from 'node:test';
import { createSearchUtils } from '../electron/catalog/searchUtils.js';

function searchUtils() {
  return createSearchUtils({
    asText: (value) => String(value || ''),
    bestThumbnail: () => '',
    hasExplicitBadge: () => false,
    normalizedLooseText: (value) => String(value || '').trim().toLowerCase(),
    shelfItems: (shelf) => shelf?.items || [],
    textParts: (value) => String(value || '').split(' • ')
  });
}

function rawSearch(key, items) {
  return { [key]: { items } };
}

function artistItem(browseId, title, subtitle = '') {
  return {
    browseId,
    browsePayload: {
      browseId,
      browseEndpointContextSupportedConfigs: {
        browseEndpointContextMusicConfig: {
          pageType: 'MUSIC_PAGE_TYPE_ARTIST'
        }
      }
    },
    type: 'artist',
    title,
    subtitle
  };
}

test('all search supplements the broad response with focused song and artist candidates', async () => {
  const calls = [];
  const collection = {
    async search(query, options) {
      calls.push([query, options]);
      if (options?.type === 'song') {
        return rawSearch('songs', [
          { id: 'usher-burn', type: 'song', title: 'Burn', artist: 'Usher' }
        ]);
      }
      if (options?.type === 'artist') {
        return rawSearch('artists', [
          artistItem('burna-boy', 'Burna Boy', 'Artist • 40M subscribers')
        ]);
      }
      return {
        songs: { items: [{ id: 'other-burn', type: 'song', title: 'Burn', artist: 'Other' }] },
        artists: {
          items: [artistItem('burn-band', 'Burn Band', 'Artist • 10K subscribers')]
        }
      };
    }
  };

  const result = await searchUtils().searchCatalog(collection, 'burn', 'all');

  assert.deepEqual(calls, [
    ['burn', undefined],
    ['burn', { type: 'song' }],
    ['burn', { type: 'artist' }]
  ]);
  assert.deepEqual(
    result.sections.find((section) => section.key === 'songs').items.map((item) => item.id),
    ['usher-burn', 'other-burn']
  );
  assert.deepEqual(
    result.sections.find((section) => section.key === 'artists').items.map((item) => item.browseId),
    ['burna-boy', 'burn-band']
  );
});

test('all search still returns broad results when a focused search fails', async () => {
  const collection = {
    async search(_query, options) {
      if (options?.type === 'song') throw new Error('song search unavailable');
      if (options?.type === 'artist') return rawSearch('artists', []);
      return rawSearch('songs', [{ id: 'broad-song', type: 'song', title: 'Burn' }]);
    }
  };

  const result = await searchUtils().searchCatalog(collection, 'burn', 'all');

  assert.equal(result.sections[0].items[0].id, 'broad-song');
});

test('filtered search makes only the requested category call', async () => {
  const calls = [];
  const collection = {
    async search(query, options) {
      calls.push([query, options]);
      return rawSearch('artists', [artistItem('usher', 'USHER')]);
    }
  };

  await searchUtils().searchCatalog(collection, 'usher', 'artists');

  assert.deepEqual(calls, [['usher', { type: 'artist' }]]);
});

test('an album title matching the query does not discard songs by other artists', () => {
  const result = searchUtils().normalizeSearch(rawSearch('songs', [
    { id: 'usher-burn', type: 'song', title: 'Burn', artists: ['Usher'], album: 'Confessions' },
    { id: 'obscure-burn', type: 'song', title: 'Burn', artists: ['Obscure'], album: 'Burn' }
  ]), 'burn');

  assert.deepEqual(
    result.sections.find((section) => section.key === 'songs').items.map((item) => item.id),
    ['usher-burn', 'obscure-burn']
  );
});

test('song search hydrates exact-title candidates with track popularity', async () => {
  const collection = {
    async search() {
      return rawSearch('songs', [
        { id: 'less-popular', type: 'song', title: 'Burn', artists: ['First'] },
        { id: 'more-popular', type: 'song', title: 'Burn', artists: ['Second'] },
        { id: 'partial-title', type: 'song', title: 'Burning', artists: ['Third'] }
      ]);
    }
  };
  const views = new Map([
    ['less-popular', 10],
    ['more-popular', 100]
  ]);

  const result = await searchUtils().searchCatalog(
    collection,
    'burn',
    'songs',
    async (videoId) => views.get(videoId) || 0
  );
  const songs = result.sections.find((section) => section.key === 'songs').items;

  assert.deepEqual(songs.map((item) => item.searchPopularity || 0), [10, 100, 0]);
});

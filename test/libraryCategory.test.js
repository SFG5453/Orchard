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
import { createBrowseNormalizers } from '../electron/catalog/browseNormalizers.js';
import { createMainFeeds } from '../electron/catalog/mainFeeds.js';

function mainFeeds(overrides = {}) {
  return createMainFeeds({
    asText: (value) => typeof value === 'string'
      ? value
      : value?.simpleText || value?.runs?.map((run) => run.text).join('') || '',
    browseContinuationTokenFromData: (data) => data.continuation || null,
    bridgeError: (error) => error.message,
    fetchRawBrowserMusicBrowse: async () => ({}),
    hasBrowserLoginCookie: () => false,
    normalizeBrowseSection: (section) => section,
    normalizeRawBrowseItem: (item) => item,
    normalizeTrack: (item) => item,
    normalizeTvLibrary: () => ({ sections: [] }),
    rawBrowseItemsFromData: (data) => data.items || [],
    rawSectionList: () => [],
    ...overrides
  });
}

test('loads every parsed YouTube Music home continuation', async () => {
  const third = { sections: [{ title: 'New releases' }], filters: [], has_continuation: false };
  const second = {
    sections: [{ title: 'Albums for you' }],
    filters: [],
    has_continuation: true,
    getContinuation: async () => third
  };
  const first = {
    sections: [{ title: 'Quick picks' }],
    filters: ['Energize'],
    has_continuation: true,
    getContinuation: async () => second
  };

  const feed = await mainFeeds().fetchMusicHomeFeed({
    music: { getHomeFeed: async () => first }
  });

  assert.deepEqual(feed.sections.map((section) => section.title), [
    'Quick picks',
    'Albums for you',
    'New releases'
  ]);
  assert.deepEqual(feed.filters, ['Energize']);
});

test('loads raw browser home continuation sections', async () => {
  const pages = {
    FEmusic_home: {
      sections: [{ key: 'quick', title: 'Quick picks', items: [{ id: 'one', type: 'track' }] }],
      continuation: 'home-next'
    },
    'home-next': {
      continuationContents: {
        sectionListContinuation: {
          contents: [{ key: 'albums', title: 'Albums for you', items: [{ id: 'two', type: 'album' }] }]
        }
      }
    }
  };
  const requests = [];
  const feed = await mainFeeds({
    fetchRawBrowserMusicBrowse: async (request) => {
      const key = request.continuation || request.browseId;
      requests.push(key);
      return pages[key];
    },
    rawSectionList: (data) => data.sections || [],
    normalizeBrowseSection: (section) => section,
    browseContinuationTokenFromData: (data) => data.continuation || null
  }).fetchBrowserMusicHome();

  assert.deepEqual(feed.sections.map((section) => section.title), ['Quick picks', 'Albums for you']);
  assert.deepEqual(requests, ['FEmusic_home', 'home-next']);
});

test('unwraps item sections in the newer music library layout', () => {
  const album = { musicTwoRowItemRenderer: { title: { runs: [{ text: 'Album' }] } } };
  const data = {
    contents: {
      singleColumnBrowseResultsRenderer: {
        tabs: [{
          tabRenderer: {
            content: {
              sectionListRenderer: {
                contents: [{
                  itemSectionRenderer: {
                    contents: [{ gridRenderer: { items: [album] } }]
                  }
                }]
              }
            }
          }
        }]
      }
    }
  };
  const normalizers = createBrowseNormalizers({
    asText: () => '',
    bestThumbnail: () => '',
    cleanedText: () => '',
    findDurationText: () => '',
    hasExplicitBadge: () => false,
    normalizeTrack: (item) => item,
    normalizedLooseText: (value) => String(value || '').toLowerCase(),
    textParts: () => []
  });

  assert.deepEqual(normalizers.rawBrowseItemsFromData(data), [album]);
});

test('loads, paginates, and deduplicates a raw music library category', async () => {
  const firstAlbum = { browseId: 'MPR-first', title: 'First', type: 'album' };
  const secondAlbum = { browseId: 'MPR-second', title: 'Second', type: 'album' };
  const landing = {
    chips: [{
      chipCloudChipRenderer: {
        text: { runs: [{ text: 'Albums' }] },
        navigationEndpoint: {
          commandExecutorCommand: {
            commands: [{ browseEndpoint: { browseId: 'library-albums' } }]
          }
        }
      }
    }]
  };
  const pages = {
    FEmusic_library_landing: landing,
    'library-albums': { items: [firstAlbum], continuation: 'next-page' },
    'next-page': { items: [firstAlbum, secondAlbum] }
  };
  const requests = [];
  const yt = {
    actions: {
      execute: async (_path, request) => {
        requests.push(request);
        return { data: pages[request.continuation || request.browseId] };
      }
    }
  };

  assert.deepEqual(
    await mainFeeds().fetchMusicLibraryCategory(yt, 'albums'),
    [firstAlbum, secondAlbum]
  );
  assert.deepEqual(
    requests.map((request) => request.continuation || request.browseId),
    ['FEmusic_library_landing', 'library-albums', 'next-page']
  );
});

test('loads every saved playlist from the dedicated playlist grid', async () => {
  const first = { browseId: 'VL-first', title: 'First', type: 'playlist' };
  const second = { browseId: 'VL-second', title: 'Second', type: 'playlist' };
  const pages = {
    FEmusic_liked_playlists: { items: [first], continuation: 'playlist-page-2' },
    'playlist-page-2': { items: [first, second] }
  };
  const requests = [];
  const yt = {
    actions: {
      execute: async (_path, request) => {
        const key = request.continuation || request.browseId;
        requests.push(key);
        return { data: pages[key] };
      }
    }
  };

  assert.deepEqual(
    await mainFeeds().fetchMusicLibraryCategory(yt, 'Playlists'),
    [first, second]
  );
  assert.deepEqual(requests, ['FEmusic_liked_playlists', 'playlist-page-2']);
});

test('omits the YouTube Music shuffle action from library song data', async () => {
  const shuffleAction = { id: null, title: 'Shuffle all', type: 'track' };
  const song = { id: 'song-id', title: 'A saved song', type: 'track' };
  const landing = {
    chips: [{
      chipCloudChipRenderer: {
        text: { runs: [{ text: 'Songs' }] },
        navigationEndpoint: { browseEndpoint: { browseId: 'library-songs' } }
      }
    }]
  };
  const pages = {
    FEmusic_library_landing: landing,
    'library-songs': { items: [shuffleAction, song] }
  };
  const yt = {
    actions: {
      execute: async (_path, request) => ({ data: pages[request.browseId] })
    }
  };

  assert.deepEqual(
    await mainFeeds().fetchMusicLibraryCategory(yt, 'Songs'),
    [song]
  );
});

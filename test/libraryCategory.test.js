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

function mainFeeds() {
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
    rawSectionList: () => []
  });
}

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

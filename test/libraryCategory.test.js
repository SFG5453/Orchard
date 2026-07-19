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

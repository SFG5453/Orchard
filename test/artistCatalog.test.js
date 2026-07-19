import test from 'node:test';
import assert from 'node:assert/strict';
import { createArtistCatalog } from '../electron/catalog/artistCatalog.js';
import { createSearchUtils } from '../electron/catalog/searchUtils.js';

function mergeTrackMetadata(track, candidates) {
  const match = candidates.find((candidate) => candidate !== track && candidate.id === track.id);
  return match ? {
    ...track,
    album: track.album || match.album || '',
    albumId: track.albumId || match.albumId || null,
    duration: track.duration || match.duration || '',
    durationSeconds: track.durationSeconds || match.durationSeconds || 0
  } : track;
}

function artistCatalog(
  searchTrackAlbumMetadata = async () => null,
  artistBrowseSectionItemMatches = () => true
) {
  return createArtistCatalog({
    asText: (value) => String(value || ''),
    artistBrowseSectionItemMatches,
    browseContinuationTokenFromData: () => '',
    dedupeMediaItems: (items) => [...new Map(items.map((item) => [item.id || item.browseId, item])).values()],
    isSingleOrEpRelease: () => false,
    itemMatchesReleaseSection: () => true,
    mergeTrackMetadata,
    normalizeAlbum: (album, browseId) => ({ ...album, browseId }),
    normalizeBrowseSection: (section) => section,
    normalizeRawBrowseItem: (item) => item,
    normalizedLooseText: (value) => String(value || '').toLowerCase(),
    rawBrowseDescription: () => '',
    rawBrowseItemsFromData: () => [],
    rawBrowseThumbnail: () => '',
    rawHeader: (artist) => artist.header,
    rawMicroformat: () => ({}),
    rawSectionList: (artist) => artist.sections,
    searchArtistShelfFallback: async () => [],
    searchTrackAlbumMetadata
  });
}

function artistMatcher() {
  return createSearchUtils({
    asText: (value) => String(value || ''),
    bestThumbnail: () => '',
    hasExplicitBadge: () => false,
    normalizedLooseText: (value) => String(value || '').trim().toLowerCase(),
    shelfItems: () => [],
    textParts: () => []
  }).artistMatchesSearchItem;
}

test('artist identity does not fall back to a matching name when browse IDs differ', () => {
  const matches = artistMatcher();

  assert.equal(matches({ artist: 'flo', artistBrowseIds: ['other-flo'] }, 'FLO', 'flo-group'), false);
  assert.equal(matches({ artist: 'FLO', artistBrowseIds: ['flo-group'] }, 'FLO', 'flo-group'), true);
  assert.equal(matches({ artist: 'FLO', artistBrowseIds: [] }, 'FLO', 'flo-group'), true);
});

test('normalizeArtist returns Popular tracks with album and duration metadata', async () => {
  const popularTrack = { id: 'song-1', title: 'Song One', artist: 'Example', album: '', duration: '' };
  const release = {
    browseId: 'release-1',
    title: 'First Album',
    type: 'album',
    artist: 'Example',
    browsePayload: { browseId: 'release-1' }
  };
  const collection = {
    browseId: 'artist-1',
    data: {
      header: { title: 'Example' },
      sections: [
        { title: 'Top songs', items: [popularTrack] },
        { title: 'Albums', items: [release] }
      ]
    },
    browse: async () => ({
      title: 'First Album',
      artist: 'Example',
      tracks: [{ ...popularTrack, album: 'First Album', albumId: 'release-1', duration: '3:42', durationSeconds: 222 }]
    })
  };

  const detail = await artistCatalog().normalizeArtist(collection);

  assert.equal(detail.tracks[0].album, 'First Album');
  assert.equal(detail.tracks[0].albumId, 'release-1');
  assert.equal(detail.tracks[0].duration, '3:42');
  assert.equal(detail.tracks[0].durationSeconds, 222);
});

test('normalizeArtist searches missing Popular track metadata before returning', async () => {
  const popularTrack = { id: 'song-2', title: 'Loose Single', artist: 'Example', album: '', duration: '' };
  const collection = {
    browseId: 'artist-1',
    data: {
      header: { title: 'Example' },
      sections: [{ title: 'Top songs', items: [popularTrack] }]
    }
  };
  const catalog = artistCatalog(async () => ({
    ...popularTrack,
    album: 'Loose Single',
    albumId: 'single-1',
    duration: '2:58',
    durationSeconds: 178
  }));

  const detail = await catalog.normalizeArtist(collection);

  assert.equal(detail.tracks[0].album, 'Loose Single');
  assert.equal(detail.tracks[0].duration, '2:58');
});

test('normalizeArtist loads release and search metadata concurrently', async () => {
  let finishAlbumBrowse;
  let searchStarted = false;
  const popularTrack = { id: 'song-3', title: 'Concurrent Song', artist: 'Example', album: '', duration: '' };
  const collection = {
    browseId: 'artist-1',
    data: {
      header: { title: 'Example' },
      sections: [
        { title: 'Top songs', items: [popularTrack] },
        { title: 'Albums', items: [{ browseId: 'release-1', title: 'Album', type: 'album' }] }
      ]
    },
    browse: () => new Promise((resolve) => { finishAlbumBrowse = resolve; })
  };
  const catalog = artistCatalog(async () => {
    searchStarted = true;
    return { ...popularTrack, album: 'Album', duration: '3:10' };
  });

  const pending = catalog.normalizeArtist(collection);
  await Promise.resolve();
  assert.equal(searchStarted, true);

  finishAlbumBrowse({ title: 'Album', tracks: [] });
  await pending;
});

test('normalizeArtist filters same-name releases with another artist identity', async () => {
  const collection = {
    browseId: 'flo-group',
    data: {
      header: { title: 'FLO' },
      sections: [{
        title: 'Albums',
        items: [
          { browseId: 'wanted', title: 'Walk Like This', artistBrowseIds: ['flo-group'] },
          { browseId: 'collision', title: 'Deep Smell', artistBrowseIds: ['other-flo'] }
        ]
      }]
    }
  };
  const matchesArtist = (item, _artistName, artistBrowseId) =>
    item.artistBrowseIds.includes(artistBrowseId);

  const detail = await artistCatalog(async () => null, matchesArtist).normalizeArtist(collection);

  assert.deepEqual(detail.sections[0].items.map((item) => item.title), ['Walk Like This']);
});

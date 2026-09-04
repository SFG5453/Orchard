import assert from 'node:assert/strict';
import test from 'node:test';

import {
  fetchTauriHomeFeed,
  fetchTauriLibraryCategory,
  fetchTauriLibraryFeed,
  normalizeTauriAlbum,
  normalizeTauriArtist,
  normalizeTauriMusicFeed,
  normalizeTauriPlaylist,
  normalizeTauriPlaylistPage,
  normalizeTauriSearch,
  normalizeTauriUpNext,
  startTauriHomeFeed,
  startTauriLibraryFeed
} from '../src/platform/desktop/tauriMusicCatalog.js';

function song(id, title = id) {
  return {
    id,
    item_type: 'song',
    title,
    artists: [{ name: 'Artist', channel_id: 'UCartist' }],
    duration: { text: '3:00', seconds: 180 },
    thumbnails: [{ url: `https://img.example/${id}.jpg`, width: 120 }]
  };
}

function playlist(id, title = id) {
  return {
    item_type: 'playlist',
    title,
    endpoint: {
      payload: {
        browseId: id,
        browseEndpointContextSupportedConfigs: {
          browseEndpointContextMusicConfig: { pageType: 'MUSIC_PAGE_TYPE_PLAYLIST' }
        }
      }
    }
  };
}

function page({ sections, contents, filters = [], next = null }) {
  return {
    sections,
    contents,
    filters,
    has_continuation: Boolean(next),
    async getContinuation() {
      return next;
    }
  };
}

test('normalizes parsed YouTube Music cards without losing browse identity', () => {
  const feed = normalizeTauriMusicFeed({
    sections: [{
      header: { title: 'Made for you' },
      contents: [song('song-1', 'First'), playlist('VLplaylist-1', 'Saved')]
    }]
  }, 'home');

  assert.equal(feed.sections[0].title, 'Made for you');
  assert.equal(feed.sections[0].items[0].id, 'song-1');
  assert.equal(feed.sections[0].items[0].artist, 'Artist');
  assert.equal(feed.sections[0].items[1].browseId, 'VLplaylist-1');
  assert.equal(feed.sections[0].items[1].type, 'playlist');
});

test('groups parsed search results into stable ordered Orchard categories', () => {
  const search = normalizeTauriSearch({
    filters: ['Songs', 'Albums'],
    contents: [
      {
        type: 'MusicCardShelf',
        title: 'FLO',
        subtitle: 'Artist',
        on_tap: {
          payload: {
            browseId: 'UCFLO',
            browseEndpointContextSupportedConfigs: {
              browseEndpointContextMusicConfig: { pageType: 'MUSIC_PAGE_TYPE_ARTIST' }
            }
          }
        },
        contents: [song('top-song')]
      },
      { type: 'MusicShelf', title: 'Albums', contents: [{
        item_type: 'album',
        title: 'Access All Areas',
        endpoint: { payload: { browseId: 'MPRalbum' } }
      }] },
      { type: 'MusicShelf', title: 'Songs', contents: [song('top-song'), song('second-song')] },
      { type: 'MusicShelf', contents: [playlist('VLplaylist')] }
    ]
  });

  assert.deepEqual(search.sections.map((section) => section.key), [
    'songs', 'albums', 'artists', 'playlists'
  ]);
  assert.deepEqual(search.sections.map((section) => section.title), [
    'Songs', 'Albums', 'Artists', 'Playlists'
  ]);
  assert.deepEqual(search.sections[0].items.map((item) => item.id), ['top-song', 'second-song']);
  assert.equal(search.sections[2].items[0].title, 'FLO');
});

test('loads every available parsed home continuation', async () => {
  const third = page({ sections: [{ title: 'Third', contents: [song('three')] }] });
  const second = page({ sections: [{ title: 'Second', contents: [song('two')] }], next: third });
  const first = page({ sections: [{ title: 'First', contents: [song('one')] }], filters: ['Energize'], next: second });

  const feed = await fetchTauriHomeFeed({ getHomeFeed: async () => first });

  assert.deepEqual(feed.filters, ['Energize']);
  assert.deepEqual(feed.sections.map((section) => section.items[0].id), ['one', 'two', 'three']);
});

test('makes the first Linux home page available before its continuations', async () => {
  const second = page({ sections: [{ title: 'Second', contents: [song('two')] }] });
  const first = page({ sections: [{ title: 'First', contents: [song('one')] }], next: second });

  const staged = await startTauriHomeFeed({ getHomeFeed: async () => first });

  assert.deepEqual(staged.initial.sections.map((section) => section.items[0].id), ['one']);
  assert.deepEqual((await staged.complete).sections.map((section) => section.items[0].id), ['one', 'two']);
});

test('supplements the Linux library with saved playlists and deduplicates them', async () => {
  const playlistContinuation = page({ contents: { contents: [playlist('VLtwo', 'Two')] } });
  const playlistPage = page({
    contents: [{ title: 'Playlists', contents: [playlist('VLone', 'One')] }],
    next: playlistContinuation
  });
  const library = page({
    contents: [{ title: 'Recent activity', contents: [song('recent')] }],
    filters: ['Songs', 'Albums', 'Playlists']
  });
  library.applyFilter = async (filter) => {
    assert.equal(filter, 'Playlists');
    return playlistPage;
  };

  const feed = await fetchTauriLibraryFeed({ getLibrary: async () => library });

  assert.equal(feed.sections[0].title, 'Library');
  assert.deepEqual(feed.sections[0].items.map((item) => item.browseId), ['VLone', 'VLtwo']);
  assert.equal(feed.sections[1].items[0].id, 'recent');
});

test('makes the initial Linux library available before playlist supplementation', async () => {
  const playlistPage = page({
    contents: [{ title: 'Playlists', contents: [playlist('VLone', 'One')] }]
  });
  const library = page({
    contents: [{ title: 'Recent activity', contents: [song('recent')] }],
    filters: ['Playlists']
  });
  library.applyFilter = async () => playlistPage;

  const staged = await startTauriLibraryFeed({ getLibrary: async () => library });

  assert.deepEqual(staged.initial.sections[0].items.map((item) => item.id), ['recent']);
  assert.equal((await staged.complete).sections[0].items[0].browseId, 'VLone');
});

test('loads and deduplicates a filtered Linux library category', async () => {
  const continuation = page({ contents: { contents: [song('two'), song('one')] } });
  const songsPage = page({
    contents: [{ title: 'Songs', contents: [song('one')] }],
    next: continuation
  });
  const library = page({ filters: ['Songs'] });
  library.applyFilter = async () => songsPage;

  const items = await fetchTauriLibraryCategory({ getLibrary: async () => library }, 'Songs');

  assert.deepEqual(items.map((item) => item.id), ['one', 'two']);
});

test('loads Recently Added from the default Linux library feed', async () => {
  const continuation = page({ contents: { contents: [song('older'), song('newest')] } });
  const library = page({
    contents: [{ title: 'Recent activity', contents: [song('newest')] }],
    filters: ['Songs', 'Albums'],
    next: continuation
  });
  library.applyFilter = async () => {
    throw new Error('Recently Added must not depend on a category chip.');
  };

  const items = await fetchTauriLibraryCategory({ getLibrary: async () => library }, 'Recently Added');

  assert.deepEqual(items.map((item) => item.id), ['newest', 'older']);
});

test('normalizes Linux playlist pages and collection metadata', () => {
  const parsed = {
    header: {
      title: 'Road trip',
      subtitle: 'Private playlist',
      second_subtitle: '2 songs • 6 minutes',
      thumbnails: [{ url: 'https://img.example/playlist.jpg', width: 640 }]
    },
    items: [song('one'), song('two')]
  };

  const detail = normalizeTauriPlaylist(parsed, 'VLroad', 'playlist:1');
  const nextTracks = normalizeTauriPlaylistPage({ items: [song('three')] }, 2);

  assert.equal(detail.kind, 'playlist');
  assert.equal(detail.title, 'Road trip');
  assert.equal(detail.itemCount, '2 songs');
  assert.equal(detail.totalDuration, '6 minutes');
  assert.equal(detail.hasMoreTracks, true);
  assert.deepEqual(detail.tracks.map((track) => track.index), ['1', '2']);
  assert.equal(nextTracks[0].index, '3');
});

test('normalizes albums, artists, and autoplay panels for renderer navigation', () => {
  const album = normalizeTauriAlbum({
    header: {
      title: 'Record',
      author: { name: 'Artist', channel_id: 'UCartist' },
      year: '2026',
      song_count: '1 song',
      thumbnails: [{ url: 'https://img.example/record.jpg', width: 640 }]
    },
    contents: [song('album-song')],
    sections: [{ header: { title: 'More like this' }, contents: [playlist('VLrelated')] }]
  }, 'MPRrecord');
  const artist = normalizeTauriArtist({
    header: { title: 'Artist', thumbnail: { contents: [{ url: 'https://img.example/artist.jpg' }] } },
    sections: [
      { title: 'Top songs', contents: [song('top-song')] },
      { header: { title: 'Albums' }, contents: [playlist('VLalbum-card')] }
    ]
  }, 'UCartist');
  const queue = normalizeTauriUpNext({ contents: [{ ...song('next'), video_id: 'next', id: undefined }] });

  assert.equal(album.artistBrowseId, 'UCartist');
  assert.equal(album.tracks[0].index, '1');
  assert.equal(album.sections[0].title, 'More like this');
  assert.equal(artist.tracks[0].id, 'top-song');
  assert.equal(artist.sections[0].title, 'Albums');
  assert.equal(queue[0].id, 'next');
});

test('Linux artist Popular matches Electron with five tracks and no catalog count', () => {
  const artist = normalizeTauriArtist({
    header: { title: 'Artist' },
    sections: [{
      title: 'Top songs',
      contents: Array.from({ length: 12 }, (_, index) => song(`popular-${index + 1}`))
    }]
  }, 'UCartist');

  assert.equal(artist.tracks.length, 5);
  assert.deepEqual(artist.tracks.map((track) => track.index), ['1', '2', '3', '4', '5']);
  assert.equal(artist.itemCount, '');
});

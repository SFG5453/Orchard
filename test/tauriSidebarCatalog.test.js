/*
 * Copyright (C) 2026 SFG545
 *
 * This file is part of Orchard.
 *
 * Orchard is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 */

import assert from 'node:assert/strict';
import test from 'node:test';

import {
  createTauriSidebarCatalog,
  normalizeTauriRadio
} from '../src/platform/desktop/tauriSidebarCatalog.js';

function browseEndpoint(browseId, pageType) {
  return {
    browseId,
    browseEndpointContextSupportedConfigs: {
      browseEndpointContextMusicConfig: { pageType }
    }
  };
}

function twoRow(title, browseId, pageType = 'MUSIC_PAGE_TYPE_PLAYLIST') {
  return {
    musicTwoRowItemRenderer: {
      title: { runs: [{ text: title }] },
      subtitle: { runs: [{ text: 'Made for you' }] },
      navigationEndpoint: { browseEndpoint: browseEndpoint(browseId, pageType) },
      thumbnailRenderer: {
        musicThumbnailRenderer: {
          thumbnail: { thumbnails: [{ url: `${browseId}.jpg`, width: 400 }] }
        }
      }
    }
  };
}

function sectionList(contents) {
  return {
    contents: {
      singleColumnBrowseResultsRenderer: {
        tabs: [{ tabRenderer: { content: { sectionListRenderer: { contents } } } }]
      }
    }
  };
}

function carousel(title, contents) {
  return {
    musicCarouselShelfRenderer: {
      header: { musicCarouselShelfBasicHeaderRenderer: { title: { runs: [{ text: title }] } } },
      contents
    }
  };
}

function playlistTrack(id, title) {
  return {
    musicResponsiveListItemRenderer: {
      flexColumns: [
        { musicResponsiveListItemFlexColumnRenderer: { text: { runs: [{ text: title }] } } },
        { musicResponsiveListItemFlexColumnRenderer: { text: { runs: [{ text: 'Artist' }] } } }
      ],
      fixedColumns: [{ musicResponsiveListItemFixedColumnRenderer: { text: { runs: [{ text: '3:00' }] } } }],
      playlistItemData: { videoId: id }
    }
  };
}

test('selects My Supermix from the raw Linux mixed-for-you page', () => {
  const data = sectionList([
    carousel('Mixed for you', [
      twoRow('Discover Mix', 'RDdiscover'),
      twoRow('My Supermix', 'RDsupermix')
    ])
  ]);

  assert.deepEqual(normalizeTauriRadio(data), {
    id: null,
    browseId: 'RDsupermix',
    browsePayload: browseEndpoint('RDsupermix', 'MUSIC_PAGE_TYPE_PLAYLIST'),
    type: 'playlist',
    musicVideoType: '',
    isAudioOnly: false,
    title: 'My Supermix',
    subtitle: 'Made for you',
    artists: [],
    artistBrowseIds: [],
    artist: '',
    album: '',
    albumId: null,
    duration: '',
    durationSeconds: 0,
    explicit: false,
    year: 'Made for you',
    views: '',
    itemCount: '',
    thumbnail: 'RDsupermix.jpg',
    index: '2',
    isUpload: false
  });
});

test('loads the podcasts destination and podcast detail through raw music browse', async () => {
  const show = twoRow('The Orchard Show', 'MPSPshow', 'MUSIC_PAGE_TYPE_PODCAST_SHOW_DETAIL_PAGE');
  const feedData = sectionList([carousel('Your shows', [show])]);
  const detailData = {
    ...sectionList([]),
    microformat: {
      microformatDataRenderer: {
        title: 'The Orchard Show',
        thumbnail: { thumbnails: [{ url: 'show.jpg', width: 400 }] }
      }
    }
  };
  const requests = [];
  const client = {
    actions: {
      execute: async (_path, payload) => {
        requests.push(payload.browseId);
        return { data: payload.browseId === 'FEmusic_podcasts' ? feedData : detailData };
      }
    }
  };
  const catalog = createTauriSidebarCatalog({
    getMusicClient: async () => client,
    getSubscriptionClient: async () => ({})
  });

  const feed = await catalog.podcasts();
  const detail = await catalog.podcast({ browseId: 'MPSPshow' });

  assert.equal(feed.sections[0].items[0].browseId, 'MPSPshow');
  assert.equal(feed.sections[0].items[0].type, 'podcast');
  assert.equal(detail.kind, 'podcast');
  assert.equal(detail.title, 'The Orchard Show');
  assert.deepEqual(requests, ['FEmusic_podcasts', 'MPSPshow']);
});

test('opens RD radio playlists with raw browse and paginates their tracks', async () => {
  const firstPage = {
    header: { musicDetailHeaderRenderer: { title: { runs: [{ text: 'My Supermix' }] } } },
    ...sectionList([{
      musicPlaylistShelfRenderer: {
        contents: [
          playlistTrack('one', 'One'),
          { continuationItemRenderer: { continuationEndpoint: { continuationCommand: { token: 'next' } } } }
        ]
      }
    }])
  };
  const nextPage = {
    continuationContents: {
      musicPlaylistShelfContinuation: { contents: [playlistTrack('two', 'Two')] }
    }
  };
  const requests = [];
  const client = {
    actions: {
      execute: async (_path, payload) => {
        requests.push(payload);
        return { data: payload.continuation ? nextPage : firstPage };
      }
    }
  };
  const catalog = createTauriSidebarCatalog({
    getMusicClient: async () => client,
    getSubscriptionClient: async () => ({})
  });

  const playlist = await catalog.playlist({ browseId: 'RDsupermix' });
  const continuation = await catalog.continuePlaylist(playlist.continuation, playlist.tracks.length);

  assert.equal(playlist.kind, 'playlist');
  assert.equal(playlist.browseId, 'RDsupermix');
  assert.deepEqual(playlist.tracks.map((track) => track.id), ['one']);
  assert.equal(playlist.continuation, 'next');
  assert.deepEqual(continuation.tracks.map((track) => track.id), ['two']);
  assert.equal(continuation.tracks[0].index, '2');
  assert.equal(requests[0].browseId, 'RDsupermix');
  assert.equal(requests[1].continuation, 'next');
});

test('paginates official artist subscriptions and performs subscription changes', async () => {
  const artist = (id, name) => ({
    id,
    author: {
      id,
      name,
      is_verified_artist: true,
      thumbnails: [{ url: `${id}.jpg` }]
    }
  });
  const secondPage = { channels: [artist('UCtwo', 'Two')], has_continuation: false };
  const firstPage = {
    channels: [artist('UCone', 'One')],
    has_continuation: true,
    getContinuation: async () => secondPage
  };
  const mutations = [];
  const subscriptionClient = {
    getChannelsFeed: async () => firstPage,
    interact: {
      subscribe: async (id) => mutations.push(['subscribe', id]),
      unsubscribe: async (id) => mutations.push(['unsubscribe', id])
    }
  };
  const catalog = createTauriSidebarCatalog({
    getMusicClient: async () => ({}),
    getSubscriptionClient: async () => subscriptionClient
  });

  assert.deepEqual((await catalog.subscribedArtists()).map((item) => item.browseId), ['UCone', 'UCtwo']);
  const result = await catalog.setArtistSubscription({ browseId: 'UCthree', subscribed: true });

  assert.deepEqual(mutations, [['subscribe', 'UCthree']]);
  assert.equal(result.subscribed, true);
  assert.deepEqual(result.artists.map((item) => item.browseId), ['UCone', 'UCtwo']);
});

test('builds New from subscribed artists and resolves released albums against music search', async () => {
  const subscriptionClient = {
    getChannelsFeed: async () => ({
      channels: [{
        id: 'UCartist',
        author: { id: 'UCartist', name: 'Artist', is_verified_artist: true, thumbnails: [] }
      }],
      has_continuation: false
    })
  };
  const release = {
    title: 'New Record',
    artist: 'Artist',
    releaseDate: '2026-09-01',
    releaseDateText: 'September 1, 2026',
    releaseDaysFromToday: -2,
    releaseTiming: 'recently_released',
    releaseTimingLabel: 'Released 2 days ago',
    releaseResolved: false
  };
  const futureAlbums = {
    releaseRadarForArtists: async (artists) => {
      assert.deepEqual(artists.map((item) => item.browseId), ['UCartist']);
      return [release];
    },
    releaseAlbumMatches: (_release, album) => album.title === 'New Record',
    resolveFutureAlbum: async () => ({}),
    resolveItunesAlbum: async () => ({})
  };
  const music = {
    search: async () => ({
      contents: [{
        type: 'MusicShelf',
        title: 'Albums',
        contents: [{
          item_type: 'album',
          title: 'New Record',
          endpoint: { payload: browseEndpoint('MPRrecord', 'MUSIC_PAGE_TYPE_ALBUM') }
        }]
      }]
    })
  };
  const catalog = createTauriSidebarCatalog({
    getMusicClient: async () => ({ music }),
    getSubscriptionClient: async () => subscriptionClient,
    futureAlbums
  });

  const result = await catalog.releaseRadar();

  assert.equal(result.artists[0].name, 'Artist');
  assert.equal(result.releases[0].browseId, 'MPRrecord');
  assert.equal(result.releases[0].releaseResolved, true);
  assert.equal(result.releases[0].releaseTiming, 'recently_released');
});

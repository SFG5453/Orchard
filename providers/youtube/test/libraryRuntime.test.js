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
import { createRuntimeLibrary } from '../src/runtime/library.js';

const session = { cookie: 'SAPISID=test-cookie' };
const videoRow = { id: 'original123', type: 'video', musicVideoType: 'MUSIC_VIDEO_TYPE_OMV',
  title: 'Scream', artist: 'Michael Jackson', album: 'Scream', duration: '4:38', durationSeconds: 278 };

function response(data) {
  return { ok: true, status: 200, text: async () => JSON.stringify(data) };
}

function searchResponse(id) {
  return response({ contents: { tabbedSearchResultsRenderer: { tabs: [{ tabRenderer: {
    content: { sectionListRenderer: { contents: [{ musicShelfRenderer: { contents: [{
      musicResponsiveListItemRenderer: {
        playlistItemData: { videoId: id },
        flexColumns: [
          { musicResponsiveListItemFlexColumnRenderer: { text: { runs: [{ text: 'Scream', navigationEndpoint: {
            watchEndpoint: { videoId: id, watchEndpointMusicSupportedConfigs: {
              watchEndpointMusicConfig: { musicVideoType: 'MUSIC_VIDEO_TYPE_ATV' }
            } }
          } }] } } },
          { musicResponsiveListItemFlexColumnRenderer: { text: { runs: [
            { text: 'Michael Jackson', navigationEndpoint: { browseEndpoint: { browseId: 'UCartist' } } },
            { text: ' • ' }, { text: '4:38' }
          ] } } }
        ]
      }
    }] } }] } }
  } }] } } });
}

function options(containsSelectedVideos = 'NONE') {
  return response({ contents: [{ addToPlaylistRenderer: { playlists: [
    { playlistAddToOptionRenderer: { playlistId: 'PLmine', title: { simpleText: 'Mine' },
      privacy: 'PRIVATE', containsSelectedVideos } }
  ] } }] });
}

// Router keyed on the innertube endpoint; records every request body.
function fakeYouTube(handlers) {
  const calls = [];
  const fetchImpl = async (url, init) => {
    const endpoint = /\/youtubei\/v1\/([^?]+)/.exec(url)[1];
    const body = JSON.parse(init.body);
    calls.push({ endpoint, body });
    const handler = handlers[endpoint];
    if (!handler) throw new Error(`Unexpected endpoint ${endpoint}`);
    return handler(body);
  };
  return { calls, fetchImpl };
}

test('adding a music-video row saves the album audio playback would open', async () => {
  const yt = fakeYouTube({
    search: () => searchResponse('albumaudio1'),
    'playlist/get_add_to_playlist': (body) => {
      assert.deepEqual(body.videoIds, ['albumaudio1']);
      return options();
    },
    'browse/edit_playlist': () => response({ status: 'STATUS_SUCCEEDED' })
  });
  const result = await createRuntimeLibrary(yt.fetchImpl)
    .addToPlaylist({ session, playlistId: 'VLPLmine', track: videoRow });
  assert.equal(result.videoId, 'albumaudio1');
  const edit = yt.calls.find((call) => call.endpoint === 'browse/edit_playlist').body;
  assert.equal(edit.playlistId, 'PLmine');
  assert.equal(edit.actions[0].addedVideoId, 'albumaudio1');
});

test('creating a playlist seeds it with the resolved audio version', async () => {
  const yt = fakeYouTube({
    search: () => searchResponse('albumaudio1'),
    'playlist/create': (body) => {
      assert.equal(body.title, 'Road trip');
      assert.equal(body.privacyStatus, 'PRIVATE');
      assert.deepEqual(body.videoIds, ['albumaudio1']);
      return response({ playlistId: 'PLnew' });
    }
  });
  const result = await createRuntimeLibrary(yt.fetchImpl)
    .createPlaylist({ session, title: '  Road trip ', track: videoRow });
  assert.deepEqual(result, { playlistId: 'PLnew', videoId: 'albumaudio1', title: 'Road trip' });
});

test('a known playback id skips the audio search', async () => {
  const yt = fakeYouTube({
    'like/like': (body) => {
      assert.equal(body.target.videoId, 'streamed123');
      return response({});
    }
  });
  const result = await createRuntimeLibrary(yt.fetchImpl)
    .setLike({ session, videoId: 'streamed123', track: videoRow, liked: true });
  assert.deepEqual(result, { videoId: 'streamed123', liked: true });
  assert.deepEqual(yt.calls.map((call) => call.endpoint), ['like/like']);
});

test('unliking and reading like status use the Music endpoints', async () => {
  const yt = fakeYouTube({
    'like/removelike': () => response({}),
    next: () => response({ playerOverlays: { playerOverlayRenderer: { actions: [
      { likeButtonRenderer: { likeStatus: 'LIKE' } }
    ] } } })
  });
  const library = createRuntimeLibrary(yt.fetchImpl);
  assert.deepEqual(await library.setLike({ session, videoId: 'streamed123', liked: false }),
    { videoId: 'streamed123', liked: false });
  assert.deepEqual(await library.likeStatus({ session, videoId: 'streamed123' }),
    { videoId: 'streamed123', liked: true });
});

test('adding refuses duplicates and playlists the account cannot edit', async () => {
  const duplicate = fakeYouTube({ 'playlist/get_add_to_playlist': () => options('ALL') });
  await assert.rejects(createRuntimeLibrary(duplicate.fetchImpl)
    .addToPlaylist({ session, playlistId: 'PLmine', videoId: 'streamed123' }), /already in that playlist/);
  const foreign = fakeYouTube({ 'playlist/get_add_to_playlist': () => options() });
  await assert.rejects(createRuntimeLibrary(foreign.fetchImpl)
    .addToPlaylist({ session, playlistId: 'PLsomeoneElse', videoId: 'streamed123' }), /cannot be edited/);
  assert.ok(!duplicate.calls.some((call) => call.endpoint === 'browse/edit_playlist'));
});

test('playlist targets list editable playlists for the resolved id', async () => {
  const yt = fakeYouTube({ 'playlist/get_add_to_playlist': () => options('ALL') });
  const result = await createRuntimeLibrary(yt.fetchImpl)
    .playlistTargets({ session, videoId: 'streamed123' });
  assert.equal(result.videoId, 'streamed123');
  assert.deepEqual(result.playlists, [{ id: 'PLmine', title: 'Mine', privacy: 'private', containsTrack: true }]);
});

test('the playlist picker asks www.youtube.com first and falls back to the Music host', async () => {
  const hosts = [];
  const fetchImpl = async (url) => {
    const host = new URL(url).host;
    hosts.push(host);
    return host === 'www.youtube.com' ? response({ contents: [] }) : options();
  };
  const result = await createRuntimeLibrary(fetchImpl).playlistTargets({ session, videoId: 'streamed123' });
  assert.deepEqual(hosts, ['www.youtube.com', 'music.youtube.com']);
  assert.equal(result.playlists[0].id, 'PLmine');
});

test('removing deletes the stored row, not its audio version', async () => {
  const yt = fakeYouTube({
    'browse/edit_playlist': (body) => {
      assert.equal(body.playlistId, 'PLmine');
      assert.deepEqual(body.actions, [{ action: 'ACTION_REMOVE_VIDEO', removedVideoId: 'original123', setVideoId: 'SET1' }]);
      return response({ status: 'STATUS_SUCCEEDED' });
    }
  });
  const result = await createRuntimeLibrary(yt.fetchImpl)
    .removeFromPlaylist({ session, playlistId: 'VLPLmine', title: 'Mine', track: { ...videoRow, setVideoId: 'SET1' } });
  assert.deepEqual(result, { playlistId: 'PLmine', videoId: 'original123', setVideoId: 'SET1', title: 'Mine' });
  assert.ok(!yt.calls.some((call) => call.endpoint === 'search'));
});

test('removing looks up the entry id when the row lacks one', async () => {
  const yt = fakeYouTube({
    browse: (body) => {
      assert.equal(body.browseId, 'VLPLmine');
      return response({ contents: [{ playlistItemData: { videoId: 'original123', playlistSetVideoId: 'SET9' } }] });
    },
    'browse/edit_playlist': (body) => {
      assert.equal(body.actions[0].setVideoId, 'SET9');
      return response({ status: 'STATUS_SUCCEEDED' });
    }
  });
  await createRuntimeLibrary(yt.fetchImpl).removeFromPlaylist({ session, playlistId: 'PLmine', track: videoRow });
  const missing = fakeYouTube({ browse: () => response({ contents: [] }) });
  await assert.rejects(createRuntimeLibrary(missing.fetchImpl)
    .removeFromPlaylist({ session, playlistId: 'PLmine', track: videoRow }), /not in that playlist/);
});

function playlistRow(videoId, setVideoId) {
  return { musicResponsiveListItemRenderer: {
    playlistItemData: { videoId, playlistSetVideoId: setVideoId },
    flexColumns: [{ musicResponsiveListItemFlexColumnRenderer: { text: { runs: [{ text: videoId }] } } }]
  } };
}

function playlistBrowse(rows) {
  return response({ contents: { twoColumnBrowseResultsRenderer: { secondaryContents: { sectionListRenderer: {
    contents: [{ musicPlaylistShelfRenderer: { contents: rows } }]
  } } } } });
}

test('moving a row names the entry it now precedes, duplicates included', async () => {
  const yt = fakeYouTube({
    browse: () => playlistBrowse([
      playlistRow('songaaaaaaa', 'set-a'), playlistRow('songbbbbbbb', 'set-b'),
      playlistRow('songaaaaaaa', 'set-a2'), playlistRow('songccccccc', 'set-c')
    ]),
    'browse/edit_playlist': () => response({ status: 'STATUS_SUCCEEDED' })
  });
  await createRuntimeLibrary(yt.fetchImpl).movePlaylistItem({ session, playlistId: 'VLPLmine', fromIndex: 0, toIndex: 2 });
  const edit = yt.calls.find((call) => call.endpoint === 'browse/edit_playlist').body;
  assert.equal(edit.playlistId, 'PLmine');
  assert.deepEqual(edit.actions, [{ action: 'ACTION_MOVE_VIDEO_BEFORE', setVideoId: 'set-a', movedSetVideoIdSuccessor: 'set-c' }]);

  const last = fakeYouTube({
    browse: () => playlistBrowse([playlistRow('songaaaaaaa', 'set-a'), playlistRow('songbbbbbbb', 'set-b')]),
    'browse/edit_playlist': () => response({ status: 'STATUS_SUCCEEDED' })
  });
  await createRuntimeLibrary(last.fetchImpl).movePlaylistItem({ session, playlistId: 'PLmine', fromIndex: 0, toIndex: 1 });
  assert.deepEqual(last.calls.at(-1).body.actions, [{ action: 'ACTION_MOVE_VIDEO_BEFORE', setVideoId: 'set-a' }]);
  await assert.rejects(createRuntimeLibrary(last.fetchImpl)
    .movePlaylistItem({ session, playlistId: 'PLmine', fromIndex: 0, toIndex: 5 }), /playlist changed/);
});

test('subscriptions and playlist deletion use the signed-in Music session', async () => {
  const yt = fakeYouTube({
    'subscription/subscribe': () => response({}),
    'subscription/unsubscribe': () => response({}),
    'playlist/delete': () => response({})
  });
  const library = createRuntimeLibrary(yt.fetchImpl);
  await library.setArtistSubscription({ session, channelId: 'UCartist', subscribed: true });
  await library.setArtistSubscription({ session, channelId: 'UCartist', subscribed: false });
  await library.deletePlaylist({ session, playlistId: 'VLPLmine' });
  assert.deepEqual(yt.calls.map((call) => call.endpoint),
    ['subscription/subscribe', 'subscription/unsubscribe', 'playlist/delete']);
  assert.deepEqual(yt.calls[0].body.channelIds, ['UCartist']);
  assert.equal(yt.calls[2].body.playlistId, 'PLmine');
});

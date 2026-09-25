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
import { ref } from 'vue';
import { installPlaylistActions } from '../src/app/browse/playlistActions.js';
import { createPlaylistMutations } from '../electron/bridge/playlistMutations.js';

function createMockAppCtx() {
  const ctx = {
    authState: ref({ signedIn: true }),
    activeTrack: ref({ id: 'active-1', title: 'Track 1' }),
    queue: ref([{ id: 'queue-2', title: 'Track 2' }, { id: 'queue-3', title: 'Track 3' }]),
    continuousQueueEnabled: ref(false),
    continuousQueue: ref([]),
    userPlaylistItems: ref([
      { id: 'PL1', title: 'My Favs', subtitle: '5 songs' },
      { id: 'PL2', title: 'Roadtrip', subtitle: '10 songs' }
    ]),
    itemBrowseId: (item) => item.id,
    socket: ref({ connected: true }),
    browseDetail: ref(null),
    homeData: ref({ library: { sections: [] } }),
    loadHomeLibrary: async () => {},
    showShareMessage: () => {},
    emitWithReply: async () => {}
  };
  installPlaylistActions(ctx);
  return ctx;
}

test('queueTracksForPlaylist resolves activeTrack and queue tracks in upNext mode', () => {
  const ctx = createMockAppCtx();
  const tracks = ctx.queueTracksForPlaylist();
  assert.equal(tracks.length, 3);
  assert.deepEqual(tracks.map((t) => t.id), ['active-1', 'queue-2', 'queue-3']);
});

test('queueTracksForPlaylist resolves continuousQueue when continuousQueueEnabled', () => {
  const ctx = createMockAppCtx();
  ctx.continuousQueueEnabled.value = true;
  ctx.continuousQueue.value = [
    { track: { id: 'prev-0', title: 'Previous' } },
    { track: { id: 'active-1', title: 'Track 1' } },
    { track: { id: 'queue-2', title: 'Track 2' } }
  ];
  const tracks = ctx.queueTracksForPlaylist();
  assert.equal(tracks.length, 3);
  assert.deepEqual(tracks.map((t) => t.id), ['prev-0', 'active-1', 'queue-2']);
});

test('openQueuePlaylistDialog requires signed in user', () => {
  const ctx = createMockAppCtx();
  ctx.authState.value = { signedIn: false };
  let message = '';
  ctx.showShareMessage = (msg) => { message = msg; };

  ctx.openQueuePlaylistDialog();
  assert.equal(ctx.playlistDialogOpen.value, false);
  assert.equal(message, 'Sign in to edit playlists.');
});

test('openQueuePlaylistDialog prevents opening on empty queue', () => {
  const ctx = createMockAppCtx();
  ctx.activeTrack.value = null;
  ctx.queue.value = [];
  let message = '';
  ctx.showShareMessage = (msg) => { message = msg; };

  ctx.openQueuePlaylistDialog();
  assert.equal(ctx.playlistDialogOpen.value, false);
  assert.equal(message, 'Queue is empty.');
});

test('openQueuePlaylistDialog opens dialog in queue mode with all queue tracks', () => {
  const ctx = createMockAppCtx();
  let emittedEvent = '';
  let emittedPayload = null;
  ctx.emitWithReply = async (event, payload) => {
    emittedEvent = event;
    emittedPayload = payload;
    return [{ id: 'PL1', title: 'My Favs', editable: true }];
  };

  ctx.openQueuePlaylistDialog();
  assert.equal(ctx.playlistDialogOpen.value, true);
  assert.equal(ctx.playlistDialogMode.value, 'queue');
  assert.equal(ctx.playlistDialogTracks.value.length, 3);
  assert.equal(emittedEvent, 'music:playlists:editable');
  assert.deepEqual(emittedPayload.videoIds, ['active-1', 'queue-2', 'queue-3']);
  assert.equal(emittedPayload.videoId, '');
});

test('createPlaylistWithTrack in queue mode creates playlist with batch videoIds', async () => {
  const ctx = createMockAppCtx();
  let createdPayload = null;
  let message = '';
  ctx.showShareMessage = (msg) => { message = msg; };
  ctx.emitWithReply = async (event, payload) => {
    if (event === 'music:playlist:create') {
      createdPayload = payload;
      return { id: 'new-pl-id', title: payload.title };
    }
  };

  ctx.openQueuePlaylistDialog();
  ctx.newPlaylistTitle.value = 'Summer Mix';
  await ctx.createPlaylistWithTrack();

  assert.equal(ctx.playlistDialogOpen.value, false);
  assert.deepEqual(createdPayload, {
    title: 'Summer Mix',
    videoIds: ['active-1', 'queue-2', 'queue-3']
  });
  assert.equal(message, 'Created Summer Mix with 3 songs.');
});

test('addTrackToPlaylist in queue mode adds batch videoIds to playlist', async () => {
  const ctx = createMockAppCtx();
  let addPayload = null;
  let message = '';
  ctx.showShareMessage = (msg) => { message = msg; };
  ctx.emitWithReply = async (event, payload) => {
    if (event === 'music:playlist:add-track') {
      addPayload = payload;
      return { id: payload.playlistId };
    }
  };

  ctx.openQueuePlaylistDialog();
  await ctx.addTrackToPlaylist({ id: 'PL1', title: 'My Favs' });

  assert.equal(ctx.playlistDialogOpen.value, false);
  assert.deepEqual(addPayload, {
    playlistId: 'PL1',
    videoIds: ['active-1', 'queue-2', 'queue-3']
  });
  assert.equal(message, 'Added 3 songs to My Favs.');
});

test('createPlaylistMutations bridge creates playlist with multiple videoIds', async () => {
  let createdWith = null;
  const mockYt = {
    playlist: {
      create: async (title, ids) => {
        createdWith = { title, ids };
        return { success: true, playlist_id: 'PL-created-123' };
      }
    }
  };

  const mutations = createPlaylistMutations({
    ensureSignedIn: async () => mockYt,
    refreshBrowserAuth: async () => {}
  });

  const registeredHandlers = {};
  const mockSocket = {
    on: (event, handler) => { registeredHandlers[event] = handler; }
  };
  mutations.register(mockSocket, (err) => err.message);

  let replyData = null;
  await registeredHandlers['music:playlist:create']({
    title: 'My Roadtrip',
    videoIds: ['vid-1', 'vid-2', 'vid-3']
  }, (res) => { replyData = res; });

  assert.equal(replyData.ok, true);
  assert.equal(replyData.data.id, 'PL-created-123');
  assert.deepEqual(createdWith, {
    title: 'My Roadtrip',
    ids: ['vid-1', 'vid-2', 'vid-3']
  });
});

test('createPlaylistMutations bridge adds multiple videoIds to playlist', async () => {
  let addedWith = null;
  const mockYt = {
    music: {
      getPlaylist: async (id) => ({ header: { edit_header: {} } })
    },
    playlist: {
      addVideos: async (id, ids) => {
        addedWith = { id, ids };
        return { success: true };
      }
    }
  };

  const mutations = createPlaylistMutations({
    ensureSignedIn: async () => mockYt,
    refreshBrowserAuth: async () => {}
  });

  const registeredHandlers = {};
  const mockSocket = {
    on: (event, handler) => { registeredHandlers[event] = handler; }
  };
  mutations.register(mockSocket, (err) => err.message);

  let replyData = null;
  await registeredHandlers['music:playlist:add-track']({
    playlistId: 'PL-existing-456',
    videoIds: ['vid-10', 'vid-20']
  }, (res) => { replyData = res; });

  assert.equal(replyData.ok, true);
  assert.deepEqual(addedWith, {
    id: 'PL-existing-456',
    ids: ['vid-10', 'vid-20']
  });
});

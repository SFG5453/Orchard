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

import { computed, ref } from 'vue';

function trackName(track) {
  return String(track?.title || 'Track').trim();
}

function playlistName(playlist) {
  return String(playlist?.title || 'playlist').trim();
}

export function installPlaylistActions(ctx) {
  ctx.playlistDialogOpen = ref(false);
  ctx.playlistDialogTrack = ref(null);
  ctx.playlistDialogTracks = ref([]);
  ctx.playlistDialogMode = ref('track');
  ctx.playlistTargets = ref([]);
  ctx.playlistTargetsLoading = ref(false);
  ctx.playlistMutationPending = ref('');
  ctx.playlistMutationError = ref('');
  ctx.newPlaylistTitle = ref('');
  ctx.deletePlaylistDialogOpen = ref(false);
  ctx.playlistDeleteTarget = ref(null);
  ctx.playlistDeletePending = ref(false);
  ctx.playlistDeleteError = ref('');

  ctx.canCreatePlaylist = computed(() => Boolean(
    ctx.newPlaylistTitle.value.trim() &&
    !ctx.playlistMutationPending.value
  ));

  ctx.currentPlaylistId = function currentPlaylistId(detail = ctx.browseDetail.value) {
    const id = String(detail?.browseId || '').trim();
    return id.startsWith('VL') ? id.slice(2) : id;
  };

  ctx.canRemoveTrackFromPlaylist = function canRemoveTrackFromPlaylist(track, detail) {
    return Boolean(track?.id && detail?.kind === 'playlist' && detail?.editable && ctx.currentPlaylistId(detail));
  };

  ctx.queueTracksForPlaylist = function queueTracksForPlaylist() {
    if (ctx.continuousQueueEnabled?.value && ctx.continuousQueue?.value?.length) {
      return ctx.continuousQueue.value.map((entry) => entry.track).filter((t) => t?.id);
    }
    const active = ctx.activeTrack?.value;
    const queued = ctx.queue?.value || [];
    const list = active?.id ? [active, ...queued] : queued;
    return list.filter((t) => t?.id);
  };

  ctx.loadEditablePlaylistTargets = async function loadEditablePlaylistTargets() {
    if (!ctx.socket.value?.connected) return;
    ctx.playlistTargetsLoading.value = true;
    ctx.playlistMutationError.value = '';

    try {
      const isQueue = ctx.playlistDialogMode.value === 'queue';
      const firstTrack = ctx.playlistDialogTracks.value?.[0] || ctx.playlistDialogTrack.value;
      ctx.playlistTargets.value = await ctx.emitWithReply('music:playlists:editable', {
        videoId: isQueue ? '' : (firstTrack?.id || ''),
        videoIds: isQueue ? ctx.playlistDialogTracks.value.map((t) => t.id).filter(Boolean) : [],
        playlists: ctx.userPlaylistItems.value.map((item) => ({
          id: ctx.itemBrowseId(item),
          title: item.title,
          subtitle: item.subtitle || item.itemCount || '',
          thumbnail: item.thumbnail || ''
        }))
      });
    } catch (error) {
      ctx.playlistTargets.value = [];
      ctx.playlistMutationError.value = error.message;
    } finally {
      ctx.playlistTargetsLoading.value = false;
    }
  };

  ctx.openPlaylistDialog = function openPlaylistDialog(track) {
    if (!ctx.authState.value.signedIn) {
      ctx.showShareMessage?.('Sign in to edit playlists.', true);
      return;
    }
    ctx.playlistDialogMode.value = 'track';
    ctx.playlistDialogTrack.value = track;
    ctx.playlistDialogTracks.value = track ? [track] : [];
    ctx.playlistTargets.value = [];
    ctx.playlistMutationError.value = '';
    ctx.newPlaylistTitle.value = '';
    ctx.playlistDialogOpen.value = true;
    void ctx.loadEditablePlaylistTargets();
  };

  ctx.openQueuePlaylistDialog = function openQueuePlaylistDialog() {
    if (!ctx.authState.value.signedIn) {
      ctx.showShareMessage?.('Sign in to edit playlists.', true);
      return;
    }
    const tracks = ctx.queueTracksForPlaylist();
    if (!tracks.length) {
      ctx.showShareMessage?.('Queue is empty.', true);
      return;
    }
    ctx.playlistDialogMode.value = 'queue';
    ctx.playlistDialogTrack.value = tracks[0] || null;
    ctx.playlistDialogTracks.value = tracks;
    ctx.playlistTargets.value = [];
    ctx.playlistMutationError.value = '';
    ctx.newPlaylistTitle.value = '';
    ctx.playlistDialogOpen.value = true;
    void ctx.loadEditablePlaylistTargets();
  };

  ctx.closePlaylistDialog = function closePlaylistDialog() {
    if (ctx.playlistMutationPending.value) return;
    ctx.playlistDialogOpen.value = false;
  };

  ctx.refreshLibraryAfterMutation = async function refreshLibraryAfterMutation(targetId = '') {
    const detail = ctx.browseDetail.value;
    const shouldRefreshDetail = detail?.kind === 'playlist' &&
      ctx.currentPlaylistId(detail) === ctx.currentPlaylistId({ browseId: targetId });
    const detailRefresh = shouldRefreshDetail
      ? ctx.emitWithReply('music:playlist', { browseId: detail.browseId })
        .then((data) => {
          if (ctx.currentPlaylistId(ctx.browseDetail.value) === ctx.currentPlaylistId(detail)) {
            ctx.browseDetail.value = { ...ctx.browseDetail.value, ...data };
          }
        })
        .catch(() => {})
      : Promise.resolve();

    await Promise.all([ctx.loadHomeLibrary(), detailRefresh]);
  };

  ctx.addTrackToPlaylist = async function addTrackToPlaylist(playlist) {
    if (ctx.playlistMutationPending.value) return;

    if (ctx.playlistDialogMode.value === 'queue') {
      const tracks = ctx.playlistDialogTracks.value.filter((t) => t?.id);
      if (!tracks.length || !playlist?.id) return;
      const videoIds = tracks.map((t) => t.id);

      ctx.playlistMutationPending.value = playlist.id;
      ctx.playlistMutationError.value = '';
      try {
        await ctx.emitWithReply('music:playlist:add-track', {
          playlistId: playlist.id,
          videoIds
        });
        ctx.playlistDialogOpen.value = false;
        const count = tracks.length;
        ctx.showShareMessage?.(`Added ${count} ${count === 1 ? 'song' : 'songs'} to ${playlistName(playlist)}.`);
        await ctx.refreshLibraryAfterMutation(playlist.id);
      } catch (error) {
        ctx.playlistMutationError.value = error.message;
      } finally {
        ctx.playlistMutationPending.value = '';
      }
      return;
    }

    const track = ctx.playlistDialogTrack.value;
    if (!track?.id || !playlist?.id || playlist.containsTrack) return;

    ctx.playlistMutationPending.value = playlist.id;
    ctx.playlistMutationError.value = '';
    try {
      await ctx.emitWithReply('music:playlist:add-track', {
        playlistId: playlist.id,
        videoId: track.id
      });
      ctx.playlistDialogOpen.value = false;
      ctx.showShareMessage?.(`Added ${trackName(track)} to ${playlistName(playlist)}.`);
      await ctx.refreshLibraryAfterMutation(playlist.id);
    } catch (error) {
      ctx.playlistMutationError.value = error.message;
    } finally {
      ctx.playlistMutationPending.value = '';
    }
  };

  ctx.createPlaylistWithTrack = async function createPlaylistWithTrack() {
    const title = ctx.newPlaylistTitle.value.trim();
    if (!title || ctx.playlistMutationPending.value) return;

    if (ctx.playlistDialogMode.value === 'queue') {
      const tracks = ctx.playlistDialogTracks.value.filter((t) => t?.id);
      if (!tracks.length) return;
      const videoIds = tracks.map((t) => t.id);

      ctx.playlistMutationPending.value = 'create';
      ctx.playlistMutationError.value = '';
      try {
        const created = await ctx.emitWithReply('music:playlist:create', {
          title,
          videoIds
        });
        ctx.playlistDialogOpen.value = false;
        const count = tracks.length;
        ctx.showShareMessage?.(`Created ${title} with ${count} ${count === 1 ? 'song' : 'songs'}.`);
        await ctx.refreshLibraryAfterMutation(created.id);
        ctx.insertCreatedPlaylist({ ...created, track: tracks[0] });
      } catch (error) {
        ctx.playlistMutationError.value = error.message;
      } finally {
        ctx.playlistMutationPending.value = '';
      }
      return;
    }

    const track = ctx.playlistDialogTrack.value;
    if (!track?.id) return;

    ctx.playlistMutationPending.value = 'create';
    ctx.playlistMutationError.value = '';
    try {
      const created = await ctx.emitWithReply('music:playlist:create', { title, videoId: track.id });
      ctx.playlistDialogOpen.value = false;
      ctx.showShareMessage?.(`Created ${title} with ${trackName(track)}.`);
      await ctx.refreshLibraryAfterMutation(created.id);
      ctx.insertCreatedPlaylist({ ...created, track });
    } catch (error) {
      ctx.playlistMutationError.value = error.message;
    } finally {
      ctx.playlistMutationPending.value = '';
    }
  };

  ctx.insertCreatedPlaylist = function insertCreatedPlaylist({ id, title, track }) {
    const normalizedId = ctx.currentPlaylistId({ browseId: id });
    const count = ctx.playlistDialogMode?.value === 'queue' && ctx.playlistDialogTracks?.value?.length
      ? ctx.playlistDialogTracks.value.length
      : 1;
    const countLabel = `${count} ${count === 1 ? 'song' : 'songs'}`;
    const item = {
      id: null,
      browseId: `VL${normalizedId}`,
      browsePayload: {
        browseId: `VL${normalizedId}`,
        browseEndpointContextSupportedConfigs: {
          browseEndpointContextMusicConfig: { pageType: 'MUSIC_PAGE_TYPE_PLAYLIST' }
        }
      },
      type: 'playlist',
      title,
      subtitle: `Playlist • ${countLabel}`,
      itemCount: countLabel,
      thumbnail: track?.thumbnail || ''
    };
    const library = ctx.homeData.value.library || { sections: [] };
    const sections = [...(library.sections || [])];
    let index = sections.findIndex((section) => /^(library|playlists)$/i.test(section.title || ''));
    if (index < 0) {
      sections.unshift({ key: 'library-playlists', title: 'Library', items: [item] });
    } else {
      sections[index] = {
        ...sections[index],
        items: [item, ...(sections[index].items || []).filter((entry) => ctx.currentPlaylistId(entry) !== normalizedId)]
      };
    }
    ctx.homeData.value = { ...ctx.homeData.value, library: { ...library, sections } };
  };

  ctx.removePlaylistFromLibraryState = function removePlaylistFromLibraryState(targetId) {
    const id = ctx.currentPlaylistId({ browseId: targetId });
    const removeFromFeed = (feed = { sections: [] }) => ({
      ...feed,
      sections: (feed.sections || []).map((section) => ({
        ...section,
        items: (section.items || []).filter((item) => ctx.currentPlaylistId(item) !== id)
      }))
    });
    ctx.homeData.value = {
      home: removeFromFeed(ctx.homeData.value.home),
      library: removeFromFeed(ctx.homeData.value.library)
    };
  };

  ctx.openDeletePlaylistDialog = function openDeletePlaylistDialog(detail) {
    if (detail?.kind !== 'playlist' || !detail.editable) return;
    ctx.playlistDeleteTarget.value = detail;
    ctx.playlistDeleteError.value = '';
    ctx.deletePlaylistDialogOpen.value = true;
  };

  ctx.confirmDeletePlaylist = async function confirmDeletePlaylist() {
    const target = ctx.playlistDeleteTarget.value;
    const id = ctx.currentPlaylistId(target);
    if (!id || ctx.playlistDeletePending.value) return;

    ctx.playlistDeletePending.value = true;
    ctx.playlistDeleteError.value = '';
    try {
      await ctx.emitWithReply('music:playlist:delete', {
        playlistId: id,
        videoId: target.tracks?.[0]?.id || ''
      });
      ctx.deletePlaylistDialogOpen.value = false;
      ctx.resetNavigation('home');
      await ctx.loadHomeLibrary();
      ctx.removePlaylistFromLibraryState(id);
      ctx.showShareMessage?.(`Deleted ${playlistName(target)}.`);
    } catch (error) {
      ctx.playlistDeleteError.value = error.message;
    } finally {
      ctx.playlistDeletePending.value = false;
    }
  };

  ctx.removeTrackFromPlaylist = async function removeTrackFromPlaylist(track, detail) {
    if (!ctx.canRemoveTrackFromPlaylist(track, detail) || ctx.playlistMutationPending.value) return;

    ctx.playlistMutationPending.value = `remove:${track.id}`;
    try {
      await ctx.emitWithReply('music:playlist:remove-track', {
        playlistId: ctx.currentPlaylistId(detail),
        videoId: track.id
      });
      if (ctx.browseDetail.value?.browseId === detail.browseId) {
        ctx.browseDetail.value = {
          ...ctx.browseDetail.value,
          tracks: ctx.browseDetail.value.tracks.filter((item) => item.id !== track.id)
        };
      }
      ctx.showShareMessage?.(`Removed ${trackName(track)} from ${playlistName(detail)}.`);
      await ctx.refreshLibraryAfterMutation(ctx.currentPlaylistId(detail));
    } catch (error) {
      ctx.showShareMessage?.(error.message, true);
    } finally {
      ctx.playlistMutationPending.value = '';
    }
  };
}

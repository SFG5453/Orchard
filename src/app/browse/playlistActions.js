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

  ctx.loadEditablePlaylistTargets = async function loadEditablePlaylistTargets() {
    if (!ctx.socket.value?.connected) return;
    ctx.playlistTargetsLoading.value = true;
    ctx.playlistMutationError.value = '';

    try {
      ctx.playlistTargets.value = await ctx.emitWithReply('music:playlists:editable', {
        videoId: ctx.playlistDialogTrack.value?.id || '',
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
    ctx.playlistDialogTrack.value = track;
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
    const track = ctx.playlistDialogTrack.value;
    if (!track?.id || !playlist?.id || playlist.containsTrack || ctx.playlistMutationPending.value) return;

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
    const track = ctx.playlistDialogTrack.value;
    const title = ctx.newPlaylistTitle.value.trim();
    if (!track?.id || !title || ctx.playlistMutationPending.value) return;

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
      subtitle: 'Playlist • 1 song',
      itemCount: '1 song',
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

import { computed, nextTick, ref } from 'vue';
import { readPinnedTracks, writePinnedTracks } from './pinsPersistence.js';

function stopMenuEvent(event) {
  event?.preventDefault?.();
  event?.stopPropagation?.();
}

function trackLabel(track) {
  return String(track?.title || 'Track').trim();
}

export function installSongActions(ctx) {
  ctx.pinnedTracks = ref(readPinnedTracks());
  ctx.queueDragIndex = ref(null);
  ctx.songActionMenu = ref({
    open: false,
    x: 0,
    y: 0,
    track: null,
    detail: null
  });

  ctx.pinnedTrackIds = computed(() => new Set(ctx.pinnedTracks.value.map((track) => track.id)));

  ctx.isTrackPinned = function isTrackPinned(track) {
    return Boolean(track?.id && ctx.pinnedTrackIds.value.has(track.id));
  };

  ctx.closeSongActionMenu = function closeSongActionMenu() {
    ctx.songActionMenu.value = { ...ctx.songActionMenu.value, open: false };
  };

  ctx.openSongActionMenu = function openSongActionMenu(track, event, detail = null) {
    stopMenuEvent(event);
    if (!ctx.isPlayableTrack(track)) {
      ctx.showShareMessage?.('Song actions are available for tracks.', true);
      return;
    }

    ctx.songActionMenu.value = {
      open: true,
      x: Number(event?.clientX) || window.innerWidth / 2,
      y: Number(event?.clientY) || window.innerHeight / 2,
      track,
      detail
    };
  };

  ctx.onSongActionKeydown = function onSongActionKeydown(event, track, detail = null) {
    if (event.key !== 'ContextMenu' && !(event.shiftKey && event.key === 'F10')) return;
    const bounds = event.currentTarget?.getBoundingClientRect?.();
    ctx.openSongActionMenu(track, {
      clientX: bounds ? bounds.left + Math.min(bounds.width, 220) : window.innerWidth / 2,
      clientY: bounds ? bounds.top + Math.min(bounds.height, 36) : window.innerHeight / 2,
      preventDefault: () => event.preventDefault(),
      stopPropagation: () => event.stopPropagation()
    }, detail);
  };

  const playBrowseTrackFromKeyboard = ctx.onBrowseTrackRowKeydown;
  ctx.onBrowseTrackRowKeydown = function onBrowseTrackRowKeydown(event, track) {
    if (event.key === 'ContextMenu' || (event.shiftKey && event.key === 'F10')) {
      ctx.onSongActionKeydown(event, track, ctx.browseDetail.value);
      return;
    }
    playBrowseTrackFromKeyboard(event, track);
  };

  // Preserve the existing template call sites while upgrading right-click from
  // an immediate share operation to the full song action menu.
  ctx.shareTrackSongLink = ctx.openSongActionMenu;

  ctx.persistPins = function persistPins() {
    writePinnedTracks(ctx.pinnedTracks.value);
  };

  ctx.togglePinnedTrack = function togglePinnedTrack(track) {
    if (!ctx.isPlayableTrack(track)) return;

    if (ctx.isTrackPinned(track)) {
      ctx.pinnedTracks.value = ctx.pinnedTracks.value.filter((item) => item.id !== track.id);
      ctx.showShareMessage?.(`Unpinned ${trackLabel(track)}.`);
    } else {
      ctx.pinnedTracks.value = [track, ...ctx.pinnedTracks.value.filter((item) => item.id !== track.id)];
      ctx.showShareMessage?.(`Pinned ${trackLabel(track)}.`);
    }
    ctx.persistPins();
  };

  ctx.showPins = function showPins() {
    if (!ctx.authState.value.signedIn) {
      ctx.selectView('home');
      return;
    }
    ctx.navigateToView('pins');
    ctx.errorMessage.value = '';
    ctx.warningMessage.value = '';
  };

  ctx.syncManualQueueOrder = function syncManualQueueOrder() {
    if (ctx.shuffleEnabled.value) ctx.shuffleSourceQueue.value = [...ctx.queue.value];
    ctx.clearNextPreload();
    void nextTick(() => ctx.preloadNextTrack());
  };

  ctx.playTrackNext = function playTrackNext(track) {
    if (!ctx.isPlayableTrack(track) || track.id === ctx.activeTrack.value?.id) return;
    if (ctx.requestListeningPartyHostControl?.({ action: 'play-next', track })) return;
    ctx.queue.value = [track, ...ctx.queue.value.filter((item) => item.id !== track.id)].slice(0, 100);
    ctx.syncManualQueueOrder();
    ctx.showShareMessage?.(`${trackLabel(track)} will play next.`);
  };

  ctx.addTrackToQueue = function addTrackToQueue(track) {
    if (!ctx.isPlayableTrack(track) || track.id === ctx.activeTrack.value?.id) return;
    if (ctx.requestListeningPartyHostControl?.({ action: 'add-queue', track })) return;
    if (ctx.queue.value.some((item) => item.id === track.id)) {
      ctx.showShareMessage?.(`${trackLabel(track)} is already in the queue.`);
      return;
    }
    ctx.queue.value = [...ctx.queue.value, track].slice(0, 100);
    ctx.syncManualQueueOrder();
    ctx.showShareMessage?.(`Added ${trackLabel(track)} to the queue.`);
  };

  ctx.removeQueueTrack = function removeQueueTrack(index) {
    const track = ctx.queue.value[index];
    if (!track) return;
    if (ctx.requestListeningPartyHostControl?.({ action: 'remove-queue', index })) return;
    ctx.queue.value = ctx.queue.value.filter((_, itemIndex) => itemIndex !== index);
    ctx.syncManualQueueOrder();
    ctx.showShareMessage?.(`Removed ${trackLabel(track)} from the queue.`);
  };

  ctx.clearQueue = function clearQueue() {
    if (!ctx.queue.value.length) return;
    if (ctx.requestListeningPartyHostControl?.({ action: 'clear-queue' })) return;
    ctx.queue.value = [];
    ctx.shuffleSourceQueue.value = [];
    ctx.playbackPlaylistContext.value = null;
    ctx.autoplaySuppressedTrackId = ctx.activeTrack.value?.id || '';
    ctx.autoplayRequest += 1;
    ctx.clearNextPreload();
    ctx.showShareMessage?.('Cleared the queue.');
  };

  ctx.moveQueueTrack = function moveQueueTrack(fromIndex, toIndex) {
    if (fromIndex === toIndex || fromIndex < 0 || toIndex < 0) return;
    if (ctx.requestListeningPartyHostControl?.({ action: 'move-queue', fromIndex, toIndex })) return;
    const queue = [...ctx.queue.value];
    const [track] = queue.splice(fromIndex, 1);
    if (!track) return;
    queue.splice(toIndex, 0, track);
    ctx.queue.value = queue;
    ctx.syncManualQueueOrder();
  };

  ctx.onQueueDragStart = function onQueueDragStart(event, index) {
    ctx.queueDragIndex.value = index;
    event.dataTransfer.effectAllowed = 'move';
    event.dataTransfer.setData('text/plain', String(index));
  };

  ctx.onQueueDrop = function onQueueDrop(event, index) {
    const storedIndex = Number(event.dataTransfer.getData('text/plain'));
    const fromIndex = Number.isInteger(storedIndex) ? storedIndex : ctx.queueDragIndex.value;
    ctx.moveQueueTrack(fromIndex, index);
    ctx.queueDragIndex.value = null;
  };

  ctx.canOpenSongAlbum = function canOpenSongAlbum(track) {
    return Boolean(track?.albumId || track?.futureAlbumId);
  };

  ctx.canOpenSongArtist = function canOpenSongArtist(track, detail = null) {
    return Boolean(ctx.trackArtistLinks(track, detail).length || ctx.trackArtistLabel(track, detail));
  };

  ctx.openSongAlbum = function openSongAlbum(track) {
    ctx.openBrowseTrackAlbum(track);
  };

  ctx.openSongArtist = function openSongArtist(track, detail = null) {
    const artist = ctx.trackArtistLinks(track, detail)[0];
    if (artist?.browseId) {
      ctx.openBrowseTrackArtist(track, artist);
      return;
    }

    const artistName = artist?.name || ctx.trackArtistLabel(track, detail);
    if (!artistName) return;
    ctx.query.value = artistName;
    ctx.selectedFilter.value = 'artists';
    void ctx.runSearch();
  };

  ctx.runSongAction = function runSongAction(action) {
    const { track, detail } = ctx.songActionMenu.value;
    ctx.closeSongActionMenu();
    if (!track) return;

    if (action === 'play-next') ctx.playTrackNext(track);
    else if (action === 'add-queue') ctx.addTrackToQueue(track);
    else if (action === 'smart-queue') void ctx.buildSmartQueueFromSeed(track);
    else if (action === 'pin') ctx.togglePinnedTrack(track);
    else if (action === 'playlist') ctx.openPlaylistDialog(track);
    else if (action === 'remove-playlist') void ctx.removeTrackFromPlaylist(track, detail);
    else if (action === 'share') void ctx.shareSongLinkPayload(ctx.songLinkPayloadForTrack(track, detail), trackLabel(track));
    else if (action === 'artist') ctx.openSongArtist(track, detail);
    else if (action === 'album') ctx.openSongAlbum(track);
  };
}

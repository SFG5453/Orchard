import { computed, ref, watch } from 'vue';
import { ListeningPartyClient } from './listeningPartyClient.js';

const PARTY_SYNC_INTERVAL_MS = 2500;

function sameTrackOrder(left = [], right = []) {
  return left.length === right.length && left.every((track, index) => track?.id === right[index]?.id);
}

async function copyText(value) {
  const text = String(value || '').trim();
  if (!text) return false;
  if (window.orchardClipboard?.writeText) {
    await window.orchardClipboard.writeText(text);
    return true;
  }
  if (navigator.clipboard?.writeText) {
    await navigator.clipboard.writeText(text);
    return true;
  }
  const textarea = document.createElement('textarea');
  textarea.value = text;
  textarea.setAttribute('readonly', '');
  textarea.style.position = 'fixed';
  textarea.style.opacity = '0';
  document.body.appendChild(textarea);
  textarea.select();
  const copied = document.execCommand('copy');
  textarea.remove();
  if (!copied) throw new Error('Clipboard access is unavailable.');
  return true;
}

export function installListeningPartyActions(ctx) {
  ctx.listeningParty = ref({
    status: 'idle',
    room: null,
    participant: null,
    peers: [],
    error: '',
    lastState: null
  });
  ctx.listeningPartyIsHost = computed(() => ctx.listeningParty.value.participant?.role === 'host');
  ctx.listeningPartyShareUrl = computed(() => ctx.listeningParty.value.room?.shareUrl || '');
  ctx.listeningPartyInviteCopied = ref(false);
  ctx.listeningPartyInviteUrl = computed(() => {
    const code = ctx.listeningParty.value.room?.id;
    if (!code) return '';
    if (window.location.origin && !window.location.origin.startsWith('file:')) {
      const url = new URL(window.location.href);
      url.searchParams.set('party', code);
      return url.toString();
    }
    return ctx.listeningPartyShareUrl.value || code;
  });
  ctx.listeningPartyServiceUrl = import.meta.env.VITE_ORCHARD_PARTY_URL || 'https://party.sfg545.dev';
  ctx.listeningPartyClient = null;
  ctx.listeningPartySyncTimer = 0;
  ctx.applyingListeningPartyState = false;

  ctx.createListeningParty = async function createListeningParty(options = {}) {
    return ctx.startListeningPartyClient((client) => client.createRoom(options));
  };

  ctx.joinListeningParty = async function joinListeningParty(roomId, options = {}) {
    return ctx.startListeningPartyClient((client) => client.joinRoom(roomId, options));
  };

  ctx.leaveListeningParty = function leaveListeningParty(options = {}) {
    window.clearInterval(ctx.listeningPartySyncTimer);
    ctx.listeningPartySyncTimer = 0;
    if (ctx.listeningPartyIsHost.value && options.closeRoom !== false) ctx.listeningPartyClient?.closeRoom();
    ctx.listeningPartyClient?.disconnect();
    ctx.listeningPartyClient = null;
    ctx.listeningParty.value = { status: 'idle', room: null, participant: null, peers: [], error: '', lastState: null };
  };

  ctx.startListeningPartyClient = async function startListeningPartyClient(starter) {
    ctx.leaveListeningParty({ closeRoom: true });
    const client = new ListeningPartyClient({ serviceUrl: ctx.listeningPartyServiceUrl });
    ctx.listeningPartyClient = client;
    ctx.bindListeningPartyClient(client);
    ctx.patchListeningParty({ status: 'connecting', error: '' });
    try {
      const data = await starter(client);
      ctx.patchListeningParty({ room: data.room, participant: data.participant, status: client.status });
      ctx.rightPanelMode.value = 'party';
      ctx.startListeningPartySyncClock();
      return data;
    } catch (error) {
      if (ctx.listeningPartyClient === client) ctx.leaveListeningParty({ closeRoom: false });
      throw error;
    }
  };

  ctx.bindListeningPartyClient = function bindListeningPartyClient(client) {
    client.addEventListener('status', (event) => ctx.patchListeningParty({ status: event.detail.status }));
    client.addEventListener('peers', (event) => ctx.patchListeningParty({ peers: event.detail.peers || [] }));
    client.addEventListener('welcome', (event) => {
      ctx.patchListeningParty({ room: event.detail.room, lastState: event.detail.lastState || null });
      if (!ctx.listeningPartyIsHost.value && event.detail.lastState?.payload) {
        void ctx.applyListeningPartyState(event.detail.lastState.payload);
      }
    });
    client.addEventListener('party:state', (event) => {
      if (!ctx.listeningPartyIsHost.value) void ctx.applyListeningPartyState(event.detail.payload);
    });
    client.addEventListener('party:update', (event) => {
      if (!ctx.listeningPartyIsHost.value) void ctx.applyListeningPartyState(event.detail.payload);
    });
    client.addEventListener('party:request', (event) => {
      if (ctx.listeningPartyIsHost.value) void ctx.handleListeningPartyRequest(event.detail.payload, event.detail.from);
    });
    client.addEventListener('party:host-changed', (event) => {
      const current = ctx.listeningParty.value;
      const participant = current.participant?.id === event.detail.hostId
        ? { ...current.participant, role: 'host' }
        : current.participant?.role === 'host'
          ? { ...current.participant, role: 'guest' }
          : current.participant;
      ctx.patchListeningParty({ participant });
      if (participant?.role === 'host') {
        ctx.showShareMessage?.('You are now hosting the listening party.');
        ctx.broadcastListeningPartyState('host-transfer');
      }
    });
    client.addEventListener('party:closed', () => ctx.leaveListeningParty({ closeRoom: false }));
    client.addEventListener('error', (event) => ctx.patchListeningParty({ error: event.detail.error || 'Listening party failed.' }));
  };

  ctx.patchListeningParty = function patchListeningParty(patch) {
    ctx.listeningParty.value = { ...ctx.listeningParty.value, ...patch };
  };

  ctx.copyListeningPartyInviteUrl = async function copyListeningPartyInviteUrl() {
    if (!ctx.listeningPartyInviteUrl.value) return;
    await copyText(ctx.listeningPartyInviteUrl.value);
    ctx.listeningPartyInviteCopied.value = true;
    ctx.showShareMessage?.('Copied listening party invite link.');
    window.setTimeout(() => {
      ctx.listeningPartyInviteCopied.value = false;
    }, 2000);
  };

  ctx.startListeningPartySyncClock = function startListeningPartySyncClock() {
    window.clearInterval(ctx.listeningPartySyncTimer);
    ctx.listeningPartySyncTimer = window.setInterval(() => {
      if (ctx.listeningPartyIsHost.value) ctx.broadcastListeningPartyState('clock');
    }, PARTY_SYNC_INTERVAL_MS);
  };

  ctx.currentListeningPartyState = function currentListeningPartyState(reason = 'manual') {
    const media = ctx.currentPlaybackElement?.();
    const currentTime = Number(media?.currentTime ?? ctx.currentTime.value) || 0;
    return {
      reason,
      track: ctx.activeTrack.value,
      queue: ctx.queue.value,
      mediaKind: ctx.activeMediaKind.value,
      isPlaying: ctx.isPlaying.value,
      currentTime,
      duration: ctx.duration.value,
      sentAt: Date.now()
    };
  };

  ctx.shouldRequestListeningPartyHostControl = function shouldRequestListeningPartyHostControl() {
    return ctx.listeningParty.value?.status === 'connected' &&
      !ctx.listeningPartyIsHost.value &&
      !ctx.applyingListeningPartyState;
  };

  ctx.listeningPartyPlaybackOptions = function listeningPartyPlaybackOptions(options = {}) {
    const payload = {};
    if (options.mediaKind) payload.mediaKind = options.mediaKind;
    if (Array.isArray(options.queueSource)) payload.queueSource = options.queueSource.filter(ctx.isPlayableTrack).slice(0, 100);
    if (typeof options.queueAlreadyShuffled === 'boolean') payload.queueAlreadyShuffled = options.queueAlreadyShuffled;
    if (typeof options.resetHistory === 'boolean') payload.resetHistory = options.resetHistory;
    return payload;
  };

  ctx.requestListeningPartyHostControl = function requestListeningPartyHostControl(payload = {}) {
    if (!ctx.shouldRequestListeningPartyHostControl()) return false;
    ctx.sendListeningPartyRequest(payload);
    ctx.showShareMessage?.('Sent to the listening party host.');
    return true;
  };

  ctx.broadcastListeningPartyState = function broadcastListeningPartyState(reason = 'manual') {
    if (!ctx.listeningPartyClient || !ctx.listeningPartyIsHost.value || ctx.applyingListeningPartyState) return;
    const state = ctx.currentListeningPartyState(reason);
    ctx.patchListeningParty({ lastState: { payload: state, updatedAt: Date.now() } });
    ctx.listeningPartyClient.broadcast('party:state', state);
  };

  ctx.sendListeningPartyRequest = function sendListeningPartyRequest(payload = {}) {
    if (ctx.listeningPartyIsHost.value) return ctx.handleListeningPartyRequest(payload, ctx.listeningParty.value.participant?.id);
    ctx.listeningPartyClient?.requestHost(payload);
  };

  ctx.handleListeningPartyRequest = async function handleListeningPartyRequest(payload = {}) {
    if (payload.action === 'seek') ctx.seek(payload.currentTime);
    if (payload.action === 'play') await ctx.ensureListeningPartyPlaying(true);
    if (payload.action === 'pause') await ctx.ensureListeningPartyPlaying(false);
    if (payload.action === 'state' && payload.state) await ctx.applyListeningPartyState(payload.state);
    if (payload.action === 'play-track' && ctx.isPlayableTrack(payload.track)) {
      await ctx.playTrack(payload.track, {
        ...ctx.listeningPartyPlaybackOptions(payload.options),
        sessionAction: 'party-request'
      });
    }
    if (payload.action === 'next') await ctx.playNext({ skipRepeatOne: true, fromListeningPartyRequest: true });
    if (payload.action === 'previous') ctx.playPrevious({ fromListeningPartyRequest: true });
    if (payload.action === 'play-next') ctx.playTrackNext(payload.track);
    if (payload.action === 'add-queue') ctx.addTrackToQueue(payload.track);
    if (payload.action === 'remove-queue') ctx.removeQueueTrack(Number(payload.index));
    if (payload.action === 'clear-queue') ctx.clearQueue();
    if (payload.action === 'move-queue') ctx.moveQueueTrack(Number(payload.fromIndex), Number(payload.toIndex));
    if (payload.action === 'toggle-shuffle') ctx.toggleShuffle({ fromListeningPartyRequest: true });
    if (payload.action === 'cycle-repeat') ctx.cycleRepeatMode({ fromListeningPartyRequest: true });
    ctx.broadcastListeningPartyState(`request:${payload.action || 'state'}`);
  };

  ctx.applyListeningPartyState = async function applyListeningPartyState(state = {}) {
    if (!state.track?.id) return;
    ctx.applyingListeningPartyState = true;
    try {
      const drift = (Date.now() - Number(state.sentAt || Date.now())) / 1000;
      const targetTime = Math.max(0, Number(state.currentTime || 0) + (state.isPlaying ? drift : 0));
      if (ctx.activeTrack.value?.id !== state.track.id) {
        await ctx.playTrack(state.track, {
          mediaKind: state.mediaKind || 'audio',
          queueSource: [state.track, ...(state.queue || [])],
          listeningPartySync: true,
          skipHistory: true
        });
      }
      const nextQueue = Array.isArray(state.queue) ? state.queue.filter(ctx.isPlayableTrack).slice(0, 100) : [];
      if (!sameTrackOrder(ctx.queue.value, nextQueue)) {
        ctx.queue.value = nextQueue;
        ctx.syncManualQueueOrder?.();
      }
      if (Math.abs((ctx.currentPlaybackElement?.()?.currentTime || 0) - targetTime) > 1.25) ctx.seek(targetTime);
      await ctx.ensureListeningPartyPlaying(Boolean(state.isPlaying));
    } finally {
      window.setTimeout(() => {
        ctx.applyingListeningPartyState = false;
      }, 250);
    }
  };

  ctx.ensureListeningPartyPlaying = async function ensureListeningPartyPlaying(playing) {
    const media = ctx.currentPlaybackElement?.();
    if (!media?.src) return;
    if (playing && media.paused) await media.play().catch((error) => { ctx.playbackError.value = error.message; });
    if (!playing && !media.paused) media.pause();
  };

  watch([ctx.activeTrack, ctx.queue, ctx.isPlaying], () => {
    if (ctx.listeningPartyIsHost.value) ctx.broadcastListeningPartyState('playback');
  }, { deep: true });

  watch(() => ctx.listeningParty.value.status, (status) => {
    if (status !== 'connected' && ctx.rightPanelMode.value === 'party') ctx.rightPanelMode.value = 'queue';
  });

  window.addEventListener('beforeunload', () => ctx.leaveListeningParty({ closeRoom: false }));
}

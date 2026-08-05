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

import { fetchBatchCloudAnalysis } from '../../services/cloudAnalysisSync.js';

const CONNECT_SYNC_INTERVAL_MS = 500;

function safeTrack(track = {}) {
  return {
    id: track.id || '',
    title: track.title || '',
    artist: track.artist || track.subtitle || track.artists?.[0] || '',
    album: track.album || '',
    thumbnail: track.thumbnail || ''
  };
}

function connectTrack(track = {}) {
  return {
    ...safeTrack(track),
    playbackItem: {
      ...track,
      artist: track.artist || track.artists?.[0] || track.subtitle || '',
      artists: track.artists?.length
        ? track.artists
        : [track.artist || track.subtitle].filter(Boolean)
    }
  };
}

function playableSearchItems(result = {}) {
  return (result.sections || [])
    .flatMap((section) => section.items || [])
    .filter((item) => item?.id && (item.type === 'song' || item.type === 'video'))
    .slice(0, 20)
    .map(connectTrack);
}

function mergeConnectState(ctx, state = {}) {
  ctx.orchardConnect.value = {
    ...ctx.orchardConnect.value,
    ...state,
    pending: state.pending || ctx.orchardConnect.value.pending || [],
    devices: state.devices || ctx.orchardConnect.value.devices || []
  };
}

export function installConnectActions(ctx) {
  let lastConnectSyncAt = 0;

  ctx.connectSnapshot = function connectSnapshot() {
    const track = ctx.activeTrack.value;
    return {
      status: ctx.socketState.value,
      protocolVersion: 3,
      track: track
        ? {
          ...safeTrack(track),
          artwork: ctx.nowArtworkImage.value || ctx.trackCover(track),
          animatedArtwork: ctx.nowArtworkVideo.value || '',
          animatedArtworkVertical: ctx.enhancedArtwork?.value?.videoUrlVertical || ctx.nowArtworkVideo.value || '',
          bpm: track.bpm || track.analysis?.bpm || 0,
          musicalKey: track.musicalKey || track.key || track.analysis?.key || ''
        }
        : null,
      playback: {
        isPlaying: ctx.isPlaying.value,
        buffering: ctx.buffering.value,
        currentTime: ctx.currentTime.value,
        duration: ctx.duration.value || track?.durationSeconds || 0,
        volume: ctx.volume.value,
        shuffle: Boolean(ctx.shuffleEnabled?.value),
        repeatMode: ctx.repeatMode?.value || 'off'
      },
      lyrics: {
        status: ctx.lyricsState.value.status,
        mode: ctx.lyricsState.value.mode,
        lines: (ctx.lyricsState.value.lines || []).slice(0, 120)
      },
      queue: ctx.queue.value.slice(0, 30).map(safeTrack),
      audioEngine: {
        config: ctx.audioEngineConfig?.value || {},
        activePreset: ctx.audioEngineActivePreset?.value || 'flat',
        presets: ctx.audioEnginePresets || [],
        manualEqEnabled: Boolean(ctx.audioEngineConfig?.value?.eqEnabled),
        autoEqEnabled: Boolean(ctx.audioEngineConfig?.value?.autoEqEnabled)
      }
    };
  };

  ctx.syncConnectState = function syncConnectState() {
    window.clearTimeout(ctx.orchardConnectSyncTimer);
    ctx.orchardConnectSyncTimer = 0;
    lastConnectSyncAt = Date.now();
    if (!ctx.socket.value?.connected) return;
    ctx.socket.value.emit('connect:desktop-state', ctx.connectSnapshot());
  };

  ctx.queueConnectSync = function queueConnectSync() {
    if (!ctx.socket.value?.connected) return;
    if (ctx.orchardConnectSyncTimer) return;

    const elapsed = Date.now() - lastConnectSyncAt;
    const delay = Math.max(0, CONNECT_SYNC_INTERVAL_MS - elapsed);
    ctx.orchardConnectSyncTimer = window.setTimeout(ctx.syncConnectState, delay);
  };

  ctx.loadOrchardConnectInfo = async function loadOrchardConnectInfo({ refresh = false } = {}) {
    if (!ctx.socket.value?.connected) return;
    const event = refresh ? 'connect:pairing-refresh' : 'connect:pairing-info';
    const data = await ctx.emitWithReply(event);
    mergeConnectState(ctx, refresh
      ? {
        pairUrl: data.appUrl || data.url,
        appPairUrl: data.appUrl || data.url,
        webPairUrl: data.webUrl || '',
        qrSvg: data.qrSvg,
        expiresAt: data.expiresAt
      }
      : data);
  };

  ctx.approveOrchardConnectPairing = async function approveOrchardConnectPairing(id) {
    if (!ctx.socket.value?.connected || !id) return;
    await ctx.emitWithReply('connect:pairing-approve', { id });
    ctx.orchardConnectPairingMessage.value = 'Phone approved.';
  };

  ctx.rejectOrchardConnectPairing = async function rejectOrchardConnectPairing(id) {
    if (!ctx.socket.value?.connected || !id) return;
    await ctx.emitWithReply('connect:pairing-reject', { id });
    ctx.orchardConnectPairingMessage.value = 'Pairing rejected.';
  };

  ctx.revokeOrchardConnectDevice = async function revokeOrchardConnectDevice(id) {
    if (!ctx.socket.value?.connected || !id) return;
    await ctx.emitWithReply('connect:device-revoke', { id });
    ctx.orchardConnectPairingMessage.value = 'Phone access revoked.';
  };

  ctx.copyOrchardConnectLink = async function copyOrchardConnectLink() {
    const url = ctx.orchardConnect.value.pairUrl;
    if (!url || !navigator.clipboard) return;
    await navigator.clipboard.writeText(url);
    ctx.orchardConnectPairingMessage.value = 'App link copied.';
  };

  ctx.copyOrchardConnectWebLink = async function copyOrchardConnectWebLink() {
    const url = ctx.orchardConnect.value.webPairUrl;
    if (!url || !navigator.clipboard) return;
    await navigator.clipboard.writeText(url);
    ctx.orchardConnectPairingMessage.value = 'Camera link copied.';
  };

  ctx.handleConnectCommand = function handleConnectCommand({ command = {} } = {}) {
    const type = command.type;
    if (type === 'play-pause') ctx.togglePlayback();
    else if (type === 'play' && !ctx.isPlaying.value) ctx.togglePlayback();
    else if (type === 'pause' && ctx.isPlaying.value) ctx.togglePlayback();
    else if (type === 'next') void ctx.playNext({ skipRepeatOne: true });
    else if (type === 'previous') ctx.playPrevious();
    else if (type === 'volume') ctx.volume.value = Math.max(0, Math.min(1, Number(command.value) || 0));
    else if (type === 'seek') ctx.seek(Number(command.value) || 0);
    else if (type === 'audio-engine-preset') ctx.applyAudioEnginePreset(command.value);
    else if (type === 'audio-engine-auto-eq') ctx.setAutoEqEnabled(Boolean(command.value));
    else if (type === 'audio-engine-manual-eq') ctx.setManualEqEnabled(Boolean(command.value));
    else if (type === 'play-queue-index') {
      const index = Number(command.value);
      const track = Number.isInteger(index) ? ctx.queue.value[index] : null;
      if (track) ctx.playTrack(track, { queueSource: ctx.queue.value });
    } else if (type === 'remove-queue-index') {
      const index = Number(command.value);
      if (Number.isInteger(index)) ctx.removeQueueTrack(index);
    } else if (type === 'move-queue-index') {
      const { from, to } = command.value || {};
      if (Number.isInteger(from) && Number.isInteger(to)) ctx.moveQueueTrack(from, to);
    } else if (type === 'clear-upcoming') {
      ctx.clearQueue();
    } else if (type === 'play-next' && command.value) {
      const track = command.value.playbackItem || command.value;
      ctx.playTrackNext(track);
    } else if (type === 'add-to-queue' && command.value) {
      const track = command.value.playbackItem || command.value;
      ctx.addTrackToQueue(track);
    } else if (type === 'toggle-shuffle') {
      ctx.toggleShuffle();
    } else if (type === 'cycle-repeat') {
      ctx.cycleRepeatMode();
    } else if (type === 'play-track' && command.value?.id) {
      const track = command.value.playbackItem || command.value;
      ctx.playTrack(track, { queueSource: [track, ...ctx.queue.value] });
    }
  };

  ctx.handleConnectSearch = async function handleConnectSearch({ deviceId, query, requestId } = {}) {
    if (!deviceId || !query) return;
    try {
      const result = await ctx.emitWithReply('music:search', { query, filter: 'songs' });
      ctx.socket.value.emit('connect:remote-search-results', {
        deviceId,
        requestId,
        results: playableSearchItems(result)
      });
    } catch {
      ctx.socket.value.emit('connect:remote-search-results', { deviceId, requestId, results: [] });
    }
  };

  ctx.handleConnectLibrary = function handleConnectLibrary({ deviceId, requestId } = {}) {
    if (!deviceId) return;
    const librarySections = ctx.homeData?.value?.library?.sections || [];
    const results = librarySections
      .flatMap((section) => section.items || [])
      .filter((item) => item?.id && (item.type === 'song' || item.type === 'video' || item.type === 'playlist' || item.type === 'album'))
      .map(connectTrack);
    
    ctx.socket.value.emit('connect:remote-library-results', {
      deviceId,
      requestId,
      results
    });
  };

  ctx.handleConnectAnalysis = async function handleConnectAnalysis({ deviceId, trackIds = [], requestId = '' } = {}) {
    if (!deviceId || !Array.isArray(trackIds) || trackIds.length === 0) return;
    const results = [];
    const missingIds = [];
    const getCached = globalThis.orchardAudioAnalysis?.get;

    for (const id of trackIds) {
      if (!id) continue;
      let cached = null;
      if (typeof getCached === 'function') {
        try {
          cached = await getCached(id);
        } catch {}
      }
      if (cached && (cached.bpm || cached.key)) {
        results.push({
          id,
          bpm: cached.bpm || 0,
          musicalKey: cached.key || cached.musicalKey || '',
          keyConfidence: cached.keyConfidence || 0,
          beatConfidence: cached.beatConfidence || 0,
          duration: cached.duration || 0,
          cueIn: cached.cueIn || 0,
          cueOut: cached.cueOut || 0
        });
      } else {
        missingIds.push(id);
      }
    }

    if (missingIds.length > 0) {
      try {
        const cloudMap = await fetchBatchCloudAnalysis(missingIds);
        for (const [id, cloudData] of cloudMap.entries()) {
          results.push({
            id,
            bpm: cloudData.bpm || 0,
            musicalKey: cloudData.key || cloudData.musicalKey || '',
            keyConfidence: cloudData.keyConfidence || 0,
            beatConfidence: cloudData.beatConfidence || 0,
            duration: cloudData.duration || 0,
            cueIn: cloudData.cueIn || 0,
            cueOut: cloudData.cueOut || 0
          });
          void globalThis.orchardAudioAnalysis?.store?.(id, cloudData);
        }
      } catch {}
    }

    ctx.socket.value.emit('connect:remote-analysis-results', {
      deviceId,
      requestId,
      results
    });
  };

  ctx.bindOrchardConnectEvents = function bindOrchardConnectEvents() {
    if (ctx.orchardConnectEventsBound) return;
    ctx.orchardConnectEventsBound = true;

    ctx.socket.value.on('connect:pairing-request', (request) => {
      mergeConnectState(ctx, {
        pending: [
          request,
          ...ctx.orchardConnect.value.pending.filter((item) => item.id !== request.id)
        ]
      });
      ctx.orchardConnectPairingMessage.value = `${request.name || 'Phone'} wants to control Orchard.`;
    });
    ctx.socket.value.on('connect:pairing-state', (state) => mergeConnectState(ctx, state));
    ctx.socket.value.on('connect:remote-command', ctx.handleConnectCommand);
    ctx.socket.value.on('connect:remote-search', (payload) => void ctx.handleConnectSearch(payload));
    ctx.socket.value.on('connect:remote-library', (payload) => void ctx.handleConnectLibrary(payload));
    ctx.socket.value.on('connect:remote-analysis', (payload) => void ctx.handleConnectAnalysis(payload));
  };
}

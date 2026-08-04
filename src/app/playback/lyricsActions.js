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

function setProviderStatus(ctx, providerId, status) {
  ctx.lyricsState.value.providers = ctx.lyricsState.value.providers.map((item) => ({
    ...item,
    status: item.id === providerId ? status : item.status
  }));
}

function readyLyrics(data) {
  return data?.status === 'ready' && data.lines?.length;
}

function finishLyrics(ctx, track, provider, data) {
  ctx.lyricsState.value = {
    trackId: track.id,
    status: 'ready',
    mode: data.mode || '',
    lines: data.lines,
    source: data.source || provider,
    providers: ctx.lyricsState.value.providers
  };
  ctx.queueConnectSync?.();
}

export function installLyricsActions(ctx) {
  ctx.loadLyrics = async function loadLyrics(track) {
    const requestId = ++ctx.lyricsRequest;

    if (!track?.id || !ctx.socket.value?.connected) {
      ctx.lyricsState.value = { trackId: '', status: 'idle', mode: '', lines: [], source: '', providers: [] };
      ctx.queueConnectSync?.();
      return;
    }

    ctx.lyricsState.value = {
      trackId: track.id,
      status: 'loading',
      mode: '',
      lines: [],
      source: '',
      providers: ctx.lyricProviders.map((provider, index) => ({
        ...provider,
        status: index === 0 ? 'loading' : 'pending'
      }))
    };
    ctx.queueConnectSync?.();

    const payload = {
      id: track.id,
      title: track.title,
      artist: track.artist || ctx.activeArtist.value,
      artists: track.artists || [],
      album: track.album || '',
      durationSeconds: track.durationSeconds || ctx.duration.value || 0
    };
    const providers = ctx.lyricProviders.map((item) => item.id);
    let amLyricsFallback = null;

    for (const provider of providers) {
      if (requestId !== ctx.lyricsRequest || ctx.activeTrack.value?.id !== track.id) return;

      setProviderStatus(ctx, provider, 'loading');

      try {
        const data = await ctx.emitWithReply('music:lyrics', { ...payload, provider });
        if (requestId !== ctx.lyricsRequest || ctx.activeTrack.value?.id !== track.id) return;

        if (readyLyrics(data)) {
          setProviderStatus(ctx, provider, 'ready');

          if (provider === 'amlyrics' && data.mode !== 'synced' && providers.includes('lrclib')) {
            amLyricsFallback = { data, provider };
            continue;
          }

          if (provider === 'lrclib' && data.mode !== 'synced' && amLyricsFallback) {
            finishLyrics(ctx, track, amLyricsFallback.provider, amLyricsFallback.data);
            return;
          }

          finishLyrics(ctx, track, provider, data);
          return;
        }
      } catch {
        // A provider request failure is handled the same as an unavailable result.
      }

      setProviderStatus(ctx, provider, 'failed');

      if (provider === 'lrclib' && amLyricsFallback) {
        finishLyrics(ctx, track, amLyricsFallback.provider, amLyricsFallback.data);
        return;
      }
    }

    if (requestId === ctx.lyricsRequest) {
      ctx.lyricsState.value = {
        trackId: track.id,
        status: 'unavailable',
        mode: '',
        lines: [],
        source: '',
        providers: ctx.lyricsState.value.providers
      };
      ctx.queueConnectSync?.();
    }
  };
}

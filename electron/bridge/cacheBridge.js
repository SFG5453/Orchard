// Exposes bounded song-cache operations over the renderer loopback transport.
export function registerCacheBridge({ socket, bridgeError, playback, resolveTrackRequest, normalizeTrackInfo }) {
  socket.on('playback:cache-settings', (payload, reply) => {
    try {
      reply({ ok: true, data: playback.updateSongCacheSettings(payload) });
    } catch (error) {
      reply({ ok: false, error: bridgeError(error) });
    }
  });

  socket.on('playback:cache-list', async (_payload, reply) => {
    try {
      await playback.songCache.hydrateMissingMetadata((videoId) => resolveCacheMetadata(videoId, playback, normalizeTrackInfo));
      reply({ ok: true, data: await playback.songCache.list() });
    } catch (error) {
      reply({ ok: false, error: bridgeError(error) });
    }
  });

  socket.on('playback:cache-remove', async ({ key }, reply) => {
    try {
      reply({ ok: true, data: await playback.songCache.remove(key) });
    } catch (error) {
      reply({ ok: false, error: bridgeError(error) });
    }
  });

  socket.on('playback:cache-clear', async (_payload, reply) => {
    try {
      reply({ ok: true, data: await playback.songCache.clear() });
    } catch (error) {
      reply({ ok: false, error: bridgeError(error) });
    }
  });

  socket.on('playback:cache-prefetch', async ({ tracks = [] }, reply) => {
    try {
      const result = await prefetchTracks(tracks, resolveTrackRequest);
      reply({ ok: true, data: { ...result, cache: await playback.songCache.list() } });
    } catch (error) {
      reply({ ok: false, error: bridgeError(error) });
    }
  });
}

async function resolveCacheMetadata(videoId, playback, normalizeTrackInfo) {
  const { info } = await playback.playbackInfo(videoId, { lowPriority: true });
  if (typeof normalizeTrackInfo === 'function') {
    const track = normalizeTrackInfo(videoId, info);
    return {
      videoId,
      title: track.title,
      artist: track.artist,
      thumbnail: track.thumbnail,
      durationSeconds: track.durationSeconds
    };
  }

  return { videoId };
}

async function prefetchTracks(tracks, resolveTrackRequest) {
  const uniqueTracks = [];
  const seen = new Set();

  for (const track of tracks) {
    const videoId = String(track?.videoId || track?.originalVideoId || '').trim();
    if (!videoId || seen.has(videoId)) continue;
    seen.add(videoId);
    uniqueTracks.push(track);
  }

  const result = { total: uniqueTracks.length, cached: 0, failed: 0, errors: [] };

  for (const track of uniqueTracks) {
    try {
      const resolved = await resolveTrackRequest({ ...track, preload: true, mediaKind: 'audio' });
      await drainLocalStream(resolved.streamUrl);
      result.cached += 1;
    } catch (error) {
      result.failed += 1;
      if (result.errors.length < 3) {
        result.errors.push({
          title: track.title || track.videoId || 'Track',
          error: error.message
        });
      }
    }
  }

  return result;
}

async function drainLocalStream(url) {
  const response = await fetch(url);
  if (!response.ok && response.status !== 206) {
    throw new Error(`Stream returned HTTP ${response.status}`);
  }

  if (!response.body) return;
  const reader = response.body.getReader();
  while (true) {
    const chunk = await reader.read();
    if (chunk.done) break;
  }
}

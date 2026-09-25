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

  socket.on('playback:downloads-list', async (_payload, reply) => {
    try {
      const inventory = await playback.songCache.list();
      reply({ ok: true, data: inventory });
    } catch (error) {
      reply({ ok: false, error: bridgeError(error) });
    }
  });

  socket.on('playback:downloads-add', async ({ tracks = [] }, reply) => {
    try {
      const result = await downloadTracks(tracks, resolveTrackRequest, playback.songCache);
      reply({ ok: true, data: { ...result, inventory: await playback.songCache.list() } });
    } catch (error) {
      reply({ ok: false, error: bridgeError(error) });
    }
  });

  socket.on('playback:downloads-remove', async ({ videoIds = [] }, reply) => {
    try {
      let inventory = await playback.songCache.list();
      for (const videoId of [...new Set(videoIds.map((value) => String(value || '').trim()).filter(Boolean))]) {
        inventory = await playback.songCache.removeDownload(videoId);
      }
      reply({ ok: true, data: inventory });
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

export async function downloadTracks(tracks, resolveTrackRequest, songCache, drainStream = drainLocalStream) {
  const uniqueTracks = [];
  const seen = new Set();

  for (const track of tracks) {
    const videoId = String(track?.videoId || track?.originalVideoId || '').trim();
    if (!videoId || seen.has(videoId)) continue;
    seen.add(videoId);
    uniqueTracks.push({ ...track, videoId });
  }

  const result = { total: uniqueTracks.length, downloaded: 0, failed: 0, errors: [] };
  const errors = new Array(uniqueTracks.length);
  let nextIndex = 0;

  async function worker() {
    while (nextIndex < uniqueTracks.length) {
      const index = nextIndex;
      nextIndex += 1;
      const track = uniqueTracks[index];
      try {
        const existing = await songCache.findDownloaded(track.videoId);
        if (existing) {
          await songCache.pin(existing.sourceVideoId || existing.videoId, downloadMetadata(track));
        } else {
          const resolved = await resolveTrackRequest({
            ...track,
            preload: true,
            mediaKind: 'audio',
            refreshStream: true,
            downloadRequested: true
          });
          await drainStream(resolved.streamUrl);
          await songCache.pin(resolved.youtubeVideoId || track.videoId, downloadMetadata(track));
        }
        result.downloaded += 1;
      } catch (error) {
        result.failed += 1;
        errors[index] = { title: track.title || track.videoId || 'Track', error: error.message };
      }
    }
  }

  await Promise.all(Array.from(
    { length: Math.min(3, uniqueTracks.length) },
    () => worker()
  ));
  result.errors = errors.filter(Boolean).slice(0, 3);

  return result;
}

function downloadMetadata(track = {}) {
  const artistBrowseIds = Array.isArray(track.artistBrowseIds)
    ? track.artistBrowseIds.map((value) => String(value || '').trim()).filter(Boolean)
    : [];
  return {
    videoId: String(track.originalVideoId || track.videoId || '').trim(),
    title: String(track.title || '').trim(),
    artist: String(track.artist || track.artists?.[0] || '').trim(),
    artistId: String(track.artistId || track.artistBrowseId || artistBrowseIds[0] || '').trim(),
    artistBrowseIds,
    album: String(track.album || '').trim(),
    albumId: String(track.albumId || '').trim(),
    collections: Array.isArray(track.downloadCollections) ? track.downloadCollections : [],
    thumbnail: String(track.thumbnail || '').trim(),
    durationSeconds: Number(track.durationSeconds || 0)
  };
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

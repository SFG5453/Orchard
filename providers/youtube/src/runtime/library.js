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

import { collectPlaylistOptions, findPlaylistSetVideoId } from '../catalog/playlistMutations.js';
import { createBrowseRequest, playlistPage } from './catalog.js';
import { resolveAudioVersion } from './playback.js';

function clean(value) {
  return String(value || '').trim();
}

function playlistId(value) {
  const id = clean(value);
  return id.startsWith('VL') ? id.slice(2) : id;
}

function writeError(error, fallback) {
  const message = clean(error?.message) || fallback;
  if (/signed in|sign in|logged_in|login session/i.test(message)) {
    return new Error('Sign in again before editing your library.');
  }
  return error instanceof Error ? error : new Error(message);
}

const maxPlaylistPages = 50;

// Every row's setVideoId in playlist order. Duplicate songs keep separate entries.
async function playlistEntries(request, id) {
  const entries = [];
  let page = playlistPage(await request({ browseId: `VL${id}` }));
  for (let count = 0; ; count += 1) {
    entries.push(...page.tracks.map((track) => clean(track.setVideoId)).filter(Boolean));
    if (!page.continuation || count >= maxPlaylistPages) break;
    const continuation = page.continuation;
    page = playlistPage(await request({ continuation }), entries.length);
    if (page.continuation === continuation) break;
  }
  return [...new Set(entries)];
}

function findLikeStatus(value, seen = new Set(), depth = 0) {
  if (!value || typeof value !== 'object' || depth > 24 || seen.has(value)) return '';
  seen.add(value);
  const status = value.likeButtonRenderer?.likeStatus || value.likeStatusEntity?.likeStatus;
  if (status) return String(status).toUpperCase();
  for (const child of Object.values(value)) {
    const found = findLikeStatus(child, seen, depth + 1);
    if (found) return found;
  }
  return '';
}

// Every write targets the id playback would open. Callers that already know
// it (the playing stream) pass videoId and skip the search round trip.
async function targetVideoId(payload, fetchImpl) {
  const known = clean(payload.videoId);
  if (known) return known;
  const track = payload.track || {};
  if (!/^[\w-]{11}$/.test(clean(track.id))) throw new Error('This track cannot be saved to your library.');
  return clean(await resolveAudioVersion(track, fetchImpl)) || clean(track.id);
}

// youtubei.js sent playlist calls to www.youtube.com with a WEB_REMIX context. The Music
// host can answer the picker with an empty list, so it is only the fallback.
const playlistOrigins = ['https://www.youtube.com', 'https://music.youtube.com'];

async function addToPlaylistOptions(requests, videoId) {
  let answered = false;
  let lastError;
  for (const request of requests) {
    try {
      const data = await request({ videoIds: [videoId], excludeWatchLater: true }, 'playlist/get_add_to_playlist');
      answered = true;
      const unique = new Map();
      collectPlaylistOptions(data).forEach((item) => unique.set(item.id, item));
      if (unique.size) return [...unique.values()];
    } catch (error) {
      lastError = error;
    }
  }
  if (!answered && lastError) throw lastError;
  return [];
}

export function createRuntimeLibrary(fetchImpl = globalThis.fetch) {
  const requestFor = (payload) => createBrowseRequest(payload.session || {}, {}, fetchImpl);
  const playlistRequestsFor = (payload) => playlistOrigins.map((origin) =>
    createBrowseRequest(payload.session || {}, { origin }, fetchImpl));

  return {
    likeStatus: async (payload = {}) => {
      const videoId = await targetVideoId(payload, fetchImpl);
      const data = await requestFor(payload)({ videoId, isAudioOnly: true }, 'next');
      return { videoId, liked: findLikeStatus(data) === 'LIKE' };
    },

    setLike: async (payload = {}) => {
      const videoId = await targetVideoId(payload, fetchImpl);
      const liked = Boolean(payload.liked);
      try {
        await requestFor(payload)({ target: { videoId } }, liked ? 'like/like' : 'like/removelike');
      } catch (error) {
        throw writeError(error, 'Could not update the like.');
      }
      return { videoId, liked };
    },

    playlistTargets: async (payload = {}) => {
      const videoId = await targetVideoId(payload, fetchImpl);
      try {
        return { videoId, playlists: await addToPlaylistOptions(playlistRequestsFor(payload), videoId) };
      } catch (error) {
        throw writeError(error, 'Could not load your playlists.');
      }
    },

    addToPlaylist: async (payload = {}) => {
      const id = playlistId(payload.playlistId);
      if (!id) throw new Error('Choose a playlist first.');
      const videoId = await targetVideoId(payload, fetchImpl);
      const requests = playlistRequestsFor(payload);
      try {
        const options = await addToPlaylistOptions(requests, videoId).catch(() => []);
        const target = options.find((item) => item.id === id);
        if (target?.containsTrack) throw new Error('This song is already in that playlist.');
        if (options.length && !target) throw new Error('This playlist cannot be edited.');
        const data = await requests[0]({
          playlistId: id,
          actions: [{ action: 'ACTION_ADD_VIDEO', addedVideoId: videoId, dedupeOption: 'DEDUPE_OPTION_SKIP' }]
        }, 'browse/edit_playlist');
        if (data.status && data.status !== 'STATUS_SUCCEEDED') throw new Error('YouTube did not add the song.');
      } catch (error) {
        throw writeError(error, 'Could not add the song.');
      }
      return { playlistId: id, videoId, title: clean(payload.title) };
    },

    // Removal targets the row as stored, never its audio version. A playlist may hold
    // the music video itself, and setVideoId picks one entry when a song repeats.
    removeFromPlaylist: async (payload = {}) => {
      const id = playlistId(payload.playlistId);
      const videoId = clean(payload.track?.id);
      if (!id || !videoId) throw new Error('Playlist or track information is missing.');
      const [request] = playlistRequestsFor(payload);
      try {
        let setVideoId = clean(payload.track?.setVideoId);
        if (!setVideoId) {
          setVideoId = findPlaylistSetVideoId(await request({ browseId: `VL${id}` }), videoId);
        }
        if (!setVideoId) throw new Error('This song is not in that playlist anymore.');
        const data = await request({
          playlistId: id,
          actions: [{ action: 'ACTION_REMOVE_VIDEO', removedVideoId: videoId, setVideoId }]
        }, 'browse/edit_playlist');
        if (data.status && data.status !== 'STATUS_SUCCEEDED') throw new Error('YouTube did not remove the song.');
        return { playlistId: id, videoId, setVideoId, title: clean(payload.title) };
      } catch (error) {
        throw writeError(error, 'Could not remove the song.');
      }
    },

    // Moves one occurrence so it sits at toIndex once the move is done.
    movePlaylistItem: async (payload = {}) => {
      const id = playlistId(payload.playlistId);
      const from = Number(payload.fromIndex);
      const to = Number(payload.toIndex);
      if (!id) throw new Error('Playlist information is missing.');
      if (!Number.isInteger(from) || !Number.isInteger(to) || from < 0 || to < 0) {
        throw new Error('Playlist position is invalid.');
      }
      if (from === to) return { playlistId: id, fromIndex: from, toIndex: to };
      const [request] = playlistRequestsFor(payload);
      try {
        const entries = await playlistEntries(request, id);
        if (from >= entries.length || to >= entries.length) {
          throw new Error('The playlist changed. Refresh it and try again.');
        }
        const moved = entries[from];
        const remaining = entries.filter((unused, index) => index !== from);
        const successor = remaining[to];
        const data = await request({
          playlistId: id,
          actions: [{
            action: 'ACTION_MOVE_VIDEO_BEFORE',
            setVideoId: moved,
            ...(successor ? { movedSetVideoIdSuccessor: successor } : {})
          }]
        }, 'browse/edit_playlist');
        if (data.status && data.status !== 'STATUS_SUCCEEDED') throw new Error('YouTube did not move the song.');
        return { playlistId: id, fromIndex: from, toIndex: to };
      } catch (error) {
        throw writeError(error, 'Could not move the song.');
      }
    },

    deletePlaylist: async (payload = {}) => {
      const id = playlistId(payload.playlistId);
      if (!id) throw new Error('Playlist information is missing.');
      try {
        await playlistRequestsFor(payload)[0]({ playlistId: id }, 'playlist/delete');
      } catch (error) {
        throw writeError(error, 'Could not delete the playlist.');
      }
      return { playlistId: id };
    },

    setArtistSubscription: async (payload = {}) => {
      const channelId = clean(payload.channelId);
      if (!channelId) throw new Error('Artist channel ID is required.');
      const subscribed = Boolean(payload.subscribed);
      try {
        await requestFor(payload)({
          channelIds: [channelId],
          params: subscribed ? 'EgIIAhgA' : 'CgIIAhgA'
        }, subscribed ? 'subscription/subscribe' : 'subscription/unsubscribe');
      } catch (error) {
        throw writeError(error, 'Could not update the subscription.');
      }
      return { channelId, subscribed };
    },

    createPlaylist: async (payload = {}) => {
      const title = clean(payload.title);
      if (!title) throw new Error('Enter a playlist name.');
      // A track seeds the playlist; without one it starts empty, like the sidebar's "New Playlist".
      const seeded = Boolean(clean(payload.videoId) || payload.track);
      const videoId = seeded ? await targetVideoId(payload, fetchImpl) : '';
      try {
        const data = await playlistRequestsFor(payload)[0]({
          title,
          privacyStatus: 'PRIVATE',
          videoIds: videoId ? [videoId] : []
        }, 'playlist/create');
        const id = playlistId(data.playlistId);
        if (!id) throw new Error('YouTube did not create the playlist.');
        return { playlistId: id, videoId, title };
      } catch (error) {
        throw writeError(error, 'Could not create the playlist.');
      }
    }
  };
}

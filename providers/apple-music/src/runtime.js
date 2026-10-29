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

// QuickJS entry point. The host signs in, stores tokens and supplies fetch.
import { createAppleMusic } from './index.js';

let session = null;
const appleMusic = createAppleMusic({
  fetchImpl: (...args) => fetch(...args),
  session: () => session
});

function errorPayload(error) {
  return { error: { code: error?.code || 'error', status: error?.status || 0, message: String(error?.message || error) } };
}

const methods = new Map([
  ['session.set', async (payload = {}) => {
    const developerToken = String(payload.developerToken || '');
    session = developerToken ? {
      developerToken,
      userToken: String(payload.userToken || ''),
      storefront: String(payload.storefront || 'us').toLowerCase(),
      origin: String(payload.origin || '')
    } : null;
    return { connected: Boolean(session) };
  }],
  ['storefront.resolve', async (payload = {}) => ({ storefront: await appleMusic.storefront(payload.fallback || 'us') })],
  ['catalog.home', async () => appleMusic.home()],
  ['catalog.home.section', async (payload = {}) => ({ items: await appleMusic.homeSection(payload.browseId) })],
  ['catalog.artist', async (payload = {}) => appleMusic.artistPage(payload.browseId)],
  ['catalog.artist.view', async (payload = {}) => ({ items: await appleMusic.artistView(payload.browseId, payload.params) })],
  ['catalog.artist.match', async (payload = {}) => appleMusic.matchArtist(payload.name)],
  ['catalog.album', async (payload = {}) => appleMusic.albumPage(payload.browseId)],
  ['catalog.playlist', async (payload = {}) => (String(payload.browseId || '').startsWith('am.stations.')
    ? appleMusic.stationPage(payload.browseId)
    : appleMusic.playlistPage(payload.browseId))],
  ['catalog.musicVideo', async (payload = {}) => appleMusic.musicVideoFor(payload.appleId || payload.id)],
  ['catalog.upNext', async (payload = {}) => ({ items: await appleMusic.upNext(payload.seed) })],
  // Hosts parse the reply as an object, so a miss is {} rather than null.
  ['catalog.artwork', async (payload = {}) => (await appleMusic.artwork(payload)) || {}],
  ['catalog.audioAnalysis', async (payload = {}) => appleMusic.audioAnalysis(payload.ids || [])],
  ['catalog.search', async (payload = {}) => appleMusic.search(payload.query, payload.filter)],
  ['library', async () => appleMusic.library()],
  ['library.playlists', async () => ({ items: await appleMusic.libraryPlaylists() })],
  ['library.recent', async () => ({ items: await appleMusic.recentTracks() })],
  ['library.like.status', async (payload = {}) => appleMusic.likeStatus(payload.track)],
  ['library.like.set', async (payload = {}) => appleMusic.setLike(payload.track, Boolean(payload.liked))],
  ['library.playlist.targets', async () => ({ playlists: await appleMusic.playlistTargets() })],
  ['library.playlist.add', async (payload = {}) => appleMusic.addToPlaylist(payload.track, payload.playlistId, payload.title)],
  ['library.playlist.create', async (payload = {}) => appleMusic.createPlaylist(payload.title, payload.track)],
  ['playback.stream', async (payload = {}) => appleMusic.playback.stream(payload.track)],
  ['playback.atmos', async (payload = {}) => appleMusic.playback.atmos(payload.id)],
  ['playback.video', async (payload = {}) => appleMusic.playback.video(payload.id)],
  ['playback.license', async (payload = {}) => appleMusic.playback.license(payload)],
  ['lyrics', async (payload = {}) => (await appleMusic.lyrics(payload)) || {}],
  ['close', async () => {
    session = null;
    return null;
  }]
]);

// Apple errors return as {error} so hosts can tell sign-in problems from outages.
async function invoke(method, payload) {
  const handler = methods.get(String(method || ''));
  if (!handler) throw new Error(`Unknown Apple Music provider method: ${method}`);
  try {
    return await handler(payload);
  } catch (error) {
    if (error?.name === 'AppleMusicError') return errorPayload(error);
    throw error;
  }
}

globalThis.OrchardAppleMusicProvider = Object.freeze({ invoke });

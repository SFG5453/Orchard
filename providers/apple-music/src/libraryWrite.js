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

// Writes to the listener's library: loves, saved songs, and their own playlists.
// Tracks from any service are matched to an Apple catalog song first.

import { AppleMusicError } from './client.js';
import { parseBrowseId } from './ids.js';
import { isLibraryId } from './library.js';
import { findSong } from './match.js';
import { artworkUrl } from './normalize.js';

const PLAYLIST_PAGES = 5;
const COLLECTION_TYPES = new Set(['albums', 'playlists']);

export function createLibraryWrites(client) {
  /** {id, type} as Apple's library endpoints name a song. */
  async function songRef(track = {}) {
    const parsed = parseBrowseId(track.id);
    const ownId = parsed?.type === 'songs' ? parsed.id : '';
    if (ownId.startsWith('i.')) return { id: ownId, type: 'library-songs' };
    const appleId = /^\d+$/.test(String(track.appleId || '')) ? String(track.appleId) : /^\d+$/.test(ownId) ? ownId : '';
    if (appleId) return { id: appleId, type: 'songs' };
    const song = await findSong(client, {
      isrc: track.isrc, title: track.title, artist: track.artist || track.artists?.[0], album: track.album
    });
    if (!song?.id) throw new AppleMusicError(404, 'not_found', 'Apple Music does not have this song.');
    return { id: song.id, type: 'songs' };
  }

  const ratingPath = (ref) => `/v1/me/ratings/${ref.type}/${encodeURIComponent(ref.id)}`;

  async function likeStatus(track) {
    const ref = await songRef(track);
    try {
      const rating = (await client.get(ratingPath(ref))).data?.[0];
      return { liked: rating?.attributes?.value === 1, songId: ref.id };
    } catch (error) {
      if (error instanceof AppleMusicError && error.code === 'not_found') return { liked: false, songId: ref.id };
      throw error;
    }
  }

  /** Loving a song also saves it, so it shows under Songs like a YouTube Music like does. */
  async function setLike(track, liked) {
    const ref = await songRef(track);
    if (!liked) {
      await client.send('DELETE', ratingPath(ref));
      return { liked: false, songId: ref.id };
    }
    await client.send('PUT', ratingPath(ref), { type: 'rating', attributes: { value: 1 } });
    if (ref.type === 'songs') await client.send('POST', `/v1/me/library?ids%5Bsongs%5D=${encodeURIComponent(ref.id)}`);
    return { liked: true, songId: ref.id };
  }

  /** Playlists the listener can add songs to; Apple's API cannot tell whether one already has the song. */
  async function playlistTargets() {
    const { items } = await client.all('/v1/me/library/playlists', { limit: 100 }, PLAYLIST_PAGES);
    return items
      .filter((playlist) => playlist.attributes?.canEdit)
      .map((playlist) => ({
        id: playlist.id,
        title: playlist.attributes.name || '',
        thumbnail: artworkUrl(playlist.attributes.artwork),
        containsTrack: false
      }));
  }

  async function addToPlaylist(track, playlistId, title = '') {
    const ref = await songRef(track);
    await client.send('POST', `/v1/me/library/playlists/${encodeURIComponent(playlistId)}/tracks`, { data: [ref] });
    return { playlistId, title };
  }

  async function createPlaylist(title, track) {
    const name = String(title || '').trim();
    if (!name) throw new AppleMusicError(0, 'bad_title', 'Name the playlist first.');
    const body = { attributes: { name } };
    if (track?.id) body.relationships = { tracks: { data: [await songRef(track)] } };
    const created = (await client.send('POST', '/v1/me/library/playlists', body))?.data?.[0];
    return { playlistId: created?.id || '', title: created?.attributes?.name || name };
  }

  /** {type, id, libraryId} for an album or playlist; library copies are their own library id. */
  async function collectionRef(browseId) {
    const parsed = parseBrowseId(browseId);
    if (!parsed || !COLLECTION_TYPES.has(parsed.type)) {
      throw new AppleMusicError(0, 'bad_id', 'Only Apple Music albums and playlists can be saved.');
    }
    if (isLibraryId(parsed.id)) return { ...parsed, libraryId: parsed.id };
    const path = `/v1/catalog/${client.storefront()}/${parsed.type}/${encodeURIComponent(parsed.id)}`;
    const resource = (await client.get(path, { relate: 'library' }, { personal: true })).data?.[0];
    return { ...parsed, libraryId: resource?.relationships?.library?.data?.[0]?.id || '' };
  }

  /** True or false for a signed-in listener; null when Apple cannot say. */
  async function collectionSaved(browseId) {
    if (!client.userToken()) return null;
    try {
      return Boolean((await collectionRef(browseId)).libraryId);
    } catch {
      return null;
    }
  }

  async function setCollectionSaved(browseId, saved) {
    const ref = await collectionRef(browseId);
    if (saved) {
      if (!ref.libraryId) await client.send('POST', `/v1/me/library?ids%5B${ref.type}%5D=${encodeURIComponent(ref.id)}`);
      return { browseId, saved: true };
    }
    if (!ref.libraryId) return { browseId, saved: false };
    const path = `/v1/me/library/${ref.type}/${encodeURIComponent(ref.libraryId)}`;
    // Apple removes and deletes playlists the same way; never delete the listener's own.
    if (ref.type === 'playlists' && (await client.get(path)).data?.[0]?.attributes?.canEdit) {
      throw new AppleMusicError(0, 'own_playlist', 'Delete your own playlists in Apple Music.');
    }
    await client.send('DELETE', path);
    return { browseId, saved: false };
  }

  return { likeStatus, setLike, playlistTargets, addToPlaylist, createPlaylist, collectionSaved, setCollectionSaved };
}

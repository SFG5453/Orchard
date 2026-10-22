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

import { AppleMusicError } from './client.js';
import { APPLE_MUSIC_SOURCE, parseBrowseId } from './ids.js';
import { isLibraryId } from './library.js';
import { linkNotes, noteTargets } from './notes.js';
import { ARTIST_INCLUDES, album, artworkUrl, items, notes, playlist, releaseInfo, track, yearOf } from './normalize.js';

const SEARCH_TYPES = { songs: 'songs', albums: 'albums', artists: 'artists', playlists: 'playlists' };

function requireId(id, type) {
  const parsed = parseBrowseId(id);
  if (!parsed || parsed.type !== type) throw new AppleMusicError(0, 'bad_id', `Not an Apple Music ${type.slice(0, -1)}.`);
  return parsed.id;
}

export function createCatalog(client, library) {
  const catalogPath = (path) => `/v1/catalog/${client.storefront()}/${path}`;

  async function resourceWithTracks(type, id) {
    const appleId = requireId(id, type);
    const page = await client.get(catalogPath(`${type}/${encodeURIComponent(appleId)}`), {
      include: 'tracks',
      ...ARTIST_INCLUDES
    });
    const resource = page.data?.[0];
    if (!resource) throw new AppleMusicError(404, 'not_found', 'Apple Music could not find that.');
    const relation = resource.relationships?.tracks || {};
    const resources = [...(relation.data || [])];
    // Long playlists continue past the first page of tracks.
    if (relation.next) resources.push(...(await client.all(relation.next, ARTIST_INCLUDES)).items);
    return { resource, tracks: resources.filter((song) => song.type === 'songs') };
  }

  async function albumPage(id) {
    const appleId = requireId(id, 'albums');
    if (isLibraryId(appleId)) return library.libraryAlbumPage(appleId, id);
    const { resource, tracks } = await resourceWithTracks('albums', id);
    const attributes = resource.attributes || {};
    const card = album(resource);
    const cards = tracks.map((song, index) => track(song, index)).filter(Boolean);
    const others = (await discography(card.artistBrowseId)).filter((entry) => entry.browseId !== id);
    return {
      kind: 'album',
      browseId: id,
      source: APPLE_MUSIC_SOURCE,
      title: card.title,
      artist: attributes.artistName || '',
      artists: card.artists,
      artistBrowseIds: card.artistBrowseIds,
      artistBrowseId: card.artistBrowseId,
      releaseType: releaseInfo(attributes).releaseType,
      year: yearOf(attributes.releaseDate),
      description: linkNotes(notes(attributes.editorialNotes), noteTargets(cards, [card, ...others])),
      thumbnail: artworkUrl(attributes.artwork, 1200),
      explicit: attributes.contentRating === 'explicit',
      itemCount: String(attributes.trackCount || tracks.length),
      copyright: attributes.copyright || '',
      tracks: cards,
      sections: []
    };
  }

  /** Albums by the artist, for linking titles in the notes; a failure only costs the links. */
  async function discography(artistBrowseId) {
    const parsed = parseBrowseId(artistBrowseId);
    if (!parsed || parsed.type !== 'artists') return [];
    try {
      const path = catalogPath(`artists/${encodeURIComponent(parsed.id)}/albums`);
      return items((await client.all(path, { limit: 100 }, 2)).items);
    } catch {
      return [];
    }
  }

  async function playlistPage(id) {
    const appleId = requireId(id, 'playlists');
    if (isLibraryId(appleId)) return library.libraryPlaylistPage(appleId, id);
    const { resource, tracks } = await resourceWithTracks('playlists', id);
    const attributes = resource.attributes || {};
    const card = playlist(resource);
    const cards = tracks.map((song, index) => track(song, index)).filter(Boolean);
    return {
      kind: 'playlist',
      browseId: id,
      source: APPLE_MUSIC_SOURCE,
      title: card.title,
      author: card.artist,
      subtitle: card.subtitle,
      description: linkNotes(card.description, noteTargets(cards)),
      thumbnail: artworkUrl(attributes.artwork, 1200),
      itemCount: String(tracks.length),
      tracks: cards,
      sections: []
    };
  }

  /** Search grouped into sections keyed like the YouTube provider's. */
  async function search(query, filter = 'all') {
    const term = String(query || '').trim();
    if (!term) return { sections: [] };
    const types = filter === 'all' ? Object.values(SEARCH_TYPES) : [SEARCH_TYPES[filter]].filter(Boolean);
    if (!types.length) return { sections: [] };
    const result = await client.get(catalogPath('search'), { term, types: types.join(','), limit: 25, ...ARTIST_INCLUDES });
    const sections = Object.entries(SEARCH_TYPES)
      .filter(([, type]) => types.includes(type))
      .map(([key, type]) => ({ key, title: key[0].toUpperCase() + key.slice(1), items: items(result.results?.[type]?.data) }))
      .filter((section) => section.items.length);
    return { sections };
  }

  /** The signed-in listener's storefront, or [fallback] for guests. */
  async function storefront(fallback = 'us') {
    try {
      return (await client.get('/v1/me/storefront')).data?.[0]?.id || fallback;
    } catch (error) {
      if (error instanceof AppleMusicError && ['not_signed_in', 'user_token'].includes(error.code)) return fallback;
      throw error;
    }
  }

  return { albumPage, playlistPage, search, storefront };
}

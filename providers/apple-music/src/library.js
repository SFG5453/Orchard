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

// The listener's Apple Music library: saved artists, albums, songs and playlists.
// Library ids (p., l., i., r.) differ from catalog ids; cards prefer the catalog id when Apple has one.

import { AppleMusicError } from './client.js';
import { APPLE_MUSIC_SOURCE } from './ids.js';
import { album, artist, artworkUrl, notes, playlist, releaseInfo, track, yearOf } from './normalize.js';

const PAGE = 100;
const PAGES = { artists: 10, albums: 10, songs: 20, playlists: 5 };
// Apple serves at most 30 recent tracks per page and about 50 in all.
const RECENT_PAGE = 30;
const RECENT_PAGES = 2;

function catalogId(resource) {
  return resource?.attributes?.playParams?.catalogId || resource?.relationships?.catalog?.data?.[0]?.id || '';
}

// The included catalog song carries catalog album and artist ids; library relationships carry library ids.
function asCatalog(resource) {
  const id = catalogId(resource);
  if (!id) return resource;
  const relationships = resource.relationships?.catalog?.data?.[0]?.relationships;
  return { ...resource, id, ...(relationships ? { relationships } : { relationships: undefined }) };
}

export function libraryTrack(resource, index) {
  const card = track(asCatalog(resource), index);
  // Uploads and other library-only songs have no catalog song for lyrics or artwork lookups.
  if (card && !catalogId(resource)) card.appleId = '';
  return card;
}

export function libraryPlaylist(resource) {
  const card = playlist(resource);
  if (!card) return null;
  const owner = resource.attributes?.canEdit ? 'You' : 'Apple Music';
  return { ...card, artist: owner, subtitle: owner };
}

function libraryArtist(resource) {
  // Library artists carry only a name; the catalog artist has the page and artwork.
  const catalog = resource?.relationships?.catalog?.data?.[0];
  return catalog?.attributes ? artist(catalog) : null;
}

const mappers = {
  artists: libraryArtist,
  albums: (resource) => album(asCatalog(resource)),
  songs: (resource) => libraryTrack(resource),
  playlists: libraryPlaylist
};

const titles = { artists: 'Artists', albums: 'Albums', songs: 'Songs', playlists: 'Playlists' };

export function createLibraryCatalog(client) {
  async function list(type) {
    const query = { limit: PAGE, ...(type === 'artists' ? { include: 'catalog' } : {}) };
    const { items } = await client.all(`/v1/me/library/${type}`, query, PAGES[type]);
    return items.map(mappers[type]).filter(Boolean);
  }

  /** One section per kind; a failing kind reports its own error so the rest still show. */
  async function library() {
    const sections = await Promise.all(Object.keys(titles).map(async (key) => {
      try {
        return { key, title: titles[key], items: await list(key) };
      } catch (error) {
        if (error instanceof AppleMusicError && ['not_connected', 'not_signed_in', 'user_token'].includes(error.code)) throw error;
        return { key, title: titles[key], items: [], error: String(error?.message || error) };
      }
    }));
    return { source: APPLE_MUSIC_SOURCE, sections };
  }

  async function libraryPlaylists() {
    return list('playlists');
  }

  async function tracksOf(path) {
    const query = { limit: PAGE, include: 'catalog', 'include[songs]': 'artists,albums' };
    const { items } = await client.all(path, query, PAGES.songs);
    return items.filter((song) => song.type === 'library-songs');
  }

  async function resource(type, id) {
    const found = (await client.get(`/v1/me/library/${type}/${encodeURIComponent(id)}`)).data?.[0];
    if (!found) throw new AppleMusicError(404, 'not_found', 'Apple Music could not find that.');
    return found;
  }

  async function libraryPlaylistPage(id, pageId) {
    const [found, songs] = await Promise.all([
      resource('playlists', id),
      // A new playlist has no tracks relationship; Apple answers 404.
      tracksOf(`/v1/me/library/playlists/${encodeURIComponent(id)}/tracks`).catch((error) => {
        if (error instanceof AppleMusicError && error.code === 'not_found') return [];
        throw error;
      })
    ]);
    const card = libraryPlaylist(found);
    const tracks = songs.map((song, index) => libraryTrack(song, index)).filter(Boolean);
    return {
      kind: 'playlist',
      browseId: pageId,
      source: APPLE_MUSIC_SOURCE,
      title: card.title,
      author: card.artist,
      subtitle: card.subtitle,
      description: notes(found.attributes?.description),
      // Playlists without custom artwork borrow their first song's cover, as Apple Music does.
      thumbnail: artworkUrl(found.attributes?.artwork, 1200) || tracks[0]?.thumbnail || '',
      itemCount: String(tracks.length),
      tracks,
      sections: []
    };
  }

  async function libraryAlbumPage(id, pageId) {
    const [found, songs] = await Promise.all([
      resource('albums', id),
      tracksOf(`/v1/me/library/albums/${encodeURIComponent(id)}/tracks`)
    ]);
    const attributes = found.attributes || {};
    const card = album(found);
    const tracks = songs.map((song, index) => libraryTrack(song, index)).filter(Boolean);
    return {
      kind: 'album',
      browseId: pageId,
      source: APPLE_MUSIC_SOURCE,
      title: card.title,
      artist: attributes.artistName || '',
      artists: card.artists,
      artistBrowseIds: [],
      artistBrowseId: '',
      releaseType: releaseInfo(attributes).releaseType,
      year: yearOf(attributes.releaseDate),
      description: '',
      thumbnail: artworkUrl(attributes.artwork, 1200),
      explicit: attributes.contentRating === 'explicit',
      itemCount: String(attributes.trackCount || tracks.length),
      copyright: '',
      tracks,
      sections: []
    };
  }

  /** Songs the listener played recently anywhere on Apple Music, newest first. */
  async function recentTracks() {
    const { items } = await client.all('/v1/me/recent/played/tracks', { limit: RECENT_PAGE }, RECENT_PAGES);
    return items
      .map((song, index) => (song.type === 'library-songs' ? libraryTrack(song, index) : song.type === 'songs' ? track(song, index) : null))
      .filter(Boolean);
  }

  return { library, libraryPlaylists, libraryPlaylistPage, libraryAlbumPage, recentTracks };
}

/** Library ids start with a lowercase letter and a dot: p.abc for playlists, l.abc for albums. */
export function isLibraryId(id) {
  return /^[a-z]\./.test(String(id || '')) && !String(id).startsWith('pl.');
}

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

import { browseId, parseBrowseId } from './ids.js';
import { findSong, sameArtist, sameTitle } from './match.js';
import { artworkUrl } from './normalize.js';

const CANDIDATES = 10;

/** Apple's own background and text colours for an artwork, as `#rrggbb`; empty when absent. */
function artworkColors(artwork) {
  const hex = (value) => (/^[0-9a-f]{6}$/i.test(String(value || '')) ? `#${value}` : '');
  return {
    background: hex(artwork?.bgColor),
    text: hex(artwork?.textColor1),
    secondaryText: hex(artwork?.textColor2)
  };
}

/** Search results carry no relationships; the album id is in the song's web URL. */
function albumIdOf(song) {
  const fromRelationship = song?.relationships?.albums?.data?.[0]?.id;
  if (fromRelationship) return fromRelationship;
  return /\/album\/[^/]+\/(\d+)/.exec(String(song?.attributes?.url || ''))?.[1] || '';
}

export function createArtworkCatalog(client) {
  const path = (type, id = '') => `/v1/catalog/${client.storefront()}/${type}${id ? `/${encodeURIComponent(id)}` : ''}`;

  async function albumArtwork(id) {
    // Only music.apple.com's API returns editorial fields; the public one still has the cover.
    const result = (await client.editorial(path('albums', id), { extend: 'editorialArtwork,editorialVideo' }))
      || await client.get(path('albums', id));
    const resource = result.data?.[0];
    const attributes = resource?.attributes;
    if (!attributes) return null;
    const video = attributes.editorialVideo || {};
    const heroTall = attributes.editorialArtwork?.superHeroTall;
    return {
      albumId: browseId('albums', resource.id),
      name: attributes.name || '',
      artist: attributes.artistName || '',
      static: artworkUrl(attributes.artwork, 1200),
      animated: video.motionDetailSquare?.video || '',
      animatedVertical: video.motionDetailTall?.video || '',
      heroTall: artworkUrl(heroTall, 1600),
      colors: artworkColors(heroTall?.url ? heroTall : attributes.artwork)
    };
  }

  async function songAlbumId(query) {
    return albumIdOf(await findSong(client, query));
  }

  async function albumIdByName(name, artist) {
    if (!name || !artist) return '';
    const result = await client.get(path('search'), { term: `${name} ${artist}`, types: 'albums', limit: CANDIDATES });
    const albums = result.results?.albums?.data || [];
    const match = albums.find((album) =>
      sameTitle(album.attributes?.name, name) && sameArtist(album.attributes?.artistName, artist));
    return match?.id || '';
  }

  /**
   * Apple's motion artwork for a song or album: square and tall HLS URLs plus the still cover.
   * Accepts an am.albums id, an Apple song id, an ISRC, or plain title/artist/album text.
   */
  async function artwork(query = {}) {
    const parsed = parseBrowseId(query.albumId);
    let id = parsed?.type === 'albums' ? parsed.id : '';
    if (!id && query.kind === 'album') id = await albumIdByName(query.album || query.title, query.artist);
    if (!id) id = await songAlbumId(query);
    return id ? albumArtwork(id) : null;
  }

  return { artwork };
}

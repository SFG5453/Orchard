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
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
 * A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Orchard. If not, see <https://www.gnu.org/licenses/>.
 */

import { AppleMusicError } from './client.js';
import { APPLE_MUSIC_SOURCE, parseBrowseId } from './ids.js';
import { artworkUrl, station, track } from './normalize.js';

// Apple serves station tracks ten at a time; each call continues the station server side.
const BATCH = 10;

/** Apple station id for an am.stations id, or the song's own station for an am.songs id. */
export function stationIdFor(value) {
  const parsed = parseBrowseId(value);
  if (parsed?.type === 'stations') return parsed.id;
  if (parsed?.type === 'songs') return `ra.${parsed.id}`;
  throw new AppleMusicError(0, 'bad_id', 'Not an Apple Music song or station.');
}

export function createStationCatalog(client) {
  async function nextTracks(stationId) {
    let page;
    try {
      page = await client.send('POST', `/v1/me/stations/next-tracks/${encodeURIComponent(stationId)}?limit=${BATCH}`);
    } catch (error) {
      // Some songs have no station; that is an empty radio, not a failure.
      if (error instanceof AppleMusicError && error.status === 404) return [];
      throw error;
    }
    const songs = (page?.data || []).filter((entry) => entry.type === 'songs');
    return (await withCredits(songs)).map((entry) => track(entry)).filter(Boolean);
  }

  /** Next-tracks ignores include, so the catalog fills in artist and album links; a miss keeps the bare songs. */
  async function withCredits(songs) {
    if (!songs.length) return songs;
    try {
      const page = await client.get(`/v1/catalog/${client.storefront()}/songs`, {
        ids: songs.map((entry) => entry.id).join(','),
        include: 'artists,albums'
      });
      const full = new Map((page.data || []).map((entry) => [entry.id, entry]));
      return songs.map((entry) => full.get(entry.id) || entry);
    } catch {
      return songs;
    }
  }

  /** The next songs after a song or from a station, for Autoplay and Start mix. */
  async function upNext(seed) {
    return nextTracks(stationIdFor(seed));
  }

  /** A station as a playlist page holding its next batch of songs. */
  async function stationPage(id) {
    const stationId = stationIdFor(id);
    const [info, tracks] = await Promise.all([
      client.get(`/v1/catalog/${client.storefront()}/stations/${encodeURIComponent(stationId)}`),
      nextTracks(stationId)
    ]);
    const resource = info.data?.[0];
    const card = station(resource);
    if (!card) throw new AppleMusicError(0, 'live_station', 'Orchard cannot play live radio.');
    return {
      kind: 'playlist',
      station: true,
      browseId: card.browseId,
      source: APPLE_MUSIC_SOURCE,
      title: card.title,
      author: card.artist,
      subtitle: card.subtitle,
      description: card.description,
      thumbnail: artworkUrl(resource.attributes.artwork, 1200),
      itemCount: String(tracks.length),
      tracks: tracks.map((entry, index) => ({ ...entry, index: String(index + 1) })),
      sections: []
    };
  }

  return { stationPage, upNext };
}

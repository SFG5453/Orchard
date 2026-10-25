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

// Apple Music resources mapped onto the item, track and detail shapes the
// YouTube provider returns, so desktop QML and Android parse both alike.

import { APPLE_MUSIC_SOURCE, browseId } from './ids.js';
import { cleanNotes } from './notes.js';

const RELEASE_SUFFIX = /\s+-\s+(Single|EP)$/i;
export const ARTIST_INCLUDES = { 'include[albums]': 'artists', 'include[songs]': 'artists,albums' };

/** Fills an artwork template; Apple sizes covers on its CDN. */
export function artworkUrl(artwork, size = 600, format = 'jpg') {
  const template = String(artwork?.url || '');
  if (!template) return '';
  const width = Math.min(size, Number(artwork.width) || size);
  const height = Math.min(size, Number(artwork.height) || size);
  return template
    .replace('{w}', String(width))
    .replace('{h}', String(height))
    .replace('{c}', 'bb')
    .replace('{f}', format)
    // Editorial templates hard-code the extension (`{w}x{h}bb.jpg`); the CDN serves whichever is asked.
    .replace(/\.(jpe?g|png|webp)$/, `.${format}`);
}

function yearOf(date) {
  const match = /^(\d{4})/.exec(String(date || ''));
  return match ? match[1] : '';
}

/** "3:05" from seconds; empty when Apple omits the length. */
function clock(seconds) {
  const total = Math.round(Number(seconds) || 0);
  if (total <= 0) return '';
  return `${Math.floor(total / 60)}:${String(total % 60).padStart(2, '0')}`;
}

function notes(value) {
  if (!value) return '';
  return cleanNotes(typeof value === 'string' ? value : value.standard || value.short);
}

/** Apple suffixes single and EP names; the suffix becomes the release type. */
export function releaseInfo(attributes = {}) {
  const name = String(attributes.name || '');
  const suffix = RELEASE_SUFFIX.exec(name);
  let releaseType = 'Album';
  if (suffix) releaseType = suffix[1].length <= 2 ? suffix[1].toUpperCase() : 'Single';
  else if (attributes.isSingle) releaseType = 'Single';
  else if (attributes.isCompilation) releaseType = 'Compilation';
  return { title: suffix ? name.replace(RELEASE_SUFFIX, '') : name, releaseType };
}

function related(resource, name) {
  return resource?.relationships?.[name]?.data || [];
}

function artistCredits(resource) {
  const artists = related(resource, 'artists').filter((artist) => artist?.id);
  const name = resource?.attributes?.artistName || '';
  return {
    artists: artists.length ? artists.map((artist) => artist.attributes?.name || (artists.length === 1 ? name : '')) : [name],
    artistBrowseIds: artists.map((artist) => browseId('artists', artist.id))
  };
}

export function track(resource, index) {
  const attributes = resource?.attributes;
  if (!attributes || !resource.id) return null;
  const album = related(resource, 'albums')[0];
  return {
    id: browseId('songs', resource.id),
    type: 'song',
    appleId: resource.id,
    source: APPLE_MUSIC_SOURCE,
    title: attributes.name || 'Untitled',
    artist: attributes.artistName || '',
    ...artistCredits(resource),
    album: releaseInfo({ name: attributes.albumName }).title,
    albumId: album ? browseId('albums', album.id) : '',
    thumbnail: artworkUrl(attributes.artwork),
    durationSeconds: (Number(attributes.durationInMillis) || 0) / 1000,
    duration: clock((Number(attributes.durationInMillis) || 0) / 1000),
    year: yearOf(attributes.releaseDate),
    explicit: attributes.contentRating === 'explicit',
    isrc: attributes.isrc || '',
    previewUrl: attributes.previews?.[0]?.url || '',
    // Catalog-only songs (not licensed in this storefront) have no playParams.
    unplayable: !attributes.playParams,
    ...(index === undefined ? {} : { index: String(index + 1) })
  };
}

export function album(resource) {
  const attributes = resource?.attributes;
  if (!attributes) return null;
  const { title, releaseType } = releaseInfo(attributes);
  const year = yearOf(attributes.releaseDate);
  const credits = artistCredits(resource);
  return {
    type: 'album',
    browseId: browseId('albums', resource.id),
    source: APPLE_MUSIC_SOURCE,
    title,
    artist: attributes.artistName || '',
    ...credits,
    artistBrowseId: credits.artistBrowseIds[0] || '',
    subtitle: [releaseType, attributes.artistName, year].filter(Boolean).join(' • '),
    thumbnail: artworkUrl(attributes.artwork),
    year,
    releaseYear: year,
    releaseType,
    explicit: attributes.contentRating === 'explicit'
  };
}

export function playlist(resource) {
  const attributes = resource?.attributes;
  if (!attributes) return null;
  return {
    type: 'playlist',
    browseId: browseId('playlists', resource.id),
    source: APPLE_MUSIC_SOURCE,
    title: attributes.name || '',
    artist: attributes.curatorName || 'Apple Music',
    subtitle: attributes.curatorName || 'Apple Music',
    thumbnail: artworkUrl(attributes.artwork),
    description: notes(attributes.description)
  };
}

export function artist(resource) {
  const attributes = resource?.attributes;
  if (!attributes) return null;
  return {
    type: 'artist',
    browseId: browseId('artists', resource.id),
    source: APPLE_MUSIC_SOURCE,
    title: attributes.name || '',
    subtitle: (attributes.genreNames || [])[0] || 'Artist',
    thumbnail: artworkUrl(attributes.artwork)
  };
}

/** A 16:9 poster for video artwork; Apple's `mv` crop keeps the frame's own aspect. */
export function videoArtworkUrl(artwork, width = 640) {
  const template = String(artwork?.url || '');
  if (!template) return '';
  return template.replace('{w}', String(width)).replace('{h}', String(Math.round(width * 9 / 16)))
    .replace('{c}', 'mv').replace('{f}', 'jpg');
}

export function musicVideo(resource) {
  const attributes = resource?.attributes;
  if (!attributes || !resource.id) return null;
  const seconds = (Number(attributes.durationInMillis) || 0) / 1000;
  return {
    id: browseId('music-videos', resource.id),
    type: 'video',
    appleId: resource.id,
    source: APPLE_MUSIC_SOURCE,
    title: attributes.name || 'Untitled',
    artist: attributes.artistName || '',
    ...artistCredits(resource),
    thumbnail: videoArtworkUrl(attributes.artwork),
    durationSeconds: seconds,
    duration: clock(seconds),
    year: yearOf(attributes.releaseDate),
    explicit: attributes.contentRating === 'explicit',
    isrc: attributes.isrc || '',
    unplayable: !attributes.playParams
  };
}

const itemMappers = { songs: track, albums: album, playlists: playlist, artists: artist, 'music-videos': musicVideo };

/** Any supported resource as a card; stations and curators are skipped. */
export function item(resource) {
  return itemMappers[resource?.type]?.(resource) || null;
}

export function items(resources = []) {
  return resources.map((resource) => item(resource)).filter(Boolean);
}

export { notes, yearOf };

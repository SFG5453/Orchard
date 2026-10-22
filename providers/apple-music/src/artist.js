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
import { browseId, parseBrowseId } from './ids.js';
import { linkNotes, noteTargets } from './notes.js';
import { ARTIST_INCLUDES, artworkUrl, items, notes, track } from './normalize.js';

// Shelves in page order. Music videos and stations wait for Apple Music playback.
export const ARTIST_VIEWS = [
  'latest-release',
  'top-songs',
  'full-albums',
  'singles',
  'live-albums',
  'compilation-albums',
  'appears-on-albums',
  'featured-playlists',
  'similar-artists'
];
// Undocumented attributes MusicKit clients request for artist pages.
const ARTIST_EXTEND = 'artistBio,editorialVideo';
// editorialArtwork is a 400 on the public API, so only music.apple.com's API is asked for it.
const EDITORIAL_EXTEND = 'artistBio,editorialArtwork,editorialVideo';
// Fills the three-column Top Songs grid four rows deep.
const TOP_SONGS = 12;

function normalizedName(value) {
  return String(value || '')
    .normalize('NFKD')
    .replace(/[̀-ͯ]/g, '')
    .toLowerCase()
    .replace(/&/g, 'and')
    .replace(/[^a-z0-9]+/g, '');
}

// music.apple.com plays the fullscreen cut; the wide and square cuts are different edits.
const HERO_VIDEO_KEYS = ['motionArtistFullscreen16x9', 'motionArtistWide16x9', 'motionArtistSquare1x1'];

/** The motion artwork music.apple.com plays behind the artist name. */
export function heroMotion(editorialVideo) {
  if (!editorialVideo || typeof editorialVideo !== 'object') return null;
  const keys = HERO_VIDEO_KEYS.filter((key) => key in editorialVideo)
    .concat(Object.keys(editorialVideo).filter((key) => !HERO_VIDEO_KEYS.includes(key)));
  for (const key of keys) {
    const entry = editorialVideo[key];
    if (typeof entry?.video === 'string' && entry.video.startsWith('https://')) return entry;
  }
  return null;
}

/** The hero motion artwork as an HLS URL. */
export function heroVideo(editorialVideo) {
  return heroMotion(editorialVideo)?.video || '';
}

function viewSection(artistId, name, view) {
  const resources = view?.data || [];
  const sectionItems = name === 'top-songs' ? [] : items(resources);
  if (!sectionItems.length) return null;
  return {
    key: name,
    title: view.attributes?.title || name,
    items: sectionItems,
    browsePayload: view.next ? { browseId: browseId('artists', artistId), params: `view:${name}` } : null
  };
}

export function createArtistCatalog(client) {
  async function fetchArtist(id) {
    const path = `/v1/catalog/${client.storefront()}/artists/${encodeURIComponent(id)}`;
    const query = { views: ARTIST_VIEWS.join(','), ...ARTIST_INCLUDES };
    const editorial = await client.editorial(path, { ...query, extend: EDITORIAL_EXTEND });
    if (editorial) return editorial;
    try {
      return await client.get(path, { ...query, extend: ARTIST_EXTEND });
    } catch (error) {
      // The extended attributes are undocumented; a rejection must not cost the page.
      if (!(error instanceof AppleMusicError) || error.status !== 400) throw error;
      return client.get(path, query);
    }
  }

  async function artistPage(id) {
    const parsed = parseBrowseId(id);
    if (!parsed || parsed.type !== 'artists') throw new AppleMusicError(0, 'bad_id', 'Not an Apple Music artist.');
    const resource = (await fetchArtist(parsed.id)).data?.[0];
    if (!resource) throw new AppleMusicError(404, 'not_found', 'Apple Music could not find that artist.');
    const attributes = resource.attributes || {};
    const views = resource.views || {};
    const motion = heroMotion(attributes.editorialVideo);
    const tracks = (views['top-songs']?.data || [])
      .slice(0, TOP_SONGS)
      .map((song, index) => track(song, index))
      .filter(Boolean);
    const viewNames = ARTIST_VIEWS.filter((name) => name !== 'similar-artists')
      .concat(Object.keys(views).filter((name) => !ARTIST_VIEWS.includes(name)), 'similar-artists');
    const sections = viewNames
      .map((name) => viewSection(parsed.id, name, views[name]))
      .filter(Boolean);
    const targets = noteTargets(tracks, sections.flatMap((section) => section.items));
    for (const [name, target] of targets) if (target === id) targets.delete(name);
    return {
      kind: 'artist',
      browseId: id,
      source: 'apple_music',
      title: attributes.name || 'Artist',
      subtitle: (attributes.genreNames || []).join(', '),
      artist: attributes.name || '',
      year: '',
      itemCount: '',
      totalDuration: '',
      views: '',
      description: linkNotes(notes(attributes.artistBio) || notes(attributes.editorialNotes), targets),
      thumbnail: artworkUrl(attributes.editorialArtwork?.vipSquare || attributes.artwork, 1200),
      heroArtwork: artworkUrl(attributes.editorialArtwork?.centeredFullscreenBackground, 2400),
      // The name as artwork, as music.apple.com draws it over the hero. PNG keeps its transparency.
      logo: artworkUrl(attributes.editorialArtwork?.musicContentColorLogoTrimmed, 1200, 'png'),
      heroVideo: motion?.video || '',
      // Still of the video's first frame, shown until it plays.
      heroPoster: artworkUrl(motion?.previewFrame, 1920),
      // Hex without '#'; the page tints itself with this, darkened.
      heroTint: String(motion?.previewFrame?.bgColor || attributes.artwork?.bgColor || ''),
      url: attributes.url || '',
      tracks,
      // Desktop shows this as a card beside Top Songs; Android reads the section.
      latestRelease: sections.find((section) => section.key === 'latest-release')?.items[0] || null,
      sections
    };
  }

  /** "See all" for one artist shelf. */
  async function artistView(id, params) {
    const parsed = parseBrowseId(id);
    const view = /^view:([a-z-]+)$/.exec(String(params || ''))?.[1];
    if (!parsed || parsed.type !== 'artists' || !view) return [];
    const path = `/v1/catalog/${client.storefront()}/artists/${encodeURIComponent(parsed.id)}/view/${view}`;
    const { items: resources } = await client.all(path, { limit: 25, ...ARTIST_INCLUDES });
    return view === 'top-songs' ? resources.map((song, index) => track(song, index)).filter(Boolean) : items(resources);
  }

  /** The Apple Music artist for a name, preferring an exact match. */
  async function matchArtist(name) {
    const term = String(name || '').trim();
    if (!term) return null;
    const result = await client.get(`/v1/catalog/${client.storefront()}/search`, { term, types: 'artists', limit: 10 });
    const candidates = result.results?.artists?.data || [];
    const wanted = normalizedName(term);
    const exact = candidates.find((candidate) => normalizedName(candidate.attributes?.name) === wanted);
    if (!exact) return null;
    return { browseId: browseId('artists', exact.id), title: exact.attributes?.name || term };
  }

  return { artistPage, artistView, matchArtist };
}

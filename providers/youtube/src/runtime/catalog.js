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

import { createArtistCatalog } from '../catalog/artistCatalog.js';
import { createMainFeeds } from '../catalog/mainFeeds.js';
import { createSearchUtils } from '../catalog/searchUtils.js';
import { musicBrowseRequests } from '../catalog/musicBrowse.js';
import { playlistWave, prefetchPlaylistWave, rememberPlaylistTotal } from './playlistPages.js';
import { shouldShowHomeItem, shouldShowMusicItem } from '../catalog/musicItemTypes.js';
import {
  asText,
  bestThumbnail,
  hasExplicitBadge,
  normalizeTrack,
  normalizedLooseText,
  normalizeTvLibrary,
  textParts
} from '../catalog/musicText.js';
import {
  youtubeOrigin,
  defaultWebClientVersion,
  maxPlaylistPages,
  maxBrowsePages,
  normalizers,
  searchUtils,
  clean,
  sessionFrom,
  errorMessage,
  createBrowseRequest,
  createMusicSearch
} from './catalogRequest.js';

export { createBrowseRequest, createMusicSearch };

export async function loadSearch(payload = {}) {
  const query = clean(payload.query);
  if (!query) return { filters: [], sections: [] };

  const requestedFilter = clean(payload.filter).toLowerCase();
  const filter = ['all', 'songs', 'videos', 'albums', 'artists', 'playlists'].includes(requestedFilter)
    ? requestedFilter
    : 'all';
  const collection = {
    // Guest lookups keep internal searches out of the account's search history.
    search: createMusicSearch(sessionFrom(payload), {
      guest: payload.guest === true,
      // Search is useful with a valid signed-in session even though the response
      // itself is not personalized in every YouTube account configuration.
      allowGuestResponse: true
    })
  };

  // Search is finally looking outside the fridge. Keep the v2 ranking and
  // de-duplication rules in the provider so every client sees the same catalog.
  if (filter !== 'all')
    return searchUtils.searchCatalog(collection, query, filter);

  // Broad search sometimes serves only songs and artists. Invite the other
  // shelves explicitly; an empty seat is not a design feature.
  const results = await Promise.allSettled([
    searchUtils.searchCatalog(collection, query, 'all'),
    ...['videos', 'albums', 'playlists'].map(category =>
      searchUtils.searchCatalog(collection, query, category))
  ]);
  const available = results.filter(result => result.status === 'fulfilled').map(result => result.value);
  if (!available.length) throw results[0].reason;
  return searchUtils.mergeSearchResults(available[0], available.slice(1));
}

function mainFeedsForSession(session) {
  const fetchBrowse = createBrowseRequest(session);
  const fetchWebBrowse = createBrowseRequest(session, {
    origin: youtubeOrigin,
    clientName: 'WEB',
    clientNumber: '1',
    clientVersion: defaultWebClientVersion
  });
  return createMainFeeds({
    asText,
    browseContinuationTokenFromData: normalizers.browseContinuationTokenFromData,
    bridgeError: errorMessage,
    fetchRawBrowserBrowse: fetchWebBrowse,
    fetchRawBrowserMusicBrowse: fetchBrowse,
    hasBrowserLoginCookie: () => true,
    normalizeBrowseSection: normalizers.normalizeBrowseSection,
    normalizeRawBrowseItem: normalizers.normalizeRawBrowseItem,
    normalizeTrack,
    normalizeTvLibrary,
    rawBrowseItemsFromData: normalizers.rawBrowseItemsFromData,
    rawSectionList: normalizers.rawSectionList
  });
}

export async function loadHome(payload = {}) {
  const feeds = mainFeedsForSession(sessionFrom(payload));

  // Home has a frankly silly number of continuation shapes. The old app's
  // normalizer already knows all of them, so let's not invent a worse one.
  const [libraryResult, homeResult, subscriptionsResult] = await Promise.allSettled([
    feeds.fetchBrowserMusicLibraryHome(),
    feeds.fetchBrowserMusicHome(),
    feeds.fetchBrowserSubscribedArtists()
  ]);
  if (homeResult.status === 'rejected' && libraryResult.status === 'rejected') {
    throw homeResult.reason;
  }

  const library = libraryResult.status === 'fulfilled'
    ? libraryResult.value.sections || []
    : [];
  const home = homeResult.status === 'fulfilled'
    ? homeResult.value.sections || []
    : [];
  const sections = [...library, ...home]
    .map((section) => ({
      ...section,
      items: (section.items || []).filter(shouldShowHomeItem)
    }))
    .filter((section) => section.items.length > 0);
  return {
    filters: [],
    artists: subscriptionsResult.status === 'fulfilled' ? subscriptionsResult.value : [],
    sections
  };
}

export async function loadLibrary(payload = {}) {
  const session = sessionFrom(payload);
  const feeds = mainFeedsForSession(session);
  // Share the landing page's category endpoints. Recommendations do not get
  // to sneak into the library wearing a fake membership card.
  const landing = createBrowseRequest({ ...session, language: 'en' })({ browseId: 'FEmusic_library_landing' });
  const categories = ['artists', 'albums', 'songs', 'playlists'];
  const results = await Promise.allSettled([
    feeds.fetchBrowserSubscribedArtists(),
    landing.then(page => feeds.fetchMusicLibraryCategory(null, 'Albums', page)),
    landing.then(page => feeds.fetchMusicLibraryCategory(null, 'Songs', page)),
    loadPlaylists(payload)
  ]);
  if (results.every(result => result.status === 'rejected')) throw results[0].reason;

  return {
    sections: categories.map((key, index) => {
      const result = results[index];
      return {
        key,
        items: result.status === 'fulfilled' ? result.value.filter(shouldShowHomeItem) : [],
        error: result.status === 'rejected' ? errorMessage(result.reason) : ''
      };
    })
  };
}

export async function loadPlaylists(payload = {}) {
  const fetchBrowse = createBrowseRequest(sessionFrom(payload));
  const playlists = [];
  let request = { browseId: 'FEmusic_liked_playlists' };
  let pageCount = 0;

  while (request && pageCount < maxPlaylistPages) {
    const page = await fetchBrowse(request);
    playlists.push(...normalizers.rawBrowseItemsFromData(page)
      .map(normalizers.normalizeRawBrowseItem)
      .filter(Boolean)
      .filter(shouldShowMusicItem));

    pageCount += 1;
    const continuation = normalizers.browseContinuationTokenFromData(page);
    request = continuation ? { continuation } : null;
  }

  // YouTube occasionally repeats the last tile on the next page. Nice of it.
  const seen = new Set();
  return playlists.filter((playlist) => {
    const key = playlist.browseId || playlist.browsePayload?.browseId || playlist.id || playlist.title;
    if (!key || seen.has(key)) return false;
    seen.add(key);
    return true;
  });
}

// Report shapes, not private text or credentials. This detective only reads labels.
function playlistMetadataShape(data) {
  const headers = [];
  function shape(value, depth = 0) {
    if (value === null) return 'null';
    if (Array.isArray(value)) return value.slice(0, 3).map((item) => shape(item, depth + 1));
    if (typeof value !== 'object') return typeof value;
    if (depth >= 8) return Object.keys(value);
    return Object.fromEntries(Object.entries(value).map(([key, child]) => [key, shape(child, depth + 1)]));
  }
  function visit(value, path = '$') {
    if (!value || typeof value !== 'object') return;
    for (const [key, child] of Object.entries(value)) {
      const childPath = `${path}.${key}`;
      if (/header|description|strapline|owner/i.test(key)) {
        headers.push({ path: childPath, shape: shape(child) });
      } else {
        visit(child, childPath);
      }
    }
  }
  visit(data);
  return headers;
}

const playlistRows = (data) => normalizers.playlistItemsFromData(data).length;

// Only playlists the account can edit wear this header, wherever YouTube puts it this week.
function hasEditableHeader(value, depth = 0) {
  if (!value || typeof value !== 'object' || depth > 12) return false;
  if (value.musicEditablePlaylistDetailHeaderRenderer) return true;
  return Object.values(value).some((child) => hasEditableHeader(child, depth + 1));
}

export async function loadPlaylist(payload = {}) {
  const browseId = clean(payload.browseId);
  if (!browseId) throw new Error('A YouTube playlist browse ID is required.');

  const fetchBrowse = createBrowseRequest(sessionFrom(payload));
  let lastError;

  // Playlist IDs sometimes wear a VL prefix in Music and sometimes don't.
  // Trying the same variants as Orchard v2 is cheaper than pretending YouTube
  // made this consistent.
  for (const request of musicBrowseRequests('playlist', payload)) {
    try {
      const data = await fetchBrowse(request);
      const playlist = normalizers.normalizePlaylist({ data, browseId: request.browseId });
      rememberPlaylistTotal(request.browseId, data);
      prefetchPlaylistWave(sessionFrom(payload), fetchBrowse, playlistRows, playlist.continuation);
      return {
        ...playlist,
        ...(payload.diagnostics ? { metadataDiagnostics: playlistMetadataShape(data) } : {}),
        editable: hasEditableHeader(data)
      };
    } catch (error) {
      lastError = error;
    }
  }

  throw lastError || new Error('YouTube did not return the requested playlist.');
}

export async function loadAlbum(payload = {}) {
  const browseId = clean(payload.browseId);
  if (!browseId) throw new Error('A YouTube album browse ID is required.');

  let lastError;

  // Public albums do not require a personalized response. Like Orchard v2,
  // retry without the saved account if the authenticated browse fails.
  for (const guest of [false, true]) {
    try {
      const fetchBrowse = createBrowseRequest(sessionFrom(payload), {
        guest,
        allowGuestResponse: true
      });
      for (const request of musicBrowseRequests('album', payload)) {
        try {
          const data = await fetchBrowse(request);
          if (data.error || !data.contents) {
            throw new Error(data.error?.message || 'YouTube did not return the requested album.');
          }
          return normalizers.normalizeAlbum(data, request.browseId);
        } catch (error) {
          lastError = error;
        }
      }
    } catch (error) {
      lastError = error;
    }
  }

  throw lastError || new Error('YouTube did not return the requested album.');
}

export async function loadPlaylistPage(payload = {}) {
  const continuation = clean(payload.continuation);
  if (!continuation) throw new Error('A YouTube playlist continuation is required.');

  const session = sessionFrom(payload);
  const fetchBrowse = createBrowseRequest(session);
  let index = Math.max(0, Number(payload.startIndex) || 0);
  const pages = await playlistWave(session, fetchBrowse, playlistRows, continuation);
  const tracks = [];
  for (const data of pages) {
    const page = normalizers.normalizePlaylistPage(data, index);
    tracks.push(...page.tracks);
    index += page.tracks.length;
  }
  const next = normalizers.playlistContinuationTokenFromData(pages.at(-1)) || '';
  // Request the next wave before this one crosses into the host.
  prefetchPlaylistWave(session, fetchBrowse, playlistRows, next);
  return { tracks, continuation: next, hasMoreTracks: Boolean(next) };
}

// Keep desktop artist filtering, release ordering and metadata hydration in step
// with v2. YouTube's shelves still haven't agreed on what an album is.
export async function loadArtist(payload = {}) {
  const browseId = clean(payload.browseId);
  if (!browseId) throw new Error('A YouTube artist browse ID is required.');
  const searchUtils = createSearchUtils({
    asText, bestThumbnail, hasExplicitBadge, normalizedLooseText, textParts,
    shelfItems: shelf => shelf?.items || []
  });
  const catalog = createArtistCatalog({
    ...normalizers, ...searchUtils, asText, normalizedLooseText
  });
  let lastError;
  for (const guest of [false, true]) {
    try {
      const browse = createBrowseRequest(sessionFrom(payload), {
        guest, allowGuestResponse: true
      });
      const data = await browse({ browseId });
      if (data.error || !data.contents)
        throw new Error(data.error?.message || 'YouTube did not return the requested artist.');
      const collection = {
        data, browseId, browse, continue: continuation => browse({ continuation }),
        search: createMusicSearch(sessionFrom(payload), {
          guest, allowGuestResponse: true
        })
      };
      const artist = await catalog.normalizeArtist(collection);
      artist.sections = await Promise.all(artist.sections.map(section =>
        catalog.normalizeArtistSection(collection, section)));
      // Release shelves often omit credits because the page already names the
      // artist. Carry that context into desktop menus and playback links.
      artist.sections = artist.sections.map(section => ({
        ...section,
        items: section.items.map(item => item.type === 'album' && !item.artistBrowseIds?.length
          ? { ...item, artist: item.artist || artist.title,
              artists: item.artists?.length ? item.artists : [artist.title], artistBrowseIds: [browseId] }
          : item)
      }));
      artist.latestRelease = catalog.latestArtistRelease(artist.sections);
      return artist;
    } catch (error) {
      lastError = error;
    }
  }
  throw lastError;
}

// Radio uses the same Music next endpoint as V2's getUpNext(videoId, true).
export async function loadUpNext(payload = {}) {
  const videoId = clean(payload.videoId);
  if (!videoId) throw new Error('A YouTube video ID is required.');
  const data = await createBrowseRequest(sessionFrom(payload))({
    videoId, playlistId: `RDAMVM${videoId}`, isAudioOnly: true,
    enablePersistentPlaylistPanel: true
  }, 'next');
  const tracks = [];
  function visit(value) {
    if (!value || typeof value !== 'object') return;
    if (value.playlistPanelVideoRenderer) {
      const row = value.playlistPanelVideoRenderer;
      // Dress the radio row in the catalog normalizer's usual uniform.
      tracks.push(normalizers.normalizeRawResponsiveListItem({
        playlistItemData: { videoId: row.videoId },
        navigationEndpoint: row.navigationEndpoint,
        flexColumns: [row.title, row.longBylineText || row.shortBylineText].map(text => ({
          musicResponsiveListItemFlexColumnRenderer: { text }
        })),
        fixedColumns: [{ musicResponsiveListItemFixedColumnRenderer: { text: row.lengthText } }],
        thumbnail: row.thumbnail, badges: row.badges
      }));
      if (row.unplayableText || row.isPlayable === false) tracks.at(-1).unplayable = true;
      return;
    }
    for (const child of Object.values(value)) visit(child);
  }
  visit(data.contents);
  return tracks.filter(track => track.id && track.id !== videoId && !track.unplayable);
}

function lyricsBrowseId(next) {
  const tabs = next.contents?.singleColumnMusicWatchNextResultsRenderer?.tabbedRenderer
    ?.watchNextTabbedResultsRenderer?.tabs || [];
  for (const tab of tabs) {
    const browseId = tab.tabRenderer?.endpoint?.browseEndpoint?.browseId || '';
    if (browseId.startsWith('MPLY')) return browseId;
  }
  return '';
}

// Plain-text lyrics from the watch page's Lyrics tab. Empty when YouTube has none.
export async function loadYouTubeLyricsText(payload = {}) {
  const videoId = clean(payload.videoId);
  if (!videoId) return '';
  const session = sessionFrom(payload);
  const request = createBrowseRequest(session, {
    guest: !clean(session.cookie),
    allowGuestResponse: true
  });
  const browseId = lyricsBrowseId(await request({ videoId, isAudioOnly: true }, 'next'));
  if (!browseId) return '';
  const data = await request({ browseId });
  const shelves = data.contents?.sectionListRenderer?.contents || [];
  const shelf = shelves.find((item) => item.musicDescriptionShelfRenderer)?.musicDescriptionShelfRenderer;
  return (shelf?.description?.runs || []).map((run) => run.text || '').join('');
}

// Playlist rows with their setVideoId, for edits that address one occurrence.
export function playlistPage(data, startIndex = 0) {
  return normalizers.normalizePlaylistPage(data, startIndex);
}

// Any other browse page: moods, genres, charts and a shelf's "more" grid.
export async function loadBrowse(payload = {}) {
  const browseId = clean(payload.browseId);
  if (!browseId) throw new Error('A YouTube browse ID is required.');
  const params = clean(payload.params);
  const request = createBrowseRequest(sessionFrom(payload), { allowGuestResponse: true });
  const data = await request({ browseId, ...(params ? { params } : {}) });
  const sections = normalizers.rawSectionList(data)
    .map(normalizers.normalizeBrowseSection)
    .filter((section) => section.items.length > 0);
  const more = [];
  let continuation = normalizers.browseContinuationTokenFromData(data);
  for (let page = 0; continuation && page < maxBrowsePages; page += 1) {
    const next = await request({ continuation });
    const items = normalizers.rawBrowseItemsFromData(next)
      .map(normalizers.normalizeRawBrowseItem)
      .filter(Boolean);
    if (!items.length) break;
    more.push(...items);
    const token = normalizers.browseContinuationTokenFromData(next);
    if (token === continuation) break;
    continuation = token;
  }
  if (more.length) {
    const last = sections.pop() || { key: 'section-0', title: '', items: [], browsePayload: null };
    sections.push({ ...last, items: [...last.items, ...more] });
  }
  return {
    browseId,
    title: asText(normalizers.rawHeader(data).title) || '',
    sections
  };
}

// One queue row with every credited artist, for entries saved without them.
export async function loadTrack(payload = {}) {
  const videoId = clean(payload.videoId);
  if (!videoId) throw new Error('A YouTube video ID is required.');
  const session = sessionFrom(payload);
  const data = await createBrowseRequest(session, {
    guest: !clean(session.cookie),
    allowGuestResponse: true
  })({ videoId, isAudioOnly: true }, 'next');
  let found = null;
  function visit(value) {
    if (found || !value || typeof value !== 'object') return;
    const row = value.playlistPanelVideoRenderer;
    if (row?.videoId === videoId) {
      found = normalizers.normalizeRawResponsiveListItem({
        playlistItemData: { videoId: row.videoId },
        navigationEndpoint: row.navigationEndpoint,
        flexColumns: [row.title, row.longBylineText || row.shortBylineText].map(text => ({
          musicResponsiveListItemFlexColumnRenderer: { text }
        })),
        fixedColumns: [{ musicResponsiveListItemFixedColumnRenderer: { text: row.lengthText } }],
        thumbnail: row.thumbnail, badges: row.badges
      });
      return;
    }
    for (const child of Object.values(value)) visit(child);
  }
  visit(data.contents);
  if (!found) throw new Error('YouTube did not return this track.');
  return found;
}

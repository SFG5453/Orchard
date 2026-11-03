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


import {
  browserAuthHeader,
  cookieWithPlaybackDefaults
} from '../auth/browserMusicApi.js';
import { createBrowseNormalizers } from '../catalog/browseNormalizers.js';
import { createSearchUtils } from '../catalog/searchUtils.js';
import {
  asText,
  bestThumbnail,
  cleanedText,
  findDurationText,
  hasExplicitBadge,
  normalizeTrack,
  normalizedLooseText,
  textParts
} from '../catalog/musicText.js';

const musicOrigin = 'https://music.youtube.com';
export const youtubeOrigin = 'https://www.youtube.com';
const defaultClientVersion = '1.20260213.01.00';
export const defaultWebClientVersion = '2.20260910.00.00';
const defaultUserAgent = 'Mozilla/5.0 AppleWebKit/537.36 Chrome/126 Safari/537.36';
export const maxPlaylistPages = 20;
// Grid pages (moods, genres, "more" shelves) continue beneath their last shelf.
export const maxBrowsePages = 20;
// WEB_REMIX musicSearchType filters, matching youtubei.js's SearchFilter encoding.
const musicSearchParams = {
  song: 'EgWKAQIIAQ%3D%3D',
  album: 'EgWKAQIYAQ%3D%3D',
  video: 'EgWKAQIQAQ%3D%3D',
  artist: 'EgWKAQIgAQ%3D%3D',
  playlist: 'EgWKAQIoAQ%3D%3D'
};

export const normalizers = createBrowseNormalizers({
  asText,
  bestThumbnail,
  cleanedText,
  findDurationText,
  hasExplicitBadge,
  normalizeTrack,
  normalizedLooseText,
  textParts
});

export const searchUtils = createSearchUtils({
  asText,
  bestThumbnail,
  hasExplicitBadge,
  normalizedLooseText,
  shelfItems: (shelf) => shelf?.items || [],
  textParts
});

export function clean(value) {
  return String(value || '').replace(/\s+/g, ' ').trim();
}

export function sessionFrom(payload = {}) {
  return payload.session || payload;
}

export function errorMessage(error) {
  return error instanceof Error ? error.message : String(error || 'YouTube catalog request failed.');
}

function browserContext(session, clientVersion, clientName = 'WEB_REMIX') {
  return {
    context: {
      client: {
        clientName,
        clientVersion,
        hl: 'en',
        gl: 'US',
        visitorData: clean(session.visitorData) || undefined
      },
      user: clean(session.dataSyncId)
        ? { onBehalfOfUser: clean(session.dataSyncId) }
        : undefined
    }
  };
}

export function createBrowseRequest(session, options = {}, fetchImpl = globalThis.fetch) {
  const origin = options.origin || musicOrigin;
  const clientName = options.clientName || 'WEB_REMIX';
  const clientNumber = options.clientNumber || '67';
  const cookie = cookieWithPlaybackDefaults(clean(session.cookie));
  const authorization = browserAuthHeader(cookie, origin);
  if (!options.guest && (!cookie || !authorization)) {
    throw new Error('Authenticated YouTube cookies are required.');
  }

  const clientVersion = options.clientVersion || clean(session.clientVersion) || defaultClientVersion;

  return async function fetchBrowse(request, endpoint = 'browse') {
    const response = await fetchImpl(`${origin}/youtubei/v1/${endpoint}?prettyPrint=false`, {
      method: 'POST',
      headers: {
        ...(!options.guest ? { Authorization: authorization, Cookie: cookie } : {}),
        Accept: 'application/json',
        'Content-Type': 'application/json',
        Origin: origin,
        Referer: `${origin}/`,
        'User-Agent': clean(session.userAgent) || defaultUserAgent,
        'X-Origin': origin,
        'X-YouTube-Client-Name': clientNumber,
        'X-YouTube-Client-Version': clientVersion,
        ...(clean(session.visitorData) ? { 'X-Goog-Visitor-Id': clean(session.visitorData) } : {}),
        ...(!options.guest ? {
          'X-Goog-AuthUser': String(session.accountIndex || 0),
          'X-Youtube-Bootstrap-Logged-In': 'true',
          ...(clean(session.dataSyncId) ? { 'X-Goog-PageId': clean(session.dataSyncId) } : {})
        } : {})
      },
      body: JSON.stringify({
        ...browserContext(session, clientVersion, clientName),
        ...request
      })
    });

    const text = await response.text();
    let data = {};
    try {
      data = text ? JSON.parse(text) : {};
    } catch {
      throw new Error('YouTube returned malformed catalog data.');
    }

    if (!response.ok) {
      throw new Error(data.error?.message || `YouTube catalog request failed with HTTP ${response.status}.`);
    }

    const loggedOut = data.responseContext?.mainAppWebResponseContext?.loggedOut === true
      || (data.responseContext?.serviceTrackingParams || []).some((service) =>
        (service.params || []).some((param) => param.key === 'logged_in' && String(param.value) === '0'));
    if (loggedOut && !options.allowGuestResponse) {
      throw new Error('YouTube did not accept the saved login session. Personalized music is unavailable.');
    }

    return data;
  };
}

// Share raw search normalization between artist metadata and playback selection.
export function createMusicSearch(session = {}, options = {}, fetchImpl = globalThis.fetch) {
  const request = createBrowseRequest(session, options, fetchImpl);
  return async (query, { type } = {}) => {
    const result = await request({ query, params: musicSearchParams[type] }, 'search');
    const tabs = result.contents?.tabbedSearchResultsRenderer?.tabs || [];
    const contents = tabs.flatMap(tab =>
      tab.tabRenderer?.content?.sectionListRenderer?.contents || [])
      .map(normalizers.normalizeBrowseSection);
    const items = contents.flatMap(section => section.items);
    return { contents, songs: { items }, videos: { items } };
  };
}

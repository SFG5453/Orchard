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

import { loadPlayer } from './player.js';
import { resolveHlsPlayback, resolvePlayback, resolveVideoPlayback } from './playback.js';
import {
  accountSummaryFromItem,
  collectAccountRenderers
} from '../auth/accountSummary.js';
import {
  browserAuthHeader,
  cookieWithPlaybackDefaults
} from '../auth/browserMusicApi.js';
import { asText, bestThumbnail } from '../catalog/musicText.js';
import {
  loadUpNext,
  loadHome,
  loadLibrary,
  loadAlbum,
  loadArtist,
  loadBrowse,
  loadPlaylist,
  loadPlaylistPage,
  loadPlaylists,
  loadSearch,
  loadTrack,
  loadYouTubeLyricsText
} from './catalog.js';
import { createLyricsResolver, resolveLyricsChain } from '../catalog/lyricsResolver.js';
import { getNonMusicSegments } from '../integrations/sponsorblock.js';
import { createRuntimeHistory } from './history.js';
import { createRuntimeLibrary } from './library.js';

const musicOrigin = 'https://music.youtube.com';
const webOrigin = 'https://www.youtube.com';
const defaultClientVersion = '1.20260213.01.00';
const defaultWebClientVersion = '2.20260910.00.00';

function clean(value) {
  return String(value || '').replace(/\s+/g, ' ').trim();
}

function normalizedHandle(value) {
  const text = clean(value);
  if (!text || /subscribers?/i.test(text) || /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(text)) return '';
  const match = text.match(/@[\p{L}\p{N}._-]+/u);
  return match?.[0] || '';
}

function normalizedProfile(value = {}) {
  const name = clean(value.name);
  const handle = normalizedHandle(value.handle || value.byline);
  const avatarUrl = clean(value.avatarUrl || value.thumbnail);
  const channelId = clean(value.channelId);
  const channelUrl = clean(value.channelUrl) || (channelId ? `https://www.youtube.com/channel/${encodeURIComponent(channelId)}` : '');
  return { name, handle, avatarUrl, channelId, channelUrl };
}

function score(profile) {
  const value = normalizedProfile(profile);
  return Number(Boolean(value.avatarUrl)) * 4
    + Number(Boolean(value.name && !/^(signed in|youtube music)$/i.test(value.name))) * 2
    + Number(Boolean(value.handle)) * 2
    + Number(Boolean(value.channelId || value.channelUrl));
}

function mergeProfiles(...profiles) {
  const ordered = profiles.map(normalizedProfile).sort((a, b) => score(b) - score(a));
  const result = { name: '', handle: '', avatarUrl: '', channelId: '', channelUrl: '' };
  for (const profile of ordered) {
    for (const key of Object.keys(result)) {
      if (!result[key] && profile[key]) result[key] = profile[key];
    }
  }
  return result;
}

function decodeHtml(value = '') {
  return String(value)
    .replace(/&amp;/g, '&')
    .replace(/&quot;/g, '"')
    .replace(/&#39;/g, "'")
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>');
}

function metaContent(html, property) {
  const escaped = property.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  const expression = new RegExp(`<meta[^>]+(?:property|name)=["']${escaped}["'][^>]+content=["']([^"']+)["']`, 'i');
  return decodeHtml(html.match(expression)?.[1] || '');
}

async function enrichPublicProfile(profile) {
  const normalized = normalizedProfile(profile);
  if ((!normalized.channelId && !normalized.channelUrl) || (normalized.name && normalized.avatarUrl)) return normalized;

  const response = await fetch(normalized.channelUrl, {
    headers: {
      Accept: 'text/html',
      'User-Agent': 'Mozilla/5.0 AppleWebKit/537.36 Chrome/126 Safari/537.36'
    }
  });
  if (!response.ok) return normalized;
  const html = await response.text();
  return mergeProfiles(normalized, {
    name: metaContent(html, 'og:title'),
    avatarUrl: metaContent(html, 'og:image')
  });
}

function selectedAccount(data) {
  const accounts = collectAccountRenderers(data);
  return accounts.find((item) => item.isSelected || item.is_selected) || accounts[0] || null;
}

async function resolveClientVersion(session) {
  const configured = clean(session.clientVersion);
  if (configured) return configured;
  try {
    const response = await fetch(`${musicOrigin}/`, {
      headers: {
        Accept: 'text/html',
        'User-Agent': clean(session.userAgent) || 'Mozilla/5.0 AppleWebKit/537.36 Chrome/126 Safari/537.36'
      }
    });
    if (response.ok) {
      const html = await response.text();
      const discovered = html.match(/["']INNERTUBE_CLIENT_VERSION["']\s*:\s*["']([^"']+)/)?.[1];
      if (discovered) return discovered;
    }
  } catch {
    // The authenticated request below can still work with the bundled fallback.
  }
  return defaultClientVersion;
}

async function loadAccountProfile(payload = {}) {
  const session = payload.session || payload;
  const cookie = cookieWithPlaybackDefaults(clean(session.cookie));
  if (!cookie || !browserAuthHeader(cookie, musicOrigin)) {
    throw new Error('Authenticated YouTube cookies are required.');
  }

  const request = async (origin, clientName, clientNumber, clientVersion, endpoint) => {
    const context = {
      client: {
        clientName, clientVersion, hl: 'en', gl: 'US',
        visitorData: clean(session.visitorData) || undefined
      },
      user: clean(session.dataSyncId) ? { onBehalfOfUser: clean(session.dataSyncId) } : undefined
    };
    const response = await fetch(`${origin}/youtubei/v1/account/${endpoint}?prettyPrint=false`, {
      method: 'POST',
      headers: {
        Authorization: browserAuthHeader(cookie, origin),
        'Content-Type': 'application/json',
        Cookie: cookie,
        Origin: origin,
        Referer: `${origin}/`,
        'User-Agent': clean(session.userAgent) || 'Mozilla/5.0 AppleWebKit/537.36 Chrome/126 Safari/537.36',
        'X-Origin': origin,
        'X-YouTube-Client-Name': clientNumber,
        'X-YouTube-Client-Version': clientVersion,
        ...(clean(session.visitorData) ? { 'X-Goog-Visitor-Id': clean(session.visitorData) } : {}),
        'X-Goog-AuthUser': String(session.accountIndex || 0),
        'X-Youtube-Bootstrap-Logged-In': 'true',
        ...(clean(session.dataSyncId) ? { 'X-Goog-PageId': clean(session.dataSyncId) } : {})
      },
      body: JSON.stringify({
        context,
        requestType: 'ACCOUNTS_LIST_REQUEST_TYPE_CHANNEL_SWITCHER',
        callCircumstance: 'SWITCHING_USERS_FULL'
      })
    });
    if (!response.ok) throw new Error(`YouTube account request failed with HTTP ${response.status}.`);
    return response.json();
  };

  try {
    const clientVersion = await resolveClientVersion(session);
    return parseAccountProfile(await request(musicOrigin, 'WEB_REMIX', '67', clientVersion, 'accounts_list'));
  } catch {
    // Music can return only a channel-selection prompt for a valid session.
    return parseAccountProfile(await request(webOrigin, 'WEB', '1', defaultWebClientVersion, 'account_menu'));
  }
}

function parseAccountProfile(data) {
  const account = selectedAccount(data);
  if (!account) throw new Error("YouTube did not return a signed-in account.");
  const summary = accountSummaryFromItem(account, { asText, bestThumbnail });
  const profile = normalizedProfile(summary || {});
  return enrichPublicProfile(profile);
}

const history = createRuntimeHistory();
const library = createRuntimeLibrary();

const methods = new Map([
  ['runtime.ping', async () => ({ ready: true })],
  ['runtime.fetch', async (payload = {}) => {
    const response = await fetch(String(payload.url || ''), payload.init || {});
    return {
      status: response.status,
      ok: response.ok,
      url: response.url,
      headers: response.headers,
      body: await response.text()
    };
  }],
  ['account.profile', loadAccountProfile],
  ['account.parse', parseAccountProfile],
  ['account.enrich', enrichPublicProfile],
  ['playback.prepare', async () => { await loadPlayer(); return { ready: true }; }],
  ['playback.resolve', resolvePlayback],
  ['playback.resolveVideo', resolveVideoPlayback],
  ['playback.resolveHls', resolveHlsPlayback],
  ['catalog.home', loadHome],
  ['catalog.library', loadLibrary],
  ['catalog.album', loadAlbum],
  ['catalog.artist', loadArtist],
  ['catalog.playlists', loadPlaylists],
  ['catalog.playlist', loadPlaylist],
  ['catalog.playlist.more', loadPlaylistPage],
  ['catalog.upNext', loadUpNext],
  ['catalog.search', loadSearch],
  ['catalog.browse', loadBrowse],
  ['catalog.track', loadTrack],
  ['lyrics.resolve', resolveLyrics],
  ['lyrics.youtube', async (payload = {}) => ({
    text: await loadYouTubeLyricsText({ session: payload.session || {}, videoId: payload.videoId })
  })],
  ['sponsorblock.segments', async (payload = {}) => ({
    videoId: String(payload.videoId || ''),
    segments: await getNonMusicSegments(payload.videoId, payload.durationSeconds, { video: Boolean(payload.video) })
  })],
  ['history.start', history.start],
  ['history.update', history.update],
  ['library.like.status', library.likeStatus],
  ['library.like.set', library.setLike],
  ['library.playlist.targets', library.playlistTargets],
  ['library.playlist.add', library.addToPlaylist],
  ['library.playlist.create', library.createPlaylist],
  ['library.playlist.remove', library.removeFromPlaylist],
  ['library.playlist.move', library.movePlaylistItem],
  ['library.playlist.delete', library.deletePlaylist],
  ['library.artist.subscribe', library.setArtistSubscription]
]);

async function resolveLyrics(payload = {}) {
  const session = payload.session || {};
  const resolver = createLyricsResolver({
    loadYouTubeLyricsText: (videoId) => loadYouTubeLyricsText({ session, videoId })
  });
  const track = payload.track || {};
  return payload.provider ? resolver(track, String(payload.provider)) : resolveLyricsChain(resolver, track);
}

async function invoke(method, payload) {
  const handler = methods.get(String(method || ''));
  if (!handler) throw new Error(`Unknown YouTube provider method: ${method}`);
  return handler(payload);
}

globalThis.OrchardYouTubeProvider = Object.freeze({ invoke });

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

// Widevine streams for catalog songs. Hosts fetch and decrypt the media; this module
// only talks to Apple's playback and license endpoints.

import { AppleMusicError } from './client.js';
import { parseBrowseId } from './ids.js';

const PLAYBACK_URL = 'https://play.itunes.apple.com/WebObjects/MZPlay.woa/wa/webPlayback';
// AAC-LC with AES-CTR first; the cbcs flavors carry the same audio. Standard is Apple's 64 kbps encode.
const HIGH_FLAVORS = ['28:ctrp256', '30:cbcp256', '32:ctrp64', '34:cbcp64'];
const STANDARD_FLAVORS = ['32:ctrp64', '34:cbcp64', '28:ctrp256', '30:cbcp256'];
const WIDEVINE_KEYFORMAT = 'edef8ba9';

function attributes(list) {
  const found = {};
  for (const match of list.matchAll(/([A-Z0-9-]+)=("[^"]*"|[^,]*)/g)) {
    const value = match[2];
    found[match[1]] = value.startsWith('"') ? value.slice(1, -1) : value;
  }
  return found;
}

// QuickJS has no URL; Apple's segment URIs are absolute, rooted or bare file names.
function resolveUrl(base, ref) {
  if (/^[a-z][a-z0-9+.-]*:/i.test(ref)) return ref;
  const path = base.split(/[?#]/)[0];
  if (ref.startsWith('/')) return path.replace(/^([a-z]+:\/\/[^/]+).*$/i, '$1') + ref;
  return path.slice(0, path.lastIndexOf('/') + 1) + ref;
}

// "length@offset"; without an offset the range follows the previous one.
function byteRange(text, follows) {
  const parts = String(text || '').split('@');
  const size = Number(parts[0]);
  const offset = parts.length > 1 ? Number(parts[1]) : follows;
  if (parts.length > 2 || !Number.isSafeInteger(size) || size <= 0 || !Number.isSafeInteger(offset) || offset < 0) return null;
  return [offset, size];
}

/**
 * {media, keyUri, ranges, durations, duration} for one of Apple's audio playlists: a
 * single fragmented MP4 addressed by byte ranges. ranges[0] is the init segment; the
 * ranges tile the file without gaps. Throws on anything else.
 */
export function parsePlaylist(url, text) {
  const lines = String(text || '').split('\n').map((line) => line.trim());
  if (!lines[0]?.startsWith('#EXTM3U')) throw new Error('not an HLS playlist');
  const playlist = { media: '', keyUri: '', ranges: [], durations: [], duration: 0 };
  let pending = null;
  let segmentDuration = 0;
  for (const line of lines) {
    if (line.startsWith('#EXT-X-KEY:')) {
      const key = attributes(line.slice(11));
      const format = key.KEYFORMAT || '';
      const widevine = !format || format.toLowerCase().includes(WIDEVINE_KEYFORMAT);
      if (widevine && !playlist.keyUri && key.METHOD !== 'NONE' && String(key.URI || '').startsWith('data:'))
        playlist.keyUri = key.URI;
    } else if (line.startsWith('#EXT-X-MAP:')) {
      const map = attributes(line.slice(11));
      const range = byteRange(map.BYTERANGE, 0);
      if (playlist.ranges.length || !range) throw new Error('init segment is not a byte range');
      playlist.media = resolveUrl(url, map.URI || '');
      playlist.ranges.push(range);
    } else if (line.startsWith('#EXTINF:')) {
      segmentDuration = Number(line.slice(8).split(',')[0]) || 0;
    } else if (line.startsWith('#EXT-X-BYTERANGE:')) {
      const last = playlist.ranges.at(-1);
      pending = byteRange(line.slice(17), last ? last[0] + last[1] : 0);
      if (!pending) throw new Error('malformed byte range');
    } else if (line && !line.startsWith('#')) {
      if (!pending || !playlist.ranges.length) throw new Error('segment is not a byte range of the init file');
      if (resolveUrl(url, line) !== playlist.media) throw new Error('segments span more than one file');
      const last = playlist.ranges.at(-1);
      if (pending[0] !== last[0] + last[1]) throw new Error('byte ranges leave a gap');
      playlist.ranges.push(pending);
      playlist.durations.push(segmentDuration);
      playlist.duration += segmentDuration;
      segmentDuration = 0;
      pending = null;
    }
  }
  if (!playlist.keyUri) throw new Error('no Widevine key');
  if (playlist.ranges.length < 2 || playlist.ranges[0][0] !== 0) throw new Error('no media segments');
  return playlist;
}

/** The catalog song id behind a track or browse id; library-only songs have none. */
export function playbackId(track = {}) {
  const appleId = String(track.appleId || '');
  if (/^\d+$/.test(appleId)) return appleId;
  const parsed = parseBrowseId(track.id);
  return parsed?.type === 'songs' && /^\d+$/.test(parsed.id) ? parsed.id : '';
}

export function createPlayback(client, fetchImpl) {
  async function post(url, body) {
    const response = await fetchImpl(url, {
      method: 'POST',
      headers: { ...client.playbackHeaders(), 'Content-Type': 'application/json' },
      body: JSON.stringify(body)
    });
    const text = await response.text();
    let json = {};
    try {
      json = text ? JSON.parse(text) : {};
    } catch {
      json = {};
    }
    if (response.status === 401) throw new AppleMusicError(401, 'developer_token', 'Apple Music rejected Orchard\'s developer token.');
    if (response.status === 403) throw new AppleMusicError(403, 'user_token', 'Sign in to Apple Music again.');
    if (!response.ok) throw new AppleMusicError(response.status, 'http', `Apple Music returned ${response.status}.`);
    return json;
  }

  /** The Widevine stream of a catalog song: playlist layout plus what its license needs. */
  async function stream(track, quality = 'high') {
    const appleId = playbackId(track);
    if (!appleId) throw new AppleMusicError(0, 'not_playable', 'This song is only in your library and cannot be streamed.');
    const answer = await post(PLAYBACK_URL, { salableAdamId: appleId });
    const song = answer.songList?.[0];
    if (!song) {
      throw new AppleMusicError(0, answer.failureType ? `playback_${answer.failureType}` : 'not_offered',
        answer.customerMessage || 'Apple Music did not offer this song for playback.');
    }
    const assets = song.assets || [];
    const flavors = quality === 'standard' ? STANDARD_FLAVORS : HIGH_FLAVORS;
    const asset = flavors.map((flavor) => assets.find((item) => item.flavor === flavor)).find((item) => item?.URL);
    const licenseUrl = String(song['hls-key-server-url'] || '');
    if (!asset || !licenseUrl.startsWith('https://'))
      throw new AppleMusicError(0, 'no_widevine', 'Apple Music has no Widevine stream for this song.');
    const response = await fetchImpl(asset.URL, {});
    if (!response.ok) throw new AppleMusicError(response.status, 'http', `Apple Music returned ${response.status}.`);
    let playlist;
    try {
      playlist = parsePlaylist(response.url || asset.URL, await response.text());
    } catch (error) {
      throw new AppleMusicError(0, 'bad_stream', `Apple Music returned a stream Orchard cannot play (${error.message}).`);
    }
    return { appleId, licenseUrl, flavor: asset.flavor, ...playlist };
  }

  /** Enhanced HLS and the playback license endpoint for an Atmos-capable song. */
  async function atmos(id) {
    const appleId = playbackId({ id, appleId: id });
    if (!appleId) throw new AppleMusicError(0, 'not_playable', 'This song cannot be streamed.');
    const catalog = await client.editorial(`/v1/catalog/${client.storefront()}/songs/${appleId}`,
      { extend: 'audioTraits,extendedAssetUrls' }, { personal: true });
    const attributes = catalog?.data?.[0]?.attributes || {};
    const traits = attributes.audioTraits || [];
    if (!Array.isArray(traits) || !traits.some((trait) => trait === 'atmos' || trait === 'dolby-atmos'))
      return { appleId, available: false };
    const master = String(attributes.extendedAssetUrls?.enhancedHls || '');
    if (!master.startsWith('https://')) return { appleId, available: false };
    // The master playlist needs no license, so it loads beside the playback request.
    const [answer, masterText] = await Promise.all([
      post(PLAYBACK_URL, { salableAdamId: appleId }),
      fetchImpl(master, {}).then(async (response) => response.ok ? { url: response.url || master, text: await response.text() } : null)
        .catch(() => null)
    ]);
    const licenseUrl = String(answer.songList?.[0]?.['hls-key-server-url'] || '');
    if (!licenseUrl.startsWith('https://')) throw new AppleMusicError(0, 'no_widevine', 'No Widevine license for this song.');
    return { appleId, available: true, master: masterText?.url || master, licenseUrl, ...(masterText ? { masterText: masterText.text } : {}) };
  }

  /** A music video's HLS master playlist and license endpoint; renditions name their own keys. */
  async function video(id) {
    const parsed = parseBrowseId(id);
    const appleId = parsed ? (parsed.type === 'music-videos' ? parsed.id : '') : String(id || '');
    if (!/^\d+$/.test(appleId)) throw new AppleMusicError(0, 'not_playable', 'This is not an Apple Music video.');
    const answer = await post(PLAYBACK_URL, { salableAdamId: appleId });
    const item = answer.songList?.[0];
    if (!item) {
      throw new AppleMusicError(0, answer.failureType ? `playback_${answer.failureType}` : 'not_offered',
        answer.customerMessage || 'Apple Music did not offer this video for playback.');
    }
    const master = String(item['hls-playlist-url'] || '');
    const licenseUrl = String(item['hls-key-server-url'] || '');
    if (!master.startsWith('https://') || !licenseUrl.startsWith('https://'))
      throw new AppleMusicError(0, 'no_widevine', 'Apple Music has no Widevine stream for this video.');
    return { appleId, licenseUrl, master };
  }

  /** Base64 license for a base64 Widevine challenge from [stream]'s key. */
  async function license({ licenseUrl, challenge, keyUri, appleId } = {}) {
    if (!String(licenseUrl || '').startsWith('https://') || !challenge || !keyUri)
      throw new AppleMusicError(0, 'bad_request', 'The license request is incomplete.');
    const answer = await post(licenseUrl, {
      challenge,
      'key-system': 'com.widevine.alpha',
      uri: keyUri,
      // Library songs license by their catalog id too.
      adamId: String(appleId || ''),
      isLibrary: false,
      'user-initiated': true
    });
    if (answer.status !== 0 || !answer.license)
      throw new AppleMusicError(0, 'license_refused', `Apple Music refused the license (status ${answer.status ?? 'missing'}).`);
    return { license: answer.license };
  }

  return { stream, atmos, video, license };
}

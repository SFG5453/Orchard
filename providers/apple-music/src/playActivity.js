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

const ACTIVITY_URL = 'https://universal-activity-service.itunes.apple.com/play';
const CLIENT_ID = 'JSCLIENT';
const EVENT_TYPE = 'JSPLAY';
const BUILD_VERSION = 'Orchard/1.0 Unidentified OS/0.0 model/Unavailable build/1.0';
// MusicKit's enums: event 0 ends a play and 1 starts one; item type 1 is a song (4 is radio).
const PLAY_END = 0;
const PLAY_START = 1;
const SONG = 1;
const SOURCE_MUSICKIT = 10;
const CONTAINER_TYPES = { playlist: 2, album: 3, artist: 4 };
const END_REASONS = { natural: 7, skipped: 2, paused: 3, selected: 5, other: 1 };

function uuid() {
  const bytes = Array.from({ length: 16 }, () => Math.floor(Math.random() * 256));
  bytes[6] = (bytes[6] & 0x0f) | 0x40;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;
  const hex = bytes.map((byte) => byte.toString(16).padStart(2, '0')).join('');
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

function containerFields(container) {
  const type = CONTAINER_TYPES[container?.kind];
  if (!type || !container.id) return {};
  const key = type === CONTAINER_TYPES.album ? 'album-adam-id' : type === CONTAINER_TYPES.playlist ? 'global-playlist-id' : '';
  return { 'container-type': type, ...(key ? { 'container-ids': { [key]: container.id } } : {}) };
}

/** The data item MusicKit sends for one catalog song; [event] is 'start' or 'end'. */
export function buildPlayEvent(event, play, session) {
  const ending = event === 'end';
  const durationMs = Math.max(0, Math.round(Number(play.durationMs) || 0));
  const startMs = Math.max(0, Math.round(Number(play.startPositionMs) || 0));
  const item = {
    'build-version': BUILD_VERSION,
    'developer-token': session.developerToken,
    'event-type': ending ? PLAY_END : PLAY_START,
    'feature-name': play.container?.name || 'musickit',
    ids: { 'subscription-adam-id': String(play.id) },
    'internal-build': false,
    'media-duration-in-milliseconds': durationMs,
    'media-type': 0,
    offline: false,
    'persistent-id': play.persistentId,
    'play-mode': { 'auto-play-mode': 0, 'repeat-play-mode': 0, 'shuffle-play-mode': play.shuffle ? 1 : 0 },
    'private-enabled': false,
    'sb-enabled': true,
    'siri-initiated': false,
    'source-type': SOURCE_MUSICKIT,
    'start-position-in-milliseconds': startMs,
    'store-front': session.storefront,
    type: SONG,
    'user-agent': 'Orchard/1.0',
    'user-token': session.userToken,
    'utc-offset-in-seconds': -new Date().getTimezoneOffset() * 60,
    ...containerFields(play.container)
  };
  if (ending) {
    const endMs = Math.max(startMs, Math.round(Number(play.endPositionMs) || 0));
    item['end-position-in-milliseconds'] = endMs;
    item['end-reason-type'] = END_REASONS[play.reason] ?? END_REASONS.other;
  }
  item['milliseconds-since-play'] = ending ? Math.max(0, Math.round(Number(play.elapsedMs) || 0)) : 0;
  return item;
}

/** Posts Apple's play activity so a song played here lands in the listener's recents. */
export function createPlayActivity({ fetchImpl, session }) {
  async function send(event, play) {
    const current = session() || {};
    if (!current.developerToken || !current.userToken) {
      throw new AppleMusicError(0, 'not_signed_in', 'Sign in to Apple Music first.');
    }
    const id = String(play?.id || '');
    if (!/^\d+$/.test(id)) return { recorded: false };
    const persistentId = String(play.persistentId || uuid());
    const item = buildPlayEvent(event, { ...play, id, persistentId }, current);
    const headers = {
      Authorization: `Bearer ${current.developerToken}`,
      'Content-Type': 'application/json',
      'X-Apple-Music-User-Token': current.userToken
    };
    if (current.origin) headers.Origin = current.origin;
    const body = JSON.stringify({
      client_id: CLIENT_ID,
      event_type: EVENT_TYPE,
      data: [item]
    });
    const response = await fetchImpl(ACTIVITY_URL, { method: 'POST', headers, body });
    if (!response.ok) throw new AppleMusicError(response.status, 'http', `Apple Music returned ${response.status}.`);
    return { recorded: true, persistentId };
  }

  return {
    playStarted: (play) => send('start', play),
    playEnded: (play) => send('end', play)
  };
}

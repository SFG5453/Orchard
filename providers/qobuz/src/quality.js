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

const QUALITY_FIELDS = Object.freeze({
  bitDepth: ['maximum_bit_depth', 'bit_depth'],
  sampleRate: ['maximum_sampling_rate', 'sampling_rate'],
  channels: ['maximum_channel_count', 'channel_count']
});

function present(value) {
  return value !== undefined && value !== null && value !== '';
}

function firstPresent(...values) {
  return values.find(present);
}

function numberValue(value) {
  if (!present(value)) return undefined;
  const number = Number(value);
  return Number.isFinite(number) && number > 0 ? number : undefined;
}

function booleanValue(value) {
  if (typeof value === 'boolean') return value;
  if (value === 1 || value === '1' || value === 'true') return true;
  if (value === 0 || value === '0' || value === 'false') return false;
  return undefined;
}

function numericIdValue(value, fallback) {
  const id = Number(value ?? fallback);
  return Number.isSafeInteger(id) && id > 0 ? id : Number(fallback);
}

function stringIdValue(value, fallback) {
  const id = value ?? fallback;
  return id === undefined || id === null ? undefined : String(id);
}

function addIfKnown(target, key, value) {
  if (value !== undefined) target[key] = value;
}

function normalizeQuality(raw = {}, fallbackId, idKey, normalizeId) {
  const audioInfo = raw.audio_info && typeof raw.audio_info === 'object' ? raw.audio_info : {};
  const rights = raw.rights && typeof raw.rights === 'object' ? raw.rights : {};
  const result = {};
  const id = normalizeId(raw.id, fallbackId);
  if (id !== undefined) result[idKey] = id;

  const bitDepth = numberValue(firstPresent(
    audioInfo[QUALITY_FIELDS.bitDepth[0]],
    audioInfo[QUALITY_FIELDS.bitDepth[1]],
    raw[QUALITY_FIELDS.bitDepth[0]],
    raw[QUALITY_FIELDS.bitDepth[1]]
  ));
  const sampleRateKHz = numberValue(firstPresent(
    audioInfo[QUALITY_FIELDS.sampleRate[0]],
    audioInfo[QUALITY_FIELDS.sampleRate[1]],
    raw[QUALITY_FIELDS.sampleRate[0]],
    raw[QUALITY_FIELDS.sampleRate[1]]
  ));
  const channels = numberValue(firstPresent(
    audioInfo[QUALITY_FIELDS.channels[0]],
    audioInfo[QUALITY_FIELDS.channels[1]],
    raw[QUALITY_FIELDS.channels[0]],
    raw[QUALITY_FIELDS.channels[1]]
  ));
  const hiresStreamable = booleanValue(firstPresent(
    raw.hires_streamable,
    raw.hiresStreamable,
    rights.hires_streamable,
    rights.hiresStreamable,
    audioInfo.hires_streamable,
    audioInfo.hiresStreamable
  ));
  const streamable = booleanValue(firstPresent(
    raw.streamable,
    rights.streamable,
    audioInfo.streamable
  ));

  addIfKnown(result, 'bitDepth', bitDepth);
  addIfKnown(result, 'sampleRate', sampleRateKHz === undefined ? undefined : sampleRateKHz * 1000);
  addIfKnown(result, 'channels', channels);
  addIfKnown(result, 'hiresStreamable', hiresStreamable);
  addIfKnown(result, 'streamable', streamable);
  return result;
}

function unwrap(raw, key) {
  return raw?.[key] && typeof raw[key] === 'object' && !Array.isArray(raw[key]) ? raw[key] : raw || {};
}

export function normalizeQobuzAlbumQuality(raw = {}, fallbackId) {
  return normalizeQuality(unwrap(raw, 'album'), fallbackId, 'albumId', stringIdValue);
}

export function normalizeQobuzTrackQuality(raw = {}, fallbackId) {
  return normalizeQuality(unwrap(raw, 'track'), fallbackId, 'trackId', numericIdValue);
}

export function qobuzTrackItems(raw = {}) {
  if (Array.isArray(raw)) return raw;
  const tracks = raw?.tracks;
  if (Array.isArray(tracks)) return tracks;
  if (Array.isArray(tracks?.items)) return tracks.items;
  if (Array.isArray(raw?.items)) return raw.items;
  if (Array.isArray(raw?.track)) return raw.track;
  return [];
}

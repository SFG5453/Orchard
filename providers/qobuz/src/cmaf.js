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
  ascii, bytes, bytesToHex, concatBytes, decodeBase64, equalBytes, hexToBytes,
  indexOfBytes, readU16, readU24, readU32, readU64, writeU16, writeU24, writeU64
} from './bytes.js';

const QBZ_INIT_UUID = hexToBytes('c7c75df0fdd951e98fc22971e4acf8d2');
const QBZ_SEGMENT_UUID = hexToBytes('3b42129256f35f75923663b69a1f52b2');
const FLAC_METADATA_SEEKTABLE = 3;
const FLAC_SEEK_POINT_BYTES = 18;
const FLAC_MARKER = Uint8Array.of(0x66, 0x4c, 0x61, 0x43);

function boxes(input) {
  const data = bytes(input);
  const found = [];
  for (let offset = 0; offset + 8 <= data.length;) {
    let size = readU32(data, offset);
    let headerSize = 8;
    if (size === 1) {
      if (offset + 16 > data.length) break;
      const extended = readU64(data, offset + 8);
      if (extended > BigInt(Number.MAX_SAFE_INTEGER)) break;
      size = Number(extended);
      headerSize = 16;
    } else if (size === 0) {
      size = data.length - offset;
    }
    if (size < headerSize || offset + size > data.length) break;
    found.push({ offset, size, end: offset + size, headerSize, type: ascii(data, offset + 4, offset + 8) });
    offset += size;
  }
  return found;
}

function seekableFlacHeader(streamInfoInput, segmentTable, totalSamples) {
  const header = bytes(streamInfoInput).slice();
  const maximumBlockSize = readU16(header, 10);
  const frameSamples = maximumBlockSize >= 16 ? maximumBlockSize : 0;
  const points = [];
  let sampleOffset = 0;
  for (const entry of segmentTable) {
    entry.sampleOffset = sampleOffset;
    if (entry.sampleCount > 0 && sampleOffset < totalSamples) {
      points.push({ sampleNumber: sampleOffset, streamOffset: entry.byteOffset, frameSamples });
    }
    sampleOffset += entry.sampleCount;
  }
  if (!points.length) {
    header[4] |= 0x80;
    return { flacHeader: header, seekPoints: [], tableSamples: sampleOffset };
  }

  header[4] &= 0x7f;
  const seekTable = new Uint8Array(4 + points.length * FLAC_SEEK_POINT_BYTES);
  seekTable[0] = 0x80 | FLAC_METADATA_SEEKTABLE;
  writeU24(seekTable, 1, points.length * FLAC_SEEK_POINT_BYTES);
  points.forEach((point, index) => {
    const offset = 4 + index * FLAC_SEEK_POINT_BYTES;
    writeU64(seekTable, offset, point.sampleNumber);
    writeU64(seekTable, offset + 8, point.streamOffset);
    writeU16(seekTable, offset + 16, point.frameSamples);
  });
  return { flacHeader: concatBytes([header, seekTable]), seekPoints: points, tableSamples: sampleOffset };
}

export function parseQobuzInitSegment(input) {
  const data = bytes(input);
  const box = boxes(data).find((candidate) => candidate.type === 'uuid' && equalBytes(
    data.subarray(candidate.offset + candidate.headerSize, candidate.offset + candidate.headerSize + 16),
    QBZ_INIT_UUID
  ));
  if (!box) throw new Error('Qobuz init segment does not contain its stream descriptor');

  let cursor = box.offset + box.headerSize + 16;
  if (cursor + 28 > box.end) throw new Error('Qobuz init stream descriptor is truncated');
  cursor += 26;
  const rawLength = readU16(data, cursor);
  cursor += 2;
  const rawEnd = Math.min(box.end, cursor + rawLength);
  const raw = data.subarray(cursor, rawEnd);
  cursor = rawEnd;
  const flacOffset = indexOfBytes(raw, FLAC_MARKER);
  if (flacOffset < 0 || flacOffset + 42 > raw.length) {
    throw new Error('Qobuz init segment does not contain complete FLAC stream info');
  }
  const streamInfo = raw.slice(flacOffset, flacOffset + 42);

  const segmentTable = [];
  if (cursor < box.end) {
    const keyIdLength = data[cursor];
    cursor += 1 + keyIdLength;
    if (cursor + 2 <= box.end) {
      const count = readU16(data, cursor);
      cursor += 2;
      for (let index = 0; index < count && cursor + 8 <= box.end; index += 1) {
        segmentTable.push({ byteLength: readU32(data, cursor), sampleCount: readU32(data, cursor + 4) });
        cursor += 8;
      }
    }
  }
  if (!segmentTable.length) throw new Error('Qobuz init segment has no audio segment table');

  let byteOffset = 0;
  for (const entry of segmentTable) {
    entry.byteOffset = byteOffset;
    byteOffset += entry.byteLength;
  }
  const packed = readU64(streamInfo, 18);
  const totalSamples = Number(packed & 0xfffffffffn);
  const { flacHeader, seekPoints, tableSamples } = seekableFlacHeader(streamInfo, segmentTable, totalSamples);
  return {
    flacHeader, segmentTable, totalLength: flacHeader.length + byteOffset,
    sampleRate: Number(packed >> 44n), channels: Number((packed >> 41n) & 0x7n) + 1,
    bitDepth: Number((packed >> 36n) & 0x1fn) + 1, totalSamples, seekPoints, tableSamples
  };
}

export function parseQobuzAudioSegment(input) {
  const data = bytes(input);
  let descriptor = null;
  let mediaEnd = data.length;
  for (const box of boxes(data)) {
    if (box.type === 'mdat') mediaEnd = box.end;
    if (box.type === 'uuid' && equalBytes(
      data.subarray(box.offset + box.headerSize, box.offset + box.headerSize + 16), QBZ_SEGMENT_UUID
    )) descriptor = box;
  }
  if (!descriptor) throw new Error('Qobuz audio segment does not contain its frame descriptor');

  let cursor = descriptor.offset + descriptor.headerSize + 16;
  if (cursor + 12 > descriptor.end) throw new Error('Qobuz audio frame descriptor is truncated');
  cursor += 4;
  const dataOffset = descriptor.offset + readU32(data, cursor);
  cursor += 4;
  const ivSize = data[cursor];
  cursor += 1;
  const frameCount = readU24(data, cursor);
  cursor += 3;
  if (ivSize < 1 || ivSize > 16 || cursor + frameCount * (8 + ivSize) > descriptor.end) {
    throw new Error('Qobuz audio frame table has an unknown format');
  }
  const frames = [];
  for (let index = 0; index < frameCount; index += 1) {
    const size = readU32(data, cursor);
    const skip = readU16(data, cursor + 4);
    const flags = readU16(data, cursor + 6);
    cursor += 8;
    const iv = new Uint8Array(16);
    iv.set(data.subarray(cursor, cursor + Math.min(ivSize, 16)));
    cursor += ivSize;
    frames.push({ flags, iv, size, skip });
  }
  return { data, dataOffset, frames, mediaEnd };
}

function capability(crypto, name) {
  if (typeof crypto?.[name] !== 'function') throw new TypeError(`Qobuz playback requires host crypto.${name}()`);
  return crypto[name].bind(crypto);
}

export function deriveQobuzSessionKey(infos, rngInit, crypto) {
  const [saltValue, infoValue] = String(infos || '').split('.');
  if (!saltValue || !infoValue || !/^[a-f\d]{32}$/i.test(rngInit || '')) {
    throw new Error('Qobuz session key data is incomplete');
  }
  return bytes(capability(crypto, 'hkdfSha256')({
    key: hexToBytes(rngInit), salt: decodeBase64(saltValue), info: decodeBase64(infoValue), length: 16
  }));
}

export function unwrapQobuzContentKey(sessionKey, wrappedValue, crypto) {
  const [profile, encryptedValue, ivValue] = String(wrappedValue || '').split('.');
  if (profile !== 'qbz-1' || !encryptedValue || !ivValue) {
    throw new Error('Qobuz content key has an unknown format');
  }
  const key = bytes(capability(crypto, 'decryptAes128Cbc')({
    key: bytes(sessionKey), iv: decodeBase64(ivValue), data: decodeBase64(encryptedValue)
  }));
  if (key.length !== 16) throw new Error('Qobuz content key has an invalid length');
  return key;
}

export function decryptQobuzAudioSegment(input, contentKey, crypto) {
  const parsed = parseQobuzAudioSegment(input);
  let position = parsed.dataOffset;
  const chunks = [];
  for (const frame of parsed.frames) {
    const end = position + frame.size;
    if (end > parsed.data.length) throw new Error('Qobuz audio segment ends inside a FLAC frame');
    let frameBytes = parsed.data.slice(position, end);
    if (frame.flags !== 0) {
      if (!contentKey) throw new Error('Qobuz encrypted audio segment has no content key');
      frameBytes = bytes(capability(crypto, 'decryptAes128Ctr')({ key: bytes(contentKey), iv: frame.iv, data: frameBytes }));
    }
    chunks.push(frameBytes);
    position = end;
  }
  if (position < parsed.mediaEnd) chunks.push(parsed.data.subarray(position, parsed.mediaEnd));
  return concatBytes(chunks);
}

export const QOBUZ_CMAF_UUIDS = Object.freeze({ init: bytesToHex(QBZ_INIT_UUID), segment: bytesToHex(QBZ_SEGMENT_UUID) });

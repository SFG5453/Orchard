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

import { createDecipheriv, hkdfSync } from 'node:crypto';

const QBZ_INIT_UUID = Buffer.from('c7c75df0fdd951e98fc22971e4acf8d2', 'hex');
const QBZ_SEGMENT_UUID = Buffer.from('3b42129256f35f75923663b69a1f52b2', 'hex');
const FLAC_METADATA_SEEKTABLE = 3;
const FLAC_SEEK_POINT_BYTES = 18;

function boxes(data) {
  const found = [];
  for (let offset = 0; offset + 8 <= data.length;) {
    let size = data.readUInt32BE(offset);
    let headerSize = 8;
    if (size === 1) {
      if (offset + 16 > data.length) break;
      const extended = data.readBigUInt64BE(offset + 8);
      if (extended > BigInt(Number.MAX_SAFE_INTEGER)) break;
      size = Number(extended);
      headerSize = 16;
    } else if (size === 0) {
      size = data.length - offset;
    }
    if (size < headerSize || offset + size > data.length) break;
    found.push({ offset, size, end: offset + size, headerSize, type: data.toString('ascii', offset + 4, offset + 8) });
    offset += size;
  }
  return found;
}

function seekableFlacHeader(streamInfo, segmentTable, totalSamples) {
  const header = Buffer.from(streamInfo);
  const maximumBlockSize = header.readUInt16BE(10);
  const frameSamples = maximumBlockSize >= 16 ? maximumBlockSize : 0;
  const points = [];
  let sampleOffset = 0;

  for (const entry of segmentTable) {
    entry.sampleOffset = sampleOffset;
    if (entry.sampleCount > 0 && sampleOffset < totalSamples) {
      points.push({
        sampleNumber: sampleOffset,
        streamOffset: entry.byteOffset,
        frameSamples
      });
    }
    sampleOffset += entry.sampleCount;
  }

  if (!points.length) {
    header[4] |= 0x80;
    return { flacHeader: header, seekPoints: [], tableSamples: sampleOffset };
  }

  // STREAMINFO is no longer the final metadata block; the synthesized
  // SEEKTABLE follows it and is the final block before the first audio frame.
  header[4] &= 0x7f;
  const seekTable = Buffer.alloc(4 + points.length * FLAC_SEEK_POINT_BYTES);
  seekTable[0] = 0x80 | FLAC_METADATA_SEEKTABLE;
  seekTable.writeUIntBE(points.length * FLAC_SEEK_POINT_BYTES, 1, 3);
  points.forEach((point, index) => {
    const offset = 4 + index * FLAC_SEEK_POINT_BYTES;
    seekTable.writeBigUInt64BE(BigInt(point.sampleNumber), offset);
    seekTable.writeBigUInt64BE(BigInt(point.streamOffset), offset + 8);
    seekTable.writeUInt16BE(point.frameSamples, offset + 16);
  });
  return {
    flacHeader: Buffer.concat([header, seekTable]),
    seekPoints: points,
    tableSamples: sampleOffset
  };
}

export function parseQobuzInitSegment(input) {
  const data = Buffer.from(input);
  const box = boxes(data).find((candidate) =>
    candidate.type === 'uuid' &&
    data.subarray(candidate.offset + candidate.headerSize, candidate.offset + candidate.headerSize + 16).equals(QBZ_INIT_UUID)
  );
  if (!box) throw new Error('Qobuz init segment does not contain its stream descriptor');

  let cursor = box.offset + box.headerSize + 16;
  if (cursor + 28 > box.end) throw new Error('Qobuz init stream descriptor is truncated');
  cursor += 26;
  const rawLength = data.readUInt16BE(cursor);
  cursor += 2;
  const rawEnd = Math.min(box.end, cursor + rawLength);
  const raw = data.subarray(cursor, rawEnd);
  cursor = rawEnd;
  const flacOffset = raw.indexOf(Buffer.from('fLaC'));
  if (flacOffset < 0 || flacOffset + 42 > raw.length) {
    throw new Error('Qobuz init segment does not contain complete FLAC stream info');
  }
  const streamInfo = Buffer.from(raw.subarray(flacOffset, flacOffset + 42));

  const segmentTable = [];
  if (cursor < box.end) {
    const keyIdLength = data[cursor];
    cursor += 1 + keyIdLength;
    if (cursor + 2 <= box.end) {
      const count = data.readUInt16BE(cursor);
      cursor += 2;
      for (let index = 0; index < count && cursor + 8 <= box.end; index += 1) {
        segmentTable.push({
          byteLength: data.readUInt32BE(cursor),
          sampleCount: data.readUInt32BE(cursor + 4)
        });
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

  const packed = streamInfo.readBigUInt64BE(18);
  const totalSamples = Number(packed & 0xfffffffffn);
  const { flacHeader, seekPoints, tableSamples } = seekableFlacHeader(
    streamInfo,
    segmentTable,
    totalSamples
  );
  return {
    flacHeader,
    segmentTable,
    totalLength: flacHeader.length + byteOffset,
    sampleRate: Number(packed >> 44n),
    channels: Number((packed >> 41n) & 0x7n) + 1,
    bitDepth: Number((packed >> 36n) & 0x1fn) + 1,
    totalSamples,
    seekPoints,
    tableSamples
  };
}

export function parseQobuzAudioSegment(input) {
  const data = Buffer.from(input);
  let descriptor = null;
  let mediaEnd = data.length;
  for (const box of boxes(data)) {
    if (box.type === 'mdat') mediaEnd = box.end;
    if (box.type === 'uuid' && data.subarray(box.offset + box.headerSize, box.offset + box.headerSize + 16).equals(QBZ_SEGMENT_UUID)) {
      descriptor = box;
    }
  }
  if (!descriptor) throw new Error('Qobuz audio segment does not contain its frame descriptor');

  let cursor = descriptor.offset + descriptor.headerSize + 16;
  if (cursor + 12 > descriptor.end) throw new Error('Qobuz audio frame descriptor is truncated');
  cursor += 4;
  const dataOffset = descriptor.offset + data.readUInt32BE(cursor);
  cursor += 4;
  const ivSize = data[cursor];
  cursor += 1;
  const frameCount = data.readUIntBE(cursor, 3);
  cursor += 3;
  if (ivSize < 1 || ivSize > 16 || cursor + frameCount * (8 + ivSize) > descriptor.end) {
    throw new Error('Qobuz audio frame table has an unknown format');
  }

  const frames = [];
  for (let index = 0; index < frameCount; index += 1) {
    const size = data.readUInt32BE(cursor);
    const skip = data.readUInt16BE(cursor + 4);
    const flags = data.readUInt16BE(cursor + 6);
    cursor += 8;
    const iv = Buffer.alloc(16);
    data.copy(iv, 0, cursor, cursor + Math.min(ivSize, 16));
    cursor += ivSize;
    frames.push({ flags, iv, size, skip });
  }
  return { data, dataOffset, frames, mediaEnd };
}

function decodeBase64Url(value) {
  return Buffer.from(String(value || ''), 'base64url');
}

export function deriveQobuzSessionKey(infos, rngInit) {
  const [saltValue, infoValue] = String(infos || '').split('.');
  if (!saltValue || !infoValue || !/^[a-f\d]{32}$/i.test(rngInit || '')) {
    throw new Error('Qobuz session key data is incomplete');
  }
  return Buffer.from(hkdfSync(
    'sha256',
    Buffer.from(rngInit, 'hex'),
    decodeBase64Url(saltValue),
    decodeBase64Url(infoValue),
    16
  ));
}

export function unwrapQobuzContentKey(sessionKey, wrappedValue) {
  const [profile, encryptedValue, ivValue] = String(wrappedValue || '').split('.');
  if (profile !== 'qbz-1' || !encryptedValue || !ivValue) {
    throw new Error('Qobuz content key has an unknown format');
  }
  const decipher = createDecipheriv('aes-128-cbc', sessionKey, decodeBase64Url(ivValue));
  const key = Buffer.concat([decipher.update(decodeBase64Url(encryptedValue)), decipher.final()]);
  if (key.length !== 16) throw new Error('Qobuz content key has an invalid length');
  return key;
}

export function decryptQobuzAudioSegment(input, contentKey) {
  const parsed = parseQobuzAudioSegment(input);
  let position = parsed.dataOffset;
  const chunks = [];
  for (const frame of parsed.frames) {
    const end = position + frame.size;
    if (end > parsed.data.length) throw new Error('Qobuz audio segment ends inside a FLAC frame');
    let bytes = Buffer.from(parsed.data.subarray(position, end));
    if (frame.flags !== 0) {
      if (!contentKey) throw new Error('Qobuz encrypted audio segment has no content key');
      const decipher = createDecipheriv('aes-128-ctr', contentKey, frame.iv);
      bytes = Buffer.concat([decipher.update(bytes), decipher.final()]);
    }
    chunks.push(bytes);
    position = end;
  }
  if (position < parsed.mediaEnd) chunks.push(parsed.data.subarray(position, parsed.mediaEnd));
  return Buffer.concat(chunks);
}

export const QOBUZ_CMAF_UUIDS = Object.freeze({
  init: QBZ_INIT_UUID.toString('hex'),
  segment: QBZ_SEGMENT_UUID.toString('hex')
});

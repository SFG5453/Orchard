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

// Container-level WebP parser. It checks structure and reads geometry and
// timing. Pixel data is never decoded; the client and browsers do that.

const IMAGE_CHUNKS = new Set(["VP8 ", "VP8L"]);

export class WebpError extends Error {}

function fourcc(bytes, offset) {
  return String.fromCharCode(bytes[offset], bytes[offset + 1], bytes[offset + 2], bytes[offset + 3]);
}

function u24(bytes, offset) {
  return bytes[offset] | (bytes[offset + 1] << 8) | (bytes[offset + 2] << 16);
}

function u32(bytes, offset) {
  return (bytes[offset] | (bytes[offset + 1] << 8) | (bytes[offset + 2] << 16) | (bytes[offset + 3] << 24)) >>> 0;
}

// Yields [fourcc, payloadStart, payloadSize] for each chunk in [start, end).
function* chunks(bytes, start, end) {
  let offset = start;
  while (offset < end) {
    if (offset + 8 > end) throw new WebpError("Truncated chunk header");
    const id = fourcc(bytes, offset);
    const size = u32(bytes, offset + 4);
    const payload = offset + 8;
    if (payload + size > end) throw new WebpError(`Chunk ${id.trim()} overruns the file`);
    yield [id, payload, size];
    // Chunks are padded to an even length.
    offset = payload + size + (size & 1);
  }
}

function bitstreamSize(bytes, id, payload, size) {
  if (id === "VP8 ") {
    // Key frame start code at bytes 3..5, then 14-bit dimensions.
    if (size < 10 || bytes[payload + 3] !== 0x9d || bytes[payload + 4] !== 0x01 || bytes[payload + 5] !== 0x2a) {
      throw new WebpError("Malformed VP8 bitstream");
    }
    return {
      width: (bytes[payload + 6] | (bytes[payload + 7] << 8)) & 0x3fff,
      height: (bytes[payload + 8] | (bytes[payload + 9] << 8)) & 0x3fff,
    };
  }
  if (size < 5 || bytes[payload] !== 0x2f) throw new WebpError("Malformed VP8L bitstream");
  const bits = u32(bytes, payload + 1);
  return { width: (bits & 0x3fff) + 1, height: ((bits >>> 14) & 0x3fff) + 1 };
}

// Returns { width, height, animated, frames, durationMs, alpha }.
export function parseWebp(input) {
  const bytes = input instanceof Uint8Array ? input : new Uint8Array(input);
  if (bytes.length < 20 || fourcc(bytes, 0) !== "RIFF" || fourcc(bytes, 8) !== "WEBP") {
    throw new WebpError("Not a WebP file");
  }
  const riffEnd = u32(bytes, 4) + 8;
  if (riffEnd > bytes.length) throw new WebpError("Truncated RIFF container");
  if (riffEnd < bytes.length - 1) throw new WebpError("Trailing data after RIFF container");

  const all = [...chunks(bytes, 12, riffEnd)];
  const [firstId, firstPayload, firstSize] = all[0] || [];

  if (IMAGE_CHUNKS.has(firstId)) {
    const { width, height } = bitstreamSize(bytes, firstId, firstPayload, firstSize);
    return { width, height, animated: false, frames: 1, durationMs: 0, alpha: firstId === "VP8L" };
  }
  if (firstId !== "VP8X" || firstSize < 10) throw new WebpError("Missing VP8X header");

  const flags = bytes[firstPayload];
  const width = u24(bytes, firstPayload + 4) + 1;
  const height = u24(bytes, firstPayload + 7) + 1;
  const animated = (flags & 0x02) !== 0;
  const alpha = (flags & 0x10) !== 0;

  if (!animated) {
    const image = all.find(([id]) => IMAGE_CHUNKS.has(id));
    if (!image) throw new WebpError("No image data");
    const size = bitstreamSize(bytes, ...image);
    if (size.width !== width || size.height !== height) throw new WebpError("Image does not match canvas");
    return { width, height, animated: false, frames: 1, durationMs: 0, alpha };
  }

  if (!all.some(([id]) => id === "ANIM")) throw new WebpError("Animated WebP without ANIM chunk");
  let frames = 0;
  let durationMs = 0;
  for (const [id, payload, size] of all) {
    if (id !== "ANMF") continue;
    if (size < 16) throw new WebpError("Truncated ANMF frame");
    const x = u24(bytes, payload) * 2;
    const y = u24(bytes, payload + 3) * 2;
    const frameWidth = u24(bytes, payload + 6) + 1;
    const frameHeight = u24(bytes, payload + 9) + 1;
    if (x + frameWidth > width || y + frameHeight > height) throw new WebpError("Frame exceeds canvas");
    const image = [...chunks(bytes, payload + 16, payload + size)].find(([sub]) => IMAGE_CHUNKS.has(sub));
    if (!image) throw new WebpError("Frame without image data");
    const bitstream = bitstreamSize(bytes, ...image);
    if (bitstream.width !== frameWidth || bitstream.height !== frameHeight) {
      throw new WebpError("Frame bitstream does not match frame header");
    }
    frames += 1;
    durationMs += u24(bytes, payload + 12);
  }
  if (frames === 0) throw new WebpError("Animated WebP without frames");
  return { width, height, animated: true, frames, durationMs, alpha };
}

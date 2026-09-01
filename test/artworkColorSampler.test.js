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

import assert from 'node:assert/strict';
import test from 'node:test';
import sharp from 'sharp';
import {
  sampleArtworkBuffer,
  sampleArtworkPixelData,
  sampleArtworkRawFrame
} from '../electron/appearance/artworkColorSampler.js';

test('raw artwork sampling preserves an exact uniform edge colour in every zone', () => {
  const width = 64;
  const height = 48;
  const channels = 4;
  const pixel = [143, 0, 2, 255];
  const data = Buffer.alloc(width * height * channels);
  for (let offset = 0; offset < data.length; offset += channels) {
    data.set(pixel, offset);
  }

  const sampled = sampleArtworkPixelData(data, { width, height, channels });
  for (const zone of Object.values(sampled.zones)) {
    assert.deepEqual(zone.average, pixel.slice(0, 3));
    assert.deepEqual(zone.median, pixel.slice(0, 3));
    assert.deepEqual(zone.seam, pixel.slice(0, 3));
  }
  assert.deepEqual(sampled.palette.seam, pixel.slice(0, 3));
  assert.deepEqual(sampled.palette.onAccent, [18, 5, 7]);
});

test('sharp decodes the source and samples separate top and bottom edge strips', async () => {
  const width = 120;
  const height = 100;
  const raw = Buffer.alloc(width * height * 3);
  for (let y = 0; y < height; y += 1) {
    const color = y < height / 2 ? [22, 64, 138] : [142, 18, 36];
    for (let x = 0; x < width; x += 1) {
      raw.set(color, ((y * width) + x) * 3);
    }
  }
  const png = await sharp(raw, { raw: { width, height, channels: 3 } }).png().toBuffer();
  const sampled = await sampleArtworkBuffer(png);

  assert.equal(sampled.method, 'sharp-raw-edge-zones-v1');
  assert.deepEqual(sampled.zones.topStrip.seam, [22, 64, 138]);
  assert.deepEqual(sampled.zones.bottomStrip.seam, [142, 18, 36]);
  assert.equal(sampled.width, width);
  assert.equal(sampled.height, height);
});

test('video frames cross the IPC boundary as exact RGBA pixels and are sampled by sharp', async () => {
  const width = 80;
  const height = 64;
  const pixel = [8, 74, 129, 255];
  const data = new Uint8Array(width * height * 4);
  for (let offset = 0; offset < data.length; offset += 4) data.set(pixel, offset);

  const sampled = await sampleArtworkRawFrame({ width, height, channels: 4, data });

  assert.equal(sampled.method, 'sharp-raw-video-frame-edge-zones-v1');
  assert.deepEqual(sampled.palette.seam, pixel.slice(0, 3));
  assert.deepEqual(sampled.zones.rightStrip.seam, pixel.slice(0, 3));
});

test('video frame sampling rejects oversized renderer payloads', async () => {
  await assert.rejects(
    sampleArtworkRawFrame({
      width: 257,
      height: 1,
      channels: 4,
      data: new Uint8Array(257 * 4)
    }),
    /invalid dimensions or pixel data/
  );
});

test('bright or pure white artwork edges are clamped to safe dark-theme lightness', () => {
  const width = 64;
  const height = 48;
  const channels = 4;
  const whitePixel = [255, 255, 255, 255];
  const data = Buffer.alloc(width * height * channels);
  for (let offset = 0; offset < data.length; offset += channels) {
    data.set(whitePixel, offset);
  }

  const sampled = sampleArtworkPixelData(data, { width, height, channels });
  // Lightness should be clamped (max channel value <= 87)
  assert.ok(sampled.palette.seam[0] <= 87);
  assert.ok(sampled.palette.seam[1] <= 87);
  assert.ok(sampled.palette.seam[2] <= 87);
  assert.ok(sampled.palette.surface[0] < 50);
  assert.ok(sampled.palette.deep[0] < 50);
  assert.ok(sampled.palette.ink[0] < 30);
});

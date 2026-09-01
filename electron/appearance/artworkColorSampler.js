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

import sharp from 'sharp';
import { IPC_CHANNELS } from '../../shared/ipcChannels.js';

const ARTWORK_CACHE_LIMIT = 48;
const MAX_ARTWORK_BYTES = 16 * 1024 * 1024;
const MAX_ARTWORK_PIXELS = 40_000_000;
const MAX_VIDEO_FRAME_SIDE = 256;

function clamp(value, minimum, maximum) {
  return Math.min(maximum, Math.max(minimum, value));
}

function channelMedian(histogram, totalWeight) {
  const midpoint = totalWeight / 2;
  let accumulated = 0;
  for (let value = 0; value < histogram.length; value += 1) {
    accumulated += histogram[value];
    if (accumulated >= midpoint) return value;
  }
  return 0;
}

function mixRgb(from, to, amount) {
  const progress = clamp(Number(amount) || 0, 0, 1);
  return from.map((value, index) => Math.round(value + ((to[index] - value) * progress)));
}

function weightedRgb(entries) {
  const totals = [0, 0, 0];
  let totalWeight = 0;
  for (const { rgb, weight = 1 } of entries) {
    if (!Array.isArray(rgb) || rgb.length < 3 || weight <= 0) continue;
    totalWeight += weight;
    for (let channel = 0; channel < 3; channel += 1) totals[channel] += rgb[channel] * weight;
  }
  return totalWeight > 0 ? totals.map((value) => Math.round(value / totalWeight)) : [42, 42, 42];
}

function rgbToHsl(rgb) {
  const [red, green, blue] = rgb.map((value) => value / 255);
  const maximum = Math.max(red, green, blue);
  const minimum = Math.min(red, green, blue);
  const lightness = (maximum + minimum) / 2;
  const delta = maximum - minimum;
  if (delta === 0) return [0, 0, lightness];

  const saturation = delta / (1 - Math.abs((2 * lightness) - 1));
  let hue;
  if (maximum === red) hue = ((green - blue) / delta) % 6;
  else if (maximum === green) hue = ((blue - red) / delta) + 2;
  else hue = ((red - green) / delta) + 4;
  return [((hue * 60) + 360) % 360, saturation, lightness];
}

function hslToRgb(hue, saturation, lightness) {
  const chroma = (1 - Math.abs((2 * lightness) - 1)) * saturation;
  const segment = ((hue % 360) + 360) % 360 / 60;
  const secondary = chroma * (1 - Math.abs((segment % 2) - 1));
  let channels;
  if (segment < 1) channels = [chroma, secondary, 0];
  else if (segment < 2) channels = [secondary, chroma, 0];
  else if (segment < 3) channels = [0, chroma, secondary];
  else if (segment < 4) channels = [0, secondary, chroma];
  else if (segment < 5) channels = [secondary, 0, chroma];
  else channels = [chroma, 0, secondary];
  const offset = lightness - (chroma / 2);
  return channels.map((value) => Math.round((value + offset) * 255));
}

function relativeLuminance(rgb) {
  const channels = rgb.map((value) => {
    const normalized = value / 255;
    return normalized <= 0.04045
      ? normalized / 12.92
      : ((normalized + 0.055) / 1.055) ** 2.4;
  });
  return (channels[0] * 0.2126) + (channels[1] * 0.7152) + (channels[2] * 0.0722);
}

function readableAccentText(accent) {
  const dark = [18, 5, 7];
  const accentLuminance = relativeLuminance(accent);
  const whiteContrast = 1.05 / (accentLuminance + 0.05);
  const darkLuminance = relativeLuminance(dark);
  const darkContrast = (accentLuminance + 0.05) / (darkLuminance + 0.05);
  return darkContrast >= whiteContrast ? dark : [255, 255, 255];
}

function edgeZoneRectangles(width, height) {
  const shortestSide = Math.min(width, height);
  const edge = clamp(Math.round(shortestSide * 0.035), 2, 28);
  const thinEdge = clamp(Math.round(shortestSide * 0.007), 1, 7);
  const corner = clamp(Math.round(shortestSide * 0.14), 8, 112);

  return {
    left: { left: 0, top: 0, width: edge, height },
    right: { left: width - edge, top: 0, width: edge, height },
    top: { left: 0, top: 0, width, height: edge },
    bottom: { left: 0, top: height - edge, width, height: edge },
    leftStrip: { left: 0, top: 0, width: thinEdge, height },
    rightStrip: { left: width - thinEdge, top: 0, width: thinEdge, height },
    topStrip: { left: 0, top: 0, width, height: thinEdge },
    bottomStrip: { left: 0, top: height - thinEdge, width, height: thinEdge },
    topLeft: { left: 0, top: 0, width: corner, height: corner },
    topRight: { left: width - corner, top: 0, width: corner, height: corner },
    bottomRight: { left: width - corner, top: height - corner, width: corner, height: corner },
    bottomLeft: { left: 0, top: height - corner, width: corner, height: corner }
  };
}

const MAX_EDGE_LIGHTNESS = 0.34;

function clampRgbLightness(rgb, maxLightness = MAX_EDGE_LIGHTNESS) {
  const [hue, saturation, lightness] = rgbToHsl(rgb);
  if (lightness <= maxLightness) return rgb;
  return hslToRgb(hue, saturation, maxLightness);
}

function summarizeZone(data, image, rectangle) {
  const histograms = [new Float64Array(256), new Float64Array(256), new Float64Array(256)];
  const sums = [0, 0, 0];
  let totalWeight = 0;

  for (let y = rectangle.top; y < rectangle.top + rectangle.height; y += 1) {
    for (let x = rectangle.left; x < rectangle.left + rectangle.width; x += 1) {
      const offset = ((y * image.width) + x) * image.channels;
      const alpha = image.channels > 3 ? data[offset + 3] / 255 : 1;
      if (alpha < 0.04) continue;
      totalWeight += alpha;
      for (let channel = 0; channel < 3; channel += 1) {
        const value = data[offset + channel];
        sums[channel] += value * alpha;
        histograms[channel][value] += alpha;
      }
    }
  }

  if (totalWeight === 0) {
    return { average: [42, 42, 42], median: [42, 42, 42], seam: [42, 42, 42] };
  }

  const average = clampRgbLightness(sums.map((value) => Math.round(value / totalWeight)));
  const median = clampRgbLightness(histograms.map((histogram) => channelMedian(histogram, totalWeight)));
  return {
    average,
    median,
    // The median prevents one high-contrast subject crossing an edge from
    // shifting the continuation colour, while the mean keeps fine gradients.
    seam: clampRgbLightness(mixRgb(average, median, 0.68))
  };
}

function supportingArtworkHue(data, image, fallbackHue) {
  const bins = new Map();
  const stride = Math.max(1, Math.floor(Math.sqrt((image.width * image.height) / 160_000)));

  for (let y = 0; y < image.height; y += stride) {
    for (let x = 0; x < image.width; x += stride) {
      const offset = ((y * image.width) + x) * image.channels;
      const alpha = image.channels > 3 ? data[offset + 3] / 255 : 1;
      if (alpha < 0.12) continue;
      const red = data[offset];
      const green = data[offset + 1];
      const blue = data[offset + 2];
      const key = ((red >> 4) << 8) | ((green >> 4) << 4) | (blue >> 4);
      const bin = bins.get(key) || { weight: 0, sums: [0, 0, 0] };
      bin.weight += alpha;
      bin.sums[0] += red * alpha;
      bin.sums[1] += green * alpha;
      bin.sums[2] += blue * alpha;
      bins.set(key, bin);
    }
  }

  let best = null;
  for (const bin of bins.values()) {
    const rgb = bin.sums.map((value) => value / bin.weight);
    const [hue, saturation, lightness] = rgbToHsl(rgb);
    if (saturation < 0.34 || lightness < 0.14 || lightness > 0.84) continue;
    const hueDistance = Math.min(Math.abs(hue - fallbackHue), 360 - Math.abs(hue - fallbackHue));
    const score = Math.sqrt(bin.weight) * saturation * (0.24 + lightness) * (hueDistance <= 48 ? 1.18 : 0.82);
    if (!best || score > best.score) best = { hue, score };
  }
  return best ? { hue: best.hue, hasSupporting: true } : { hue: fallbackHue, hasSupporting: false };
}

export function sampleArtworkPixelData(data, image) {
  if (!data || !Number.isInteger(image?.width) || !Number.isInteger(image?.height) || !Number.isInteger(image?.channels)) {
    throw new TypeError('Artwork pixel data requires integer width, height, and channel values.');
  }
  if (image.width <= 0 || image.height <= 0 || image.channels < 3) {
    throw new RangeError('Artwork pixel dimensions and channels must be positive.');
  }
  if (data.length < image.width * image.height * image.channels) {
    throw new RangeError('Artwork pixel data is shorter than its declared dimensions.');
  }

  const rectangles = edgeZoneRectangles(image.width, image.height);
  const zones = Object.fromEntries(
    Object.entries(rectangles).map(([name, rectangle]) => [name, summarizeZone(data, image, rectangle)])
  );
  const seam = clampRgbLightness(weightedRgb([
    { rgb: zones.leftStrip.seam },
    { rgb: zones.rightStrip.seam },
    { rgb: zones.topStrip.seam },
    { rgb: zones.bottomStrip.seam },
    { rgb: zones.topLeft.seam, weight: 0.5 },
    { rgb: zones.topRight.seam, weight: 0.5 },
    { rgb: zones.bottomRight.seam, weight: 0.5 },
    { rgb: zones.bottomLeft.seam, weight: 0.5 }
  ]));

  const [edgeHue, edgeSaturation, edgeLightness] = rgbToHsl(seam);
  const supporting = supportingArtworkHue(data, image, edgeHue);
  const accentHue = edgeSaturation >= 0.34
    ? edgeHue
    : supporting.hue;
  const isMonochrome = !supporting.hasSupporting && edgeSaturation < 0.18;
  const accentSaturation = isMonochrome
    ? 0.08
    : clamp(Math.max(edgeSaturation, 0.82), 0.82, 1);
  const accentLightness = isMonochrome ? 0.72 : (edgeLightness > 0.72 ? 0.48 : 0.61);
  const accent = hslToRgb(accentHue, accentSaturation, accentLightness);
  const deep = mixRgb(seam, [5, 2, 3], 0.58);
  const ink = mixRgb(seam, [4, 2, 3], 0.8);
  const surface = mixRgb(seam, [11, 5, 7], 0.7);
  const surfaceRaised = mixRgb(seam, [28, 10, 13], 0.56);

  return {
    method: 'sharp-raw-edge-zones-v1',
    width: image.width,
    height: image.height,
    zones,
    palette: {
      seam,
      accent,
      accentSoft: mixRgb(accent, [255, 255, 255], 0.18),
      deep,
      ink,
      surface,
      surfaceRaised,
      onAccent: readableAccentText(accent)
    }
  };
}

export async function sampleArtworkBuffer(input) {
  const { data, info } = await sharp(input, {
    failOn: 'warning',
    limitInputPixels: MAX_ARTWORK_PIXELS,
    pages: 1
  })
    .rotate()
    .ensureAlpha()
    .toColourspace('srgb')
    .raw()
    .toBuffer({ resolveWithObject: true });

  return sampleArtworkPixelData(data, info);
}

export async function sampleArtworkRawFrame(input = {}) {
  const width = Number(input.width);
  const height = Number(input.height);
  const channels = Number(input.channels);
  const source = input.data;
  if (
    !Number.isInteger(width) ||
    !Number.isInteger(height) ||
    width < 1 ||
    height < 1 ||
    width > MAX_VIDEO_FRAME_SIDE ||
    height > MAX_VIDEO_FRAME_SIDE ||
    channels !== 4 ||
    !ArrayBuffer.isView(source) ||
    source.byteLength !== width * height * channels
  ) {
    throw new RangeError('Artwork video frame has invalid dimensions or pixel data.');
  }

  const rawInput = Buffer.from(source.buffer, source.byteOffset, source.byteLength);
  const { data, info } = await sharp(rawInput, {
    raw: { width, height, channels }
  })
    .ensureAlpha()
    .toColourspace('srgb')
    .raw()
    .toBuffer({ resolveWithObject: true });
  return {
    ...sampleArtworkPixelData(data, info),
    method: 'sharp-raw-video-frame-edge-zones-v1'
  };
}

async function responseBuffer(response) {
  const declaredLength = Number(response.headers.get('content-length') || 0);
  if (declaredLength > MAX_ARTWORK_BYTES) throw new Error('Artwork is too large to sample.');
  if (!response.body) return Buffer.alloc(0);

  const reader = response.body.getReader();
  const chunks = [];
  let received = 0;
  while (true) {
    const { done, value } = await reader.read();
    if (done) break;
    received += value.byteLength;
    if (received > MAX_ARTWORK_BYTES) {
      await reader.cancel();
      throw new Error('Artwork is too large to sample.');
    }
    chunks.push(Buffer.from(value.buffer, value.byteOffset, value.byteLength));
  }
  return Buffer.concat(chunks, received);
}

function dataUrlBuffer(value) {
  const match = /^data:(image\/[a-z0-9.+-]+)?(;base64)?,([\s\S]*)$/i.exec(value);
  if (!match) throw new Error('Unsupported artwork data URL.');
  const buffer = match[2]
    ? Buffer.from(match[3], 'base64')
    : Buffer.from(decodeURIComponent(match[3]), 'utf8');
  if (buffer.byteLength > MAX_ARTWORK_BYTES) throw new Error('Artwork is too large to sample.');
  return buffer;
}

async function fetchArtworkBuffer(net, value) {
  if (value.startsWith('data:image/')) return dataUrlBuffer(value);
  const url = new URL(value);
  if (!['https:', 'http:'].includes(url.protocol)) throw new Error('Artwork URL must use HTTP or HTTPS.');

  const response = await net.fetch(url.toString(), {
    headers: { Accept: 'image/avif,image/webp,image/png,image/jpeg,image/*;q=0.8' },
    redirect: 'follow'
  });
  if (!response.ok) throw new Error(`Artwork request failed with ${response.status}.`);
  return responseBuffer(response);
}

function touchCache(cache, key, value) {
  cache.delete(key);
  cache.set(key, value);
  while (cache.size > ARTWORK_CACHE_LIMIT) cache.delete(cache.keys().next().value);
}

export function registerArtworkColorSampler({ ipcMain, net }) {
  const cache = new Map();
  ipcMain.handle(IPC_CHANNELS.ARTWORK.SAMPLE_COLORS, async (_event, artworkUrl) => {
    const key = typeof artworkUrl === 'string' ? artworkUrl.trim() : '';
    if (!key || key.length > 8192) return null;

    if (cache.has(key)) {
      const cached = cache.get(key);
      touchCache(cache, key, cached);
      return cached;
    }

    const pending = fetchArtworkBuffer(net, key)
      .then((buffer) => sampleArtworkBuffer(buffer))
      .catch((error) => {
        cache.delete(key);
        console.warn('Artwork color sampling failed:', error?.message || error);
        return null;
      });
    touchCache(cache, key, pending);
    return pending;
  });

  ipcMain.handle(IPC_CHANNELS.ARTWORK.SAMPLE_FRAME_COLORS, async (_event, frame) => {
    try {
      return await sampleArtworkRawFrame(frame);
    } catch (error) {
      console.warn('Artwork video frame sampling failed:', error?.message || error);
      return null;
    }
  });
}

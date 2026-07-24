import { BasicPipeline, Vibrant } from '@vibrant/core';
import { DefaultGenerator } from '@vibrant/generator-default';
import { BrowserImage } from '@vibrant/image-browser';
import { MMCQ } from '@vibrant/quantizer-mmcq';
import { interpolateRgb } from './backgroundUtils.js';

const PALETTE_CACHE_LIMIT = 36;
const paletteCache = new Map();
const palettePipeline = new BasicPipeline()
  .filter.register('default', (r, g, b, a) => a >= 125 && !(r > 250 && g > 250 && b > 250))
  .quantizer.register('mmcq', MMCQ)
  .generator.register('default', DefaultGenerator);

Vibrant.DefaultOpts.ImageClass = BrowserImage;
Vibrant.DefaultOpts.quantizer = 'mmcq';
Vibrant.DefaultOpts.generators = ['default'];
Vibrant.DefaultOpts.filters = ['default'];
Vibrant.use(palettePipeline);

export const FALLBACK_ARTWORK_PALETTE = Object.freeze({
  dominant: Object.freeze([72, 84, 69]),
  vibrant: Object.freeze([104, 126, 91]),
  muted: Object.freeze([73, 88, 80]),
  darkVibrant: Object.freeze([31, 55, 39]),
  darkMuted: Object.freeze([19, 29, 24])
});

function clonePalette(palette) {
  return Object.fromEntries(Object.entries(palette).map(([name, rgb]) => [name, [...rgb]]));
}

function swatchRgb(swatch) {
  return Array.isArray(swatch?.rgb) ? swatch.rgb.map((value) => Math.round(value)) : null;
}

function shiftColor(rgb, target, amount) {
  return interpolateRgb(rgb, target, amount);
}

function normalizePalette(source) {
  const swatches = Object.values(source || {}).filter(Boolean);
  const populated = [...swatches].sort((left, right) => (right.population || 0) - (left.population || 0));
  const base = swatchRgb(populated[0]) || swatchRgb(source?.Vibrant) || [...FALLBACK_ARTWORK_PALETTE.dominant];
  const gray = Math.round((base[0] + base[1] + base[2]) / 3);
  const mutedFallback = shiftColor(base, [gray, gray, gray], 0.42);

  return {
    dominant: base,
    vibrant: swatchRgb(source?.Vibrant) || shiftColor(base, [255, 255, 255], 0.16),
    muted: swatchRgb(source?.Muted) || mutedFallback,
    darkVibrant: swatchRgb(source?.DarkVibrant) || shiftColor(base, [7, 13, 9], 0.56),
    darkMuted: swatchRgb(source?.DarkMuted) || shiftColor(mutedFallback, [6, 10, 8], 0.66)
  };
}

function touchCacheEntry(key, value) {
  paletteCache.delete(key);
  paletteCache.set(key, value);

  while (paletteCache.size > PALETTE_CACHE_LIMIT) {
    paletteCache.delete(paletteCache.keys().next().value);
  }
}

export async function getArtworkPalette(url, image) {
  if (!url || !image) return clonePalette(FALLBACK_ARTWORK_PALETTE);

  if (paletteCache.has(url)) {
    const cached = paletteCache.get(url);
    touchCacheEntry(url, cached);
    return clonePalette(await cached);
  }

  // Quantizing a small copy keeps extraction asynchronous without delaying playback.
  const pending = Vibrant.from(image)
    .maxDimension(180)
    .maxColorCount(32)
    .quality(5)
    .getPalette()
    .then(normalizePalette)
    .catch(() => clonePalette(FALLBACK_ARTWORK_PALETTE));

  touchCacheEntry(url, pending);
  return clonePalette(await pending);
}

export function interpolatePalette(from, to, progress) {
  return Object.fromEntries(
    Object.keys(FALLBACK_ARTWORK_PALETTE).map((name) => [name, interpolateRgb(from[name], to[name], progress)])
  );
}

export function clearArtworkPaletteCache() {
  paletteCache.clear();
}

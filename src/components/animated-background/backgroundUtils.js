const TAU = Math.PI * 2;

export function normalizeBackgroundUrl(value) {
  return typeof value === 'string' ? value.trim() : '';
}

export function isHlsSource(value) {
  const source = normalizeBackgroundUrl(value);
  if (!source) return false;

  try {
    return new URL(source, 'https://orchard.invalid').pathname.toLowerCase().endsWith('.m3u8');
  } catch {
    return /\.m3u8(?:$|[?#])/i.test(source);
  }
}

export function coverScale(sourceWidth, sourceHeight, targetWidth, targetHeight, overscan = 1) {
  if (!sourceWidth || !sourceHeight || !targetWidth || !targetHeight) return 1;
  return Math.max(targetWidth / sourceWidth, targetHeight / sourceHeight) * overscan;
}

export function ambientArtworkBlur(width, height) {
  const shortestSide = Math.min(Number(width) || 0, Number(height) || 0);
  if (!shortestSide) return 96;
  return Math.max(92, Math.min(128, shortestSide * 0.12));
}

export function backgroundViewportSize(canvas, viewport = globalThis.window) {
  const rect = canvas?.getBoundingClientRect?.() || {};
  const width = Number(viewport?.innerWidth) || Number(rect.width) || 2;
  const height = Number(viewport?.innerHeight) || Number(rect.height) || 2;
  return {
    width: Math.max(2, width),
    height: Math.max(2, height)
  };
}

export function backgroundResizeTarget(canvas) {
  return canvas?.parentElement || canvas;
}

export function interpolateRgb(from, to, progress) {
  const amount = Math.max(0, Math.min(1, Number(progress) || 0));
  return [0, 1, 2].map((index) => Math.round(from[index] + ((to[index] - from[index]) * amount)));
}

export function rgbToTint(rgb) {
  return ((rgb[0] & 255) << 16) | ((rgb[1] & 255) << 8) | (rgb[2] & 255);
}

function hashString(value) {
  let hash = 2166136261;
  for (let index = 0; index < value.length; index += 1) {
    hash ^= value.charCodeAt(index);
    hash = Math.imul(hash, 16777619);
  }
  return hash >>> 0;
}

export function motionParametersForUrl(value) {
  let hash = hashString(normalizeBackgroundUrl(value) || 'orchard');
  const next = () => {
    hash = Math.imul(hash ^ (hash >>> 15), 2246822519) >>> 0;
    return hash / 0xffffffff;
  };

  return {
    phase: next() * TAU,
    speed: 0.82 + (next() * 0.34),
    translateX: 0.014 + (next() * 0.009),
    translateY: 0.012 + (next() * 0.008),
    rotation: 0.012 + (next() * 0.012),
    scale: 0.018 + (next() * 0.014),
    distortion: 5 + (next() * 4)
  };
}

export function loadArtworkImage(source) {
  const url = normalizeBackgroundUrl(source);
  if (!url) return Promise.reject(new Error('Artwork URL is missing'));

  return new Promise((resolve, reject) => {
    const image = new Image();
    image.crossOrigin = 'anonymous';
    image.decoding = 'async';

    image.addEventListener('load', async () => {
      try {
        await image.decode?.();
      } catch {
        // A completed load is still usable on Chromium versions with strict decode().
      }
      resolve(image);
    }, { once: true });
    image.addEventListener('error', () => reject(new Error(`Unable to load artwork: ${url}`)), { once: true });
    image.src = url;
  });
}

export function createDisplacementCanvas(size = 192) {
  const canvas = document.createElement('canvas');
  canvas.width = size;
  canvas.height = size;
  const context = canvas.getContext('2d');
  const pixels = context.createImageData(size, size);

  for (let y = 0; y < size; y += 1) {
    for (let x = 0; x < size; x += 1) {
      const offset = (y * size + x) * 4;
      const waveX = Math.sin((x * 0.052) + Math.sin(y * 0.031) * 2.1);
      const waveY = Math.cos((y * 0.047) + Math.cos(x * 0.027) * 2.4);
      pixels.data[offset] = 128 + Math.round(waveX * 54);
      pixels.data[offset + 1] = 128 + Math.round(waveY * 54);
      pixels.data[offset + 2] = 128;
      pixels.data[offset + 3] = 255;
    }
  }

  context.putImageData(pixels, 0, 0);
  return canvas;
}

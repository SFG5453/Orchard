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

import { hexColorToRgb, immersiveBackgroundOpacity } from './appearancePreferences.js';
import { VEIL_MIN_ALPHA, solveVeilAlpha } from './immersiveVeil.js';

// What the veil composites against. The page colour and the two text colours
// come from the theme tokens in base-shell.css and appearance-theme.css, and the
// veil colours from immersive-background.css.
// Each theme keeps the opacity it already shipped as its floor, so a cover that
// never needed help looks exactly as it did.
const VEIL_THEMES = {
  dark: {
    veilRgb: [3, 7, 4],
    pageRgb: [0, 0, 0],
    textRgb: [246, 246, 246],
    mutedRgb: [141, 141, 141],
    minAlpha: VEIL_MIN_ALPHA
  },
  light: {
    veilRgb: [248, 250, 248],
    pageRgb: [244, 246, 244],
    textRgb: [20, 23, 20],
    mutedRgb: [104, 112, 105],
    minAlpha: 0.7
  }
};

function veilTheme() {
  const resolved = typeof document === 'undefined'
    ? 'dark'
    : document.documentElement?.dataset?.themeResolved;
  return VEIL_THEMES[resolved === 'light' ? 'light' : 'dark'];
}

export function installVisualUtils(ctx) {
  function releaseTrackCount(value = '') {
    return Number(String(value || '').match(/(\d+)\s+(?:songs?|tracks?)\b/i)?.[1] || 0);
  }

  ctx.formatTime = function formatTime(seconds) {
    if (!Number.isFinite(seconds) || seconds <= 0) return '0:00';
    const mins = Math.floor(seconds / 60);
    const secs = Math.floor(seconds % 60).toString().padStart(2, '0');
    return `${mins}:${secs}`;
  };

  ctx.isYearText = function isYearText(value = '') {
    return /^[12][0-9]{3}$/.test(String(value || '').trim());
  };

  // Mirrors normalizedLooseText in electron/catalog/musicText.js, and carries
  // the same reason for keeping every script's letters: callers use the result
  // as a dedupe key, and reducing a title to [a-z0-9] emptied anything written
  // without Latin characters, so unrelated tracks collided on one blank key.
  ctx.normalizedLookupText = function normalizedLookupText(value = '') {
    return String(value)
      .normalize('NFD')
      .replace(/[\u0300-\u036f]/g, '')
      .normalize('NFC')
      .toLowerCase()
      .replace(/&/g, ' and ')
      .replace(/[^\p{L}\p{N}]+/gu, ' ')
      .trim();
  };

  ctx.itemTypeLabel = function itemTypeLabel(item) {
    if (item.type === 'album') return ctx.albumTypeLabel(item);
    if (item.type === 'future_album') return 'Upcoming Album';
    if (item.type === 'podcast') return 'Podcast';
    if (item.type === 'podcast_episode') return 'Episode';
    if (item.type === 'playlist') return 'Playlist';
    if (item.type === 'artist' || item.type === 'library_artist') return 'Artist';
    if (item.type === 'song' || item.type === 'track') return 'Song';
    if (item.type === 'video') return 'Video';
    if (item.type === 'event') return 'Live event';
    return item.type || '';
  };

  ctx.albumTypeLabel = function albumTypeLabel(item = {}) {
    const releaseText = [item.releaseType, item.subtitle, item.itemCount].filter(Boolean).join(' ');
    const releaseType = releaseTrackCount(releaseText) >= 7
      ? 'Album'
      : /(?:^|[^\w])ep(?:[^\w]|$)/i.test(releaseText)
        ? 'EP'
        : /(?:^|[^\w])single(?:[^\w]|$)/i.test(releaseText) ? 'Single' : 'Album';
    const year = [item.year, item.subtitle, item.releaseDate]
      .map((value) => String(value || '').match(/\b[12][0-9]{3}\b/)?.[0] || '')
      .find(Boolean);
    return year ? `${releaseType} - ${year}` : releaseType;
  };

  ctx.itemMeta = function itemMeta(item, fallbackArtist = '') {
    if (item.type === 'future_album') return item.subtitle || ctx.itemTypeLabel(item);
    if (item.type === 'album') return ctx.albumTypeLabel(item);
    if (item.artists?.length) return item.artists.join(', ');
    if (item.album) return item.album;
    if (item.subtitle && !ctx.isYearText(item.subtitle)) return item.subtitle;
    return fallbackArtist || ctx.itemTypeLabel(item);
  };

  ctx.itemStat = function itemStat(item) {
    const stat = item.duration || (item.type === 'album' ? '' : item.year) || item.itemCount || item.views || '';
    return stat && stat !== ctx.itemMeta(item) ? stat : '';
  };

  ctx.sectionCount = function sectionCount(section) {
    return `${section.items.length} item${section.items.length === 1 ? '' : 's'}`;
  };

  ctx.heroBackdropStyle = function heroBackdropStyle(item, artworkUrl = '') {
    const image = artworkUrl || item?.thumbnail;
    if (!image) return {};

    if (item?.kind && item.kind !== 'artist') return {};

    if (item?.kind === 'artist') {
      const artwork = ctx.cssImageUrl(image);

      return {
        '--detail-hero-artwork': artwork,
        backgroundImage: `linear-gradient(90deg, rgba(5, 7, 6, 0.94) 0%, rgba(5, 7, 6, 0.78) 34%, rgba(5, 7, 6, 0.28) 72%, rgba(5, 7, 6, 0.5) 100%), linear-gradient(180deg, rgba(5, 7, 6, 0.06) 0%, rgba(5, 7, 6, 0.78) 100%), ${artwork}`
      };
    }

    return {
      backgroundImage: `linear-gradient(90deg, rgba(18, 15, 18, 0.82), rgba(18, 15, 18, 0.94)), url("${image}")`
    };
  };

  ctx.clamp = function clamp(value, min, max) {
    return Math.min(max, Math.max(min, value));
  };

  ctx.rgbToken = function rgbToken(rgb) {
    return rgb.map((value) => Math.round(value)).join(', ');
  };

  ctx.cssImageUrl = function cssImageUrl(imageUrl) {
    if (!imageUrl) return 'none';
    return `url("${String(imageUrl).replace(/"/g, '\\"')}")`;
  };

  ctx.highResolutionArtworkImage = function highResolutionArtworkImage(imageUrl, size = 1200) {
    const rawUrl = String(imageUrl || '').trim();
    if (!rawUrl) return '';

    try {
      const url = new URL(rawUrl);
      const host = url.hostname.toLowerCase();
      const googleArtworkHost = host.endsWith('googleusercontent.com') || host.endsWith('ggpht.com');

      if (googleArtworkHost) {
        if (/=w\d+-h\d+(?:-[^/?#=]+)*$/i.test(url.pathname)) {
          url.pathname = url.pathname.replace(/=w\d+-h\d+(?:-[^/?#=]+)*$/i, `=w${size}-h${size}-l90-rj`);
          return url.toString();
        }

        if (/=s\d+(?:-[^/?#=]+)*$/i.test(url.pathname)) {
          url.pathname = url.pathname.replace(/=s\d+(?:-[^/?#=]+)*$/i, `=s${size}-c-k-c0x00ffffff-no-rj`);
          return url.toString();
        }

        if (url.searchParams.has('sz')) {
          url.searchParams.set('sz', String(size));
          return url.toString();
        }
      }

      if (host.includes('mzstatic.com')) {
        const upgradedPath = url.pathname.replace(/\/\d+x\d+[^/]*\.(jpg|jpeg|png|webp)$/i, '/1200x1200bb.$1');
        if (upgradedPath !== url.pathname) {
          url.pathname = upgradedPath;
          return url.toString();
        }
      }
    } catch {
      return rawUrl;
    }

    return rawUrl;
  };

  ctx.rgbToHsl = function rgbToHsl(r, g, b) {
    r /= 255;
    g /= 255;
    b /= 255;

    const max = Math.max(r, g, b);
    const min = Math.min(r, g, b);
    let h = 0;
    let s = 0;
    const l = (max + min) / 2;

    if (max !== min) {
      const d = max - min;
      s = l > 0.5 ? d / (2 - max - min) : d / (max + min);
      switch (max) {
        case r:
          h = (g - b) / d + (g < b ? 6 : 0);
          break;
        case g:
          h = (b - r) / d + 2;
          break;
        default:
          h = (r - g) / d + 4;
          break;
      }
      h /= 6;
    }

    return [h, s, l];
  };

  ctx.hueToRgb = function hueToRgb(p, q, t) {
    if (t < 0) t += 1;
    if (t > 1) t -= 1;
    if (t < 1 / 6) return p + (q - p) * 6 * t;
    if (t < 1 / 2) return q;
    if (t < 2 / 3) return p + (q - p) * (2 / 3 - t) * 6;
    return p;
  };

  ctx.hslToRgb = function hslToRgb(h, s, l) {
    if (s === 0) {
      const gray = Math.round(l * 255);
      return [gray, gray, gray];
    }

    const q = l < 0.5 ? l * (1 + s) : l + s - l * s;
    const p = 2 * l - q;

    return [
      Math.round(ctx.hueToRgb(p, q, h + 1 / 3) * 255),
      Math.round(ctx.hueToRgb(p, q, h) * 255),
      Math.round(ctx.hueToRgb(p, q, h - 1 / 3) * 255)
    ];
  };

  ctx.createPlayerBarAccent = function createPlayerBarAccent(rgb) {
    const [h, s, l] = ctx.rgbToHsl(rgb[0], rgb[1], rgb[2]);
    const saturated = ctx.clamp(Math.max(0.62, s * 1.2), 0.4, 0.92);
    const bright = ctx.clamp(l < 0.24 ? l + 0.28 : l < 0.38 ? l + 0.16 : l, 0.4, 0.58);

    return {
      rgb: ctx.hslToRgb(h, saturated, bright),
      softRgb: ctx.hslToRgb(h, ctx.clamp(saturated * 0.72, 0.36, 0.82), ctx.clamp(bright + 0.18, 0.5, 0.72)),
      deepRgb: ctx.hslToRgb(h, ctx.clamp(saturated * 0.86, 0.42, 0.9), ctx.clamp(bright * 0.52, 0.16, 0.34))
    };
  };

  ctx.pickArtworkAccent = function pickArtworkAccent(imageData) {
    const buckets = new Map();
    const data = imageData.data;

    for (let index = 0; index < data.length; index += 16) {
      const alpha = data[index + 3];
      if (alpha < 200) continue;

      const r = data[index];
      const g = data[index + 1];
      const b = data[index + 2];
      const [h, s, l] = ctx.rgbToHsl(r, g, b);
      if (s < 0.18 || l < 0.1 || l > 0.88) continue;

      const hueBucket = Math.round(h * 36);
      const satBucket = Math.round(s * 5);
      const lightBucket = Math.round(l * 5);
      const key = `${hueBucket}:${satBucket}:${lightBucket}`;
      const weight = (s * 1.4 + (1 - Math.abs(l - 0.48)) * 0.7) * (0.75 + alpha / 1020);
      const bucket = buckets.get(key) || { score: 0, r: 0, g: 0, b: 0, weight: 0 };

      bucket.score += weight;
      bucket.r += r * weight;
      bucket.g += g * weight;
      bucket.b += b * weight;
      bucket.weight += weight;
      buckets.set(key, bucket);
    }

    const best = [...buckets.values()].sort((a, b) => b.score - a.score)[0];
    if (!best?.weight) return null;

    return [
      best.r / best.weight,
      best.g / best.weight,
      best.b / best.weight
    ];
  };

  // Average colour of the whole cover, opaque pixels only. The backdrop is a
  // heavy blur of the artwork, so its mean is what the veil actually has to
  // cover. Unlike the accent sampler this keeps greys, since a grey cover is
  // exactly the case that needs a heavier veil.
  ctx.meanArtworkColor = function meanArtworkColor(imageData) {
    const data = imageData.data;
    let r = 0;
    let g = 0;
    let b = 0;
    let count = 0;

    for (let index = 0; index < data.length; index += 16) {
      if (data[index + 3] < 200) continue;
      r += data[index];
      g += data[index + 1];
      b += data[index + 2];
      count += 1;
    }

    return count ? [r / count, g / count, b / count] : null;
  };

  async function sampleArtworkImageData(imageUrl) {
    if (!imageUrl || typeof Image === 'undefined' || typeof document === 'undefined') return null;

    const image = new Image();
    image.crossOrigin = 'anonymous';
    image.decoding = 'async';
    image.referrerPolicy = 'no-referrer';

    await new Promise((resolve, reject) => {
      image.onload = resolve;
      image.onerror = reject;
      image.src = imageUrl;
    });

    const canvas = document.createElement('canvas');
    const sampleWidth = 64;
    const sampleHeight = Math.max(1, Math.round((image.naturalHeight / image.naturalWidth) * sampleWidth));
    canvas.width = sampleWidth;
    canvas.height = sampleHeight;

    const context = canvas.getContext('2d', { willReadFrequently: true });
    if (!context) throw new Error('Canvas is unavailable');

    context.drawImage(image, 0, 0, sampleWidth, sampleHeight);
    return context.getImageData(0, 0, sampleWidth, sampleHeight);
  }

  /**
   * Reads the backdrop artwork once and derives both colours that depend on it:
   * the accent, when the listener has asked for an artwork accent, and the veil
   * opacity that keeps text legible over it. One decode serves both.
   */
  ctx.loadArtworkColors = async function loadArtworkColors(imageUrl) {
    const requestId = ++ctx.playerColorRequest;
    const wantsArtworkAccent = ctx.accentColorSource?.value === 'artwork';
    const fallbackAccent = ctx.accentColorSource?.value === 'custom'
      ? hexColorToRgb(ctx.customAccentColor.value)
      : [47, 223, 147];

    function applyAccent(rgb) {
      if (requestId !== ctx.playerColorRequest) return;
      const accent = ctx.createPlayerBarAccent(rgb);
      ctx.playerBarAccent.value = accent;
      document.documentElement.style.setProperty('--q-primary', `rgb(${ctx.rgbToken(accent.rgb)})`);
    }

    // No artwork to measure means nothing is covering the page, so the veil
    // goes back to the shipped default rather than holding a previous cover's.
    function applyVeil(artworkRgb) {
      if (requestId !== ctx.playerColorRequest || !ctx.immersiveVeilAlpha) return;

      const theme = veilTheme();
      ctx.immersiveVeilAlpha.value = artworkRgb
        ? solveVeilAlpha({
          ...theme,
          artworkRgb,
          backgroundOpacity: immersiveBackgroundOpacity(ctx.immersiveBackgroundIntensity?.value)
        })
        : theme.minAlpha;
    }

    // A fixed accent does not depend on the artwork, so it lands immediately
    // rather than waiting on a decode that only the veil needs.
    if (!wantsArtworkAccent) applyAccent(fallbackAccent);

    let imageData = null;
    try {
      imageData = await sampleArtworkImageData(imageUrl);
    } catch {
      imageData = null;
    }

    if (requestId !== ctx.playerColorRequest) return;

    applyVeil(imageData ? ctx.meanArtworkColor(imageData) : null);
    if (!wantsArtworkAccent) return;

    if (!imageData) {
      applyAccent(fallbackAccent);
      return;
    }

    const accent = ctx.pickArtworkAccent(imageData);
    if (accent) applyAccent(accent);
  };

  ctx.playlistCardStyle = function playlistCardStyle(index) {
    const colors = ['#69a8ff', '#7568f4', '#ffa12b', '#ff6848', '#3154ff', '#f54874', '#ec6a32', '#7d49f3'];
    return { '--playlist-card-bg': colors[index % colors.length] };
  };
}

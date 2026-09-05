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

import { interpolateRgb } from '../animated-background/backgroundUtils.js';

function clamp(value, min = 0, max = 1) {
  return Math.max(min, Math.min(max, Number(value) || 0));
}

function rgbToHsl(rgb) {
  const [red, green, blue] = rgb.map((value) => clamp(value, 0, 255) / 255);
  const max = Math.max(red, green, blue);
  const min = Math.min(red, green, blue);
  const lightness = (max + min) / 2;
  if (max === min) return [0, 0, lightness];
  const difference = max - min;
  const saturation = lightness > 0.5
    ? difference / (2 - max - min)
    : difference / (max + min);
  let hue = max === red
    ? (green - blue) / difference + (green < blue ? 6 : 0)
    : max === green ? (blue - red) / difference + 2 : (red - green) / difference + 4;
  hue /= 6;
  return [hue, saturation, lightness];
}

function hueChannel(p, q, value) {
  let t = value;
  if (t < 0) t += 1;
  if (t > 1) t -= 1;
  if (t < 1 / 6) return p + (q - p) * 6 * t;
  if (t < 1 / 2) return q;
  if (t < 2 / 3) return p + (q - p) * (2 / 3 - t) * 6;
  return p;
}

function hslToRgb([hue, saturation, lightness]) {
  if (saturation === 0) {
    const gray = Math.round(lightness * 255);
    return [gray, gray, gray];
  }
  const q = lightness < 0.5
    ? lightness * (1 + saturation)
    : lightness + saturation - lightness * saturation;
  const p = 2 * lightness - q;
  return [hue + 1 / 3, hue, hue - 1 / 3]
    .map((value) => Math.round(hueChannel(p, q, value) * 255));
}

function tune(rgb, { minimumSaturation = 0, saturation = 1, lightness = 1, maxLightness = 1 } = {}) {
  const hsl = rgbToHsl(rgb);
  const colorful = hsl[1] > 0.08;
  hsl[1] = clamp(Math.max(hsl[1] * saturation, colorful ? minimumSaturation : 0.12));
  hsl[2] = clamp(hsl[2] * lightness, 0.045, maxLightness);
  return hslToRgb(hsl);
}

function linearChannel(value) {
  const channel = value / 255;
  return channel <= 0.04045
    ? channel / 12.92
    : ((channel + 0.055) / 1.055) ** 2.4;
}

function luminance(rgb) {
  const [red, green, blue] = rgb.map(linearChannel);
  return red * 0.2126 + green * 0.7152 + blue * 0.0722;
}

function contrast(left, right) {
  const brighter = Math.max(luminance(left), luminance(right));
  const darker = Math.min(luminance(left), luminance(right));
  return (brighter + 0.05) / (darker + 0.05);
}

export function createFullscreenEnvironment(palette = {}) {
  const dominant = palette.dominant || [56, 70, 60];
  const vibrant = palette.vibrant || dominant;
  const muted = palette.muted || dominant;
  const darkVibrant = palette.darkVibrant || dominant;
  const darkMuted = palette.darkMuted || muted;
  const base = tune(darkVibrant, {
    minimumSaturation: 0.5,
    saturation: 1.22,
    lightness: 0.76,
    maxLightness: 0.24
  });
  const field = tune(vibrant, {
    minimumSaturation: 0.55,
    saturation: 1.12,
    lightness: 0.54,
    maxLightness: 0.34
  });
  const depth = tune(darkMuted, {
    minimumSaturation: 0.26,
    saturation: 1.08,
    lightness: 0.68,
    maxLightness: 0.19
  });
  const accent = tune(interpolateRgb(vibrant, dominant, 0.24), {
    minimumSaturation: 0.46,
    saturation: 1.06,
    lightness: 0.92,
    maxLightness: 0.62
  });
  const lightForeground = [249, 248, 245];
  const darkForeground = [10, 13, 12];
  const foreground = contrast(base, lightForeground) >= contrast(base, darkForeground)
    ? lightForeground
    : darkForeground;

  return { base, field, depth, accent, foreground };
}

export function interpolateFullscreenEnvironment(from, to, progress) {
  const amount = clamp(progress);
  return Object.fromEntries(
    Object.keys(from).map((name) => [name, interpolateRgb(from[name], to[name] || from[name], amount)])
  );
}

export function fullscreenEnvironmentStyle(environment) {
  const token = (rgb) => rgb.map((value) => Math.round(value)).join(', ');
  const foregroundLuminance = luminance(environment.foreground);
  return {
    '--fs-bg-rgb': token(environment.base),
    '--fs-field-rgb': token(environment.field),
    '--fs-depth-rgb': token(environment.depth),
    '--fs-accent-rgb': token(environment.accent),
    '--fs-fg-rgb': token(environment.foreground),
    '--fs-muted-alpha': foregroundLuminance > 0.5 ? 0.58 : 0.68
  };
}

export function fullscreenArtworkMotion(mix = {}, beatPulse = 0) {
  const visible = Boolean(mix.visible);
  const preparing = visible && mix.phase === 'preparing';
  const preparation = preparing ? clamp(mix.preparationProgress) : visible ? 1 : 0;
  const progress = visible && !preparing ? clamp(mix.progress) : 0;
  const incomingWeight = visible && !preparing ? clamp(mix.incomingWeight) : 0;
  const pulse = visible && ['mix-start', 'active-mix', 'handoff'].includes(mix.phase)
    ? clamp(beatPulse) * 0.007
    : 0;
  const outgoingOpacity = visible && !preparing ? clamp(1 - incomingWeight * 1.08) : 1;
  const incomingOpacity = preparing
    ? 0.04 + preparation * 0.14
    : visible ? clamp(Math.max(incomingWeight, progress * 0.12)) : 0;

  return {
    '--fs-art-beat-scale': 1 + pulse,
    '--fs-art-out-x': `${visible && !preparing ? -5.5 * progress : -0.8 * preparation}%`,
    '--fs-art-out-scale': 1 - (visible && !preparing ? 0.035 * progress : 0.008 * preparation),
    '--fs-art-out-opacity': outgoingOpacity,
    '--fs-art-in-x': `${preparing ? 8 - preparation * 2 : 6 * (1 - progress)}%`,
    '--fs-art-in-scale': preparing ? 0.96 : 0.965 + 0.035 * progress,
    '--fs-art-in-opacity': incomingOpacity,
    '--fs-palette-weight': preparing ? 0.025 * preparation : incomingWeight
  };
}

export function lyricHandoffOpacity(mix = {}) {
  if (!mix.visible || mix.phase === 'preparing') return { incoming: 1, outgoing: 0 };
  const progress = clamp(mix.progress);
  const handoff = clamp(mix.handoffProgress, 0.05, 0.95);
  const handoffProgress = clamp((progress - handoff) / Math.max(0.05, 1 - handoff));
  return {
    incoming: handoffProgress,
    outgoing: 1 - handoffProgress
  };
}

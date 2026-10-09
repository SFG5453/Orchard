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
 */

// Shared between the renderer, which stores the listener's choice, and the main
// process, which applies it while picking an InnerTube format. Mirrors the
// mobile app's AudioQuality ladder so a listener moving between devices gets the
// same three steps, minus the NewPipe-only MAX tier desktop has no path to.
export const DEFAULT_STREAM_QUALITY = 'high';

export const STREAM_QUALITY_OPTIONS = Object.freeze([
  Object.freeze({ label: 'Saver', value: 'saver', description: 'Lowest bitrate audio and 480p video.' }),
  Object.freeze({ label: 'Normal', value: 'normal', description: 'Up to 128 kbps audio and 720p video.' }),
  Object.freeze({ label: 'High', value: 'high', description: 'Best available audio and video.' })
]);

// YouTube's 128 kbps AAC track reports slightly above its nominal rate, so the
// ceiling sits a little over it to keep that format inside the normal tier.
const MAX_AUDIO_BITRATE = Object.freeze({
  saver: 0,
  normal: 140_000,
  high: Infinity
});

const MAX_VIDEO_HEIGHT = Object.freeze({
  saver: 480,
  normal: 720,
  high: Infinity
});

export function normalizeStreamQuality(value) {
  return STREAM_QUALITY_OPTIONS.some((option) => option.value === value)
    ? value
    : DEFAULT_STREAM_QUALITY;
}

export function maxAudioBitrateForQuality(quality) {
  return MAX_AUDIO_BITRATE[normalizeStreamQuality(quality)];
}

export function maxVideoHeightForQuality(quality) {
  return MAX_VIDEO_HEIGHT[normalizeStreamQuality(quality)];
}

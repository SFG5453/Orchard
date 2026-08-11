/*
 * Copyright (C) 2026 SFG545
 *
 * This file is part of Orchard.
 *
 * Orchard is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 */

const nativeHlsMimeType = 'application/vnd.apple.mpegurl';
const hlsInstances = new WeakMap();
const loadGenerations = new WeakMap();

export function isHlsPlaybackMime(value = '') {
  return /(?:application|audio)\/(?:x-mpegurl|vnd\.apple\.mpegurl)/i.test(value);
}

export function releaseHlsPlayback(element) {
  if (!element) return;
  loadGenerations.set(element, (loadGenerations.get(element) || 0) + 1);
  hlsInstances.get(element)?.destroy();
  hlsInstances.delete(element);
}

export async function loadPlaybackSource(element, source, mimeType = '') {
  if (!element) return false;
  releaseHlsPlayback(element);
  const generation = loadGenerations.get(element) || 0;

  if (!isHlsPlaybackMime(mimeType)) {
    element.src = source;
    element.load();
    return true;
  }

  if (element.canPlayType(nativeHlsMimeType)) {
    element.src = source;
    element.load();
    return true;
  }

  const { default: Hls } = await import('hls.js/light');
  if (loadGenerations.get(element) !== generation) return false;
  if (!Hls.isSupported()) throw new Error('HLS playback is unsupported by this system');

  const hls = new Hls({
    backBufferLength: 30,
    enableWorker: true,
    lowLatencyMode: false,
    maxBufferLength: 30
  });
  hlsInstances.set(element, hls);
  hls.on(Hls.Events.ERROR, (_event, data) => {
    if (data.fatal && hlsInstances.get(element) === hls) {
      element.dispatchEvent(new Event('error'));
    }
  });
  hls.loadSource(source);
  hls.attachMedia(element);
  return true;
}

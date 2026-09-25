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
import {
  immersiveBackingSize,
  KawarpArtworkBackground
} from '../src/components/animated-background/KawarpArtworkBackground.js';
import { VideoArtworkBackground } from '../src/components/animated-background/VideoArtworkBackground.js';
import {
  interpolateRgb,
  isHlsSource,
  normalizeBackgroundUrl
} from '../src/components/animated-background/backgroundUtils.js';

test('animated background identifies only HLS manifests', () => {
  assert.equal(isHlsSource('https://media.example/artwork.m3u8?token=abc'), true);
  assert.equal(isHlsSource('https://media.example/artwork.M3U8#variant'), true);
  assert.equal(isHlsSource('https://media.example/artwork.mp4?next=.m3u8'), false);
  assert.equal(isHlsSource(''), false);
});

test('palette interpolation clamps progress', () => {
  assert.deepEqual(interpolateRgb([0, 20, 40], [100, 120, 140], 0.5), [50, 70, 90]);
  assert.deepEqual(interpolateRgb([1, 2, 3], [9, 9, 9], -1), [1, 2, 3]);
  assert.equal(normalizeBackgroundUrl('  cover.jpg  '), 'cover.jpg');
});

test('immersive WebGL backing store is downscaled and capped at 720p', () => {
  assert.deepEqual(immersiveBackingSize(1920, 1080), { width: 1152, height: 648 });
  assert.deepEqual(immersiveBackingSize(3840, 2160), { width: 1280, height: 720 });

  const ultrawide = immersiveBackingSize(5120, 1440);
  assert.ok(ultrawide.width * ultrawide.height <= 1280 * 720);
  assert.ok(Math.abs((ultrawide.width / ultrawide.height) - (5120 / 1440)) < 0.01);
});

test('static artwork renders one frame without starting the WebGL animation loop', () => {
  let renderCount = 0;
  let startCount = 0;
  let stopCount = 0;
  const background = new KawarpArtworkBackground({});
  background.renderer = {
    renderFrame() { renderCount += 1; },
    start() { startCount += 1; },
    stop() { stopCount += 1; }
  };
  background.source = 'cover.jpg';
  background.motionEnabled = false;
  background.playing = true;

  background.syncPlayback();

  assert.equal(startCount, 0);
  assert.equal(stopCount, 1);
  assert.equal(renderCount, 1);
});

test('animated artwork only runs while playback is active', () => {
  let startCount = 0;
  const background = new KawarpArtworkBackground({});
  background.renderer = {
    renderFrame() {},
    start() { startCount += 1; },
    stop() {}
  };
  background.source = 'cover.jpg';
  background.motionEnabled = true;

  background.syncPlayback();
  assert.equal(startCount, 0);

  background.playing = true;
  background.syncPlayback();
  assert.equal(startCount, 1);
});

test('animated artwork reuses one video and releases superseded sources', async () => {
  const listeners = new Map();
  const video = {
    pauseCount: 0,
    playCount: 0,
    loadCount: 0,
    addEventListener(name, listener, options = {}) {
      listeners.set(name, { listener, once: options.once });
      options.signal?.addEventListener('abort', () => listeners.delete(name), { once: true });
    },
    canPlayType: () => '',
    load() { this.loadCount += 1; },
    pause() { this.pauseCount += 1; },
    play() { this.playCount += 1; return Promise.resolve(); },
    removeAttribute(name) { if (name === 'src') delete this.src; }
  };
  let readyCount = 0;
  const background = new VideoArtworkBackground(video, {
    onReady: () => { readyCount += 1; },
    onFallback: () => {}
  });

  background.setSource('https://media.example/first.mp4');
  background.setSource('https://media.example/second.webm');
  assert.equal(video.src, 'https://media.example/second.webm');
  const loaded = listeners.get('loadeddata');
  loaded.listener();
  if (loaded.once) listeners.delete('loadeddata');
  await Promise.resolve();
  assert.equal(readyCount, 1);

  background.setPlaybackAllowed(true);
  await Promise.resolve();
  assert.equal(video.playCount, 1);
  background.destroy();
  assert.equal(video.src, undefined);
  assert.equal(background.hls, null);
});

test('video errors release media and return to the generated fallback', () => {
  const listeners = new Map();
  const video = {
    addEventListener(name, listener, options = {}) {
      listeners.set(name, listener);
      options.signal?.addEventListener('abort', () => listeners.delete(name), { once: true });
    },
    canPlayType: () => '',
    load() {},
    pause() {},
    play: () => Promise.resolve(),
    removeAttribute(name) { if (name === 'src') delete this.src; }
  };
  let fallbackCount = 0;
  const background = new VideoArtworkBackground(video, {
    onReady: () => {},
    onFallback: () => { fallbackCount += 1; }
  });

  background.setSource('https://media.example/broken.mp4');
  listeners.get('error')();
  assert.equal(background.source, '');
  assert.equal(video.src, undefined);
  assert.equal(fallbackCount, 2);
});

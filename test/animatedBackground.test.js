import assert from 'node:assert/strict';
import test from 'node:test';
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

import assert from 'node:assert/strict';
import test from 'node:test';
import { PixiArtworkBackground } from '../src/components/animated-background/PixiArtworkBackground.js';
import { VideoArtworkBackground } from '../src/components/animated-background/VideoArtworkBackground.js';
import {
  ambientArtworkBlur,
  backgroundResizeTarget,
  backgroundViewportSize,
  coverScale,
  interpolateRgb,
  isHlsSource,
  loadArtworkImage,
  motionParametersForUrl,
  normalizeBackgroundUrl,
  rgbToTint
} from '../src/components/animated-background/backgroundUtils.js';

test('animated background identifies only HLS manifests', () => {
  assert.equal(isHlsSource('https://media.example/artwork.m3u8?token=abc'), true);
  assert.equal(isHlsSource('https://media.example/artwork.M3U8#variant'), true);
  assert.equal(isHlsSource('https://media.example/artwork.mp4?next=.m3u8'), false);
  assert.equal(isHlsSource(''), false);
});

test('missing artwork rejects without attempting image decoding', async () => {
  await assert.rejects(loadArtworkImage(''), /Artwork URL is missing/);
});

test('artwork cover scale preserves coverage and overscan', () => {
  assert.equal(coverScale(1000, 1000, 1600, 900), 1.6);
  assert.equal(coverScale(1600, 900, 800, 1200), 4 / 3);
  assert.equal(coverScale(1000, 1000, 1000, 1000, 1.2), 1.2);
});

test('ambient artwork blur remains heavy and scales with the viewport', () => {
  assert.equal(ambientArtworkBlur(0, 0), 96);
  assert.equal(ambientArtworkBlur(1280, 720), 92);
  assert.equal(ambientArtworkBlur(1920, 1080), 128);
  assert.equal(ambientArtworkBlur(3840, 2160), 128);
});

test('static backgrounds size to the window instead of a shell child', () => {
  const canvas = { getBoundingClientRect: () => ({ width: 800, height: 600 }) };
  assert.deepEqual(backgroundViewportSize(canvas, { innerWidth: 1600, innerHeight: 900 }), {
    width: 1600,
    height: 900
  });
  assert.deepEqual(backgroundViewportSize(canvas, {}), { width: 800, height: 600 });
});

test('static backgrounds observe the viewport wrapper instead of the self-sized canvas', () => {
  const wrapper = {};
  const canvas = { parentElement: wrapper };
  const orphanCanvas = {};

  assert.equal(backgroundResizeTarget(canvas), wrapper);
  assert.equal(backgroundResizeTarget(orphanCanvas), orphanCanvas);
});

test('palette interpolation clamps progress and produces Pixi tints', () => {
  assert.deepEqual(interpolateRgb([0, 20, 40], [100, 120, 140], 0.5), [50, 70, 90]);
  assert.deepEqual(interpolateRgb([1, 2, 3], [9, 9, 9], -1), [1, 2, 3]);
  assert.equal(rgbToTint([103, 217, 139]), 0x67d98b);
});

test('motion targets are stable per normalized artwork URL', () => {
  const first = motionParametersForUrl('https://img.example/cover.jpg');
  const repeated = motionParametersForUrl('https://img.example/cover.jpg');
  const other = motionParametersForUrl('https://img.example/other.jpg');

  assert.deepEqual(first, repeated);
  assert.notDeepEqual(first, other);
  assert.equal(normalizeBackgroundUrl('  cover.jpg  '), 'cover.jpg');
});

test('rapid artwork requests mark superseded Pixi work as stale', () => {
  const background = new PixiArtworkBackground({});
  background.requestId = 7;
  background.requestedArtwork = 'new-cover.jpg';

  assert.equal(background.isStale(6, 'old-cover.jpg'), true);
  assert.equal(background.isStale(7, 'new-cover.jpg'), false);
  background.destroy();
  assert.equal(background.isStale(7, 'new-cover.jpg'), true);
});

test('Pixi motion updates a point-like displacement scale without requiring set()', () => {
  const background = new PixiArtworkBackground({});
  background.ready = true;
  background.width = 1200;
  background.height = 800;
  background.layers.clear();
  background.displacementSprite = { position: { set() {} } };
  background.displacementFilter = { scale: { x: 0, y: 0 } };

  assert.doesNotThrow(() => background.applyMotion());
  assert.equal(background.displacementFilter.scale.x, background.motionState.distortion);
  assert.equal(
    background.displacementFilter.scale.y,
    background.motionState.distortion * 0.72
  );
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

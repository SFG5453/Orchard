import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import vm from 'node:vm';

const receiverCode = readFileSync(new URL('./receiver.js', import.meta.url), 'utf8');

function harness(fetch, extras = {}) {
  const listeners = new Map();
  const elements = new Map();
  let playerState = 'IDLE';
  const classList = () => {
    const classes = new Set();
    return {
      add: name => classes.add(name),
      remove: name => classes.delete(name),
      contains: name => classes.has(name),
    };
  };
  function element(id) {
    if (!elements.has(id)) {
      elements.set(id, {
        classList: classList(),
        style: {},
        paused: true,
        textContent: '',
        src: '',
        load() {},
        pause() { this.paused = true; },
        play() {
          this.paused = false;
          this.onplaying?.();
          return Promise.resolve();
        },
        canPlayType: () => '',
        removeAttribute(name) { if (name === 'src') this.src = ''; },
        getAttribute(name) { return name === 'src' ? this.src : null; },
      });
    }
    return elements.get(id);
  }
  const hlsInstances = [];
  class Hls {
    static Events = { MEDIA_ATTACHED: 'attached', ERROR: 'error' };
    static isSupported() { return true; }
    constructor() { this.listeners = new Map(); hlsInstances.push(this); }
    on(event, handler) { this.listeners.set(event, handler); }
    attachMedia() { this.listeners.get('attached')?.(); }
    loadSource(url) { this.url = url; }
    destroy() { this.destroyed = true; }
  }
  const cast = {
    framework: {
      events: { EventType: Object.fromEntries(
        ['MEDIA_STATUS', 'PLAYING', 'PAUSE', 'BUFFERING', 'ENDED', 'TIME_UPDATE'].map(name => [name, name]),
      ) },
      CastReceiverContext: { getInstance: () => ({
        getPlayerManager: () => ({
          addEventListener: (event, handler) => listeners.set(event, handler),
          getMediaInformation: () => null,
          getPlayerState: () => playerState,
        }),
        start() {},
      }) },
      PlaybackConfig: class {},
      CastReceiverOptions: class {},
    },
  };
  vm.runInNewContext(receiverCode, {
    document: { getElementById: element },
    cast,
    Hls,
    fetch,
    AbortController,
    URLSearchParams,
    setTimeout,
    console,
    ...extras,
  });
  return {
    element,
    hlsInstances,
    media(contentId, title, artworkUrl = '', state = null) {
      if (state) playerState = state;
      listeners.get('MEDIA_STATUS')({ mediaStatus: {
        playerState: state,
        media: { contentId, metadata: {
          title,
          artist: 'SZA',
          albumName: 'Ctrl',
          images: artworkUrl ? [{ url: artworkUrl }] : [],
        } },
      } });
    },
    playing() { playerState = 'PLAYING'; listeners.get('PLAYING')(); },
    paused() { playerState = 'PAUSED'; listeners.get('PAUSE')(); },
    buffering(isBuffering, nextState = 'PLAYING') {
      playerState = isBuffering ? 'BUFFERING' : nextState;
      listeners.get('BUFFERING')({ isBuffering });
    },
  };
}

const tick = () => new Promise(resolve => setImmediate(resolve));

test('resolves HLS artwork for the active Cast track and follows playback state', async () => {
  const receiver = harness(async () => ({
    ok: true,
    json: async () => ({ name: 'Broken Clocks', artist: 'SZA', animated: 'https://example.test/canvas.m3u8' }),
  }));
  receiver.media('track-1', 'Broken Clocks');
  receiver.playing();
  await tick();
  assert.equal(receiver.hlsInstances[0].url, 'https://example.test/canvas.m3u8');
  assert.equal(receiver.element('artwork-video').paused, false);
  receiver.paused();
  assert.equal(receiver.element('artwork-video').paused, true);
});

test('ignores an artwork lookup that finishes after the track changes', async () => {
  let finishOldLookup;
  const receiver = harness(url => {
    if (url.includes('Broken+Clocks')) {
      return new Promise(resolve => { finishOldLookup = resolve; });
    }
    return Promise.resolve({
      ok: true,
      json: async () => ({ name: 'New Song', artist: 'SZA', animated: 'https://example.test/new.m3u8' }),
    });
  });
  receiver.media('track-1', 'Broken Clocks');
  receiver.media('track-2', 'New Song');
  await tick();
  finishOldLookup({
    ok: true,
    json: async () => ({ name: 'Broken Clocks', artist: 'SZA', animated: 'https://example.test/old.m3u8' }),
  });
  await tick();
  assert.equal(receiver.hlsInstances.length, 1);
  assert.equal(receiver.hlsInstances[0].url, 'https://example.test/new.m3u8');
});

test('Kawarp background renders at quarter size and stops when paused', async () => {
  const instances = [];
  const window = {
    innerWidth: 1920,
    innerHeight: 1080,
    matchMedia: () => ({ matches: false }),
    addEventListener(event, callback) { this[event] = callback; },
  };
  class Kawarp {
    constructor(canvas) { this.canvas = canvas; this.starts = 0; this.stops = 0; instances.push(this); }
    resize() {}
    renderFrame() {}
    loadImage(url) { this.url = url; return Promise.resolve(); }
    start() { this.starts++; }
    stop() { this.stops++; }
  }
  const receiver = harness(async () => ({ ok: false }), { window, KawarpCore: { Kawarp } });
  receiver.media('track-1', 'Broken Clocks', 'https://example.test/cover.jpg');
  await tick();
  assert.equal(receiver.element('ambient-warp').width, 480);
  assert.equal(receiver.element('ambient-warp').height, 270);
  assert.equal(receiver.element('ambient-warp').classList.contains('active'), true);
  receiver.playing();
  assert.ok(instances[0].starts > 0);
  receiver.paused();
  assert.ok(instances[0].stops > 0);

  window.innerWidth = 3840;
  window.innerHeight = 2160;
  window.resize();
  assert.equal(receiver.element('ambient-warp').width, 640);
  assert.equal(receiver.element('ambient-warp').height, 360);
});

test('status leaves Buffering when CAF reports recovery or a new media state', () => {
  const receiver = harness(async () => ({ ok: false }));
  receiver.media('track-1', 'Broken Clocks', '', 'BUFFERING');
  assert.equal(receiver.element('playback-indicator').textContent, 'Buffering');
  receiver.buffering(false);
  assert.equal(receiver.element('playback-indicator').textContent, 'Playing');
  receiver.buffering(true);
  assert.equal(receiver.element('playback-indicator').textContent, 'Buffering');
  receiver.media('track-1', 'Broken Clocks', '', 'PAUSED');
  assert.equal(receiver.element('playback-indicator').textContent, 'Paused');
  receiver.buffering(false, 'PAUSED');
  assert.equal(receiver.element('playback-indicator').textContent, 'Paused');
});

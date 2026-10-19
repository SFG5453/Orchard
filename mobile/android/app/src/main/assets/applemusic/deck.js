'use strict';
// One MusicKit player. Runs inside a same-origin srcdoc frame (see deckSource), where it gets
// its own MusicKit singleton; parent.orchardDeckHost drives it.
function deckMain() {
  const NAME = window.name;
  const host = parent.orchardDeckHost;

  // MusicKit pauses every other player on this origin through this storage claim.
  window.addEventListener('storage', (e) => { if (e.key === 'mk-player-tsid') e.stopImmediatePropagation(); }, true);

  // Song -> stream into the parent's mixer. This frame's own output stays silent so every
  // deck and the mix render share the mixer's clock.
  const context = new AudioContext({ sampleRate: 48000 });
  const tap = context.createGain();
  const stream = context.createMediaStreamDestination();
  tap.connect(stream);
  const sink = context.createGain();
  sink.gain.value = 0;
  tap.connect(sink).connect(context.destination);
  let mk = null;
  let active = null;
  let songId = '';
  let parked = false;
  let finished = false;
  let pendingSeek = -1;

  function playingElement() {
    return active && !active.paused ? active : null;
  }

  const elements = [];
  // MusicKit pools its media elements; route each one through this deck's graph.
  const createElement = document.createElement.bind(document);
  document.createElement = function (tag, options) {
    const element = createElement(tag, options);
    if (/^audio$/i.test(tag)) {
      elements.push(element);
      pinRate(element);
      setTimeout(() => {
        try { context.createMediaElementSource(element).connect(tap); } catch (e) { host.log(NAME, 'route failed: ' + e); }
        element.addEventListener('playing', () => { active = element; scanner.playing(element); host.changed(NAME); });
        element.addEventListener('waiting', () => { if (element === active) scanner.stalled(); });
        element.addEventListener('pause', () => host.changed(NAME));
        element.addEventListener('ended', () => { if (element === active) { scanner.ended(); finish(); } });
      }, 0);
    }
    return element;
  };

  // Scans play muted at `scanRate` without pitch correction. MusicKit resets the rate on
  // every pooled element it takes, and loads reset it to the default, so both are pinned.
  let scanRate = 0;
  const rateProperty = Object.getOwnPropertyDescriptor(HTMLMediaElement.prototype, 'playbackRate');
  const defaultRateProperty = Object.getOwnPropertyDescriptor(HTMLMediaElement.prototype, 'defaultPlaybackRate');
  const pitchProperty = Object.getOwnPropertyDescriptor(HTMLMediaElement.prototype, 'preservesPitch');
  function pinRate(element) {
    for (const [name, property, scanValue] of [['playbackRate', rateProperty, () => scanRate || undefined],
      ['defaultPlaybackRate', defaultRateProperty, () => scanRate || undefined],
      ['preservesPitch', pitchProperty, () => (scanRate ? false : undefined)]]) {
      Object.defineProperty(element, name, {
        configurable: true,
        get() { return property.get.call(this); },
        set(value) { const pinned = scanValue(); property.set.call(this, pinned === undefined ? value : pinned); },
      });
    }
    element.addEventListener('play', () => { if (scanRate) applyRate(element); });
    element.addEventListener('loadedmetadata', () => { if (scanRate) applyRate(element); });
  }
  function applyRate(element) {
    pitchProperty.set.call(element, !scanRate);
    defaultRateProperty.set.call(element, scanRate || 1);
    rateProperty.set.call(element, scanRate || 1);
  }
  function setScanRate(rate) {
    scanRate = rate;
    for (const element of elements) applyRate(element);
  }

  // Interleaved stereo from the tap, in blocks, while capturing.
  const CAPTURE_SOURCE = `
class Capture extends AudioWorkletProcessor {
  constructor() {
    super();
    this.on = false; this.block = new Float32Array(65536); this.n = 0;
    this.port.onmessage = (e) => {
      if (e.data === 'on') this.on = true;
      else if (e.data === 'off') this.on = false;
      else if (e.data === 'flush') { this.flush(); this.port.postMessage('flushed'); }
    };
  }
  flush() { if (this.n) { const out = this.block.slice(0, this.n); this.port.postMessage(out, [out.buffer]); this.n = 0; } }
  process(inputs) {
    const input = inputs[0];
    if (this.on && input.length) {
      const left = input[0], right = input[1] || input[0];
      for (let i = 0; i < left.length; i++) { this.block[this.n++] = left[i]; this.block[this.n++] = right[i]; }
      if (this.n >= this.block.length - 512) this.flush();
    }
    return true;
  }
}
registerProcessor('orchard-capture', Capture);`;
  let captureNode = null;
  async function captureReady() {
    if (captureNode) return;
    await context.audioWorklet.addModule(URL.createObjectURL(new Blob([CAPTURE_SOURCE], { type: 'text/javascript' })));
    captureNode = new AudioWorkletNode(context, 'orchard-capture', { numberOfInputs: 1, numberOfOutputs: 1, outputChannelCount: [2] });
    const silent = context.createGain();
    silent.gain.value = 0;
    tap.connect(captureNode).connect(silent).connect(context.destination);
    captureNode.port.onmessage = (e) => {
      if (e.data === 'flushed') scanner.flushed();
      else scanner.chunk(e.data);
    };
  }

  // One capture at a time: blocks go to `sink`, starting when the song first plays and
  // pausing while it stalls, so the samples stay on the song's own timeline.
  const scanner = {
    job: null,
    playing(element) {
      const job = this.job;
      if (!job || element.paused) return;
      if (job.armed && scanRate && element.playbackRate !== scanRate) { this.fail('playback rate was not applied'); return; }
      if (job.start === undefined) job.start = element.currentTime;
      if (job.armed) captureNode.port.postMessage('on');
    },
    stalled() { if (this.job && this.job.armed) captureNode.port.postMessage('off'); },
    ended() { if (this.job) this.stop(); },
    chunk(data) { if (this.job) this.job.sink(data, this.job.start); },
    stop() {
      const job = this.job;
      if (!job || !job.armed) return;
      job.armed = false;
      captureNode.port.postMessage('off');
      captureNode.port.postMessage('flush');
    },
    flushed() {
      const job = this.job;
      if (!job || job.armed) return;
      this.job = null;
      job.resolve(job.start === undefined ? -1 : job.start);
    },
    fail(reason) {
      const job = this.job;
      if (!job) return;
      this.job = null;
      captureNode.port.postMessage('off');
      job.reject(new Error(reason));
    },
    begin(sink) {
      return new Promise((resolve, reject) => { this.job = { armed: true, sink, resolve, reject }; });
    },
  };

  function finish() {
    if (finished || parked || !songId) return;
    finished = true;
    host.ended(NAME);
  }

  function loading() {
    const S = MusicKit.PlaybackStates;
    return [S.loading, S.waiting, S.stalled, S.seeking].includes(mk.playbackState);
  }

  function waitForPlaying() {
    return new Promise((resolve, reject) => {
      const S = MusicKit.PlaybackStates;
      const done = (error) => { clearTimeout(timer); mk.removeEventListener('playbackStateDidChange', watch); error ? reject(error) : resolve(); };
      const watch = () => { if (mk.playbackState === S.playing) done(); };
      const timer = setTimeout(() => done(new Error('Apple Music took too long to start.')), 20000);
      mk.addEventListener('playbackStateDidChange', watch);
      watch();
    });
  }

  // Resolves once the element can play from where it sits, or after `ms` regardless.
  function buffered(ms) {
    return new Promise((resolve) => {
      if (!active || active.readyState >= 3) { resolve(); return; }
      const done = () => { clearTimeout(timer); active.removeEventListener('canplay', done); resolve(); };
      const timer = setTimeout(done, ms);
      active.addEventListener('canplay', done);
    });
  }

  async function start(position) {
    await context.resume();
    await mk.play();
    if (position > 0) await mk.seekToTime(position);
  }

  window.deck = {
    stream: stream.stream,
    async boot(developerToken, bitrate) {
      await new Promise((resolve, reject) => {
        const script = createElement('script');
        script.src = 'https://js-cdn.music.apple.com/musickit/v3/musickit.js';
        script.onload = resolve;
        script.onerror = () => reject(Object.assign(new Error('Could not load Apple Music.'), { errorCode: 'NETWORK_ERROR' }));
        document.head.appendChild(script);
      });
      mk = await MusicKit.configure({ developerToken, app: { name: 'Orchard', build: '1' } });
      this.setBitrate(bitrate);
      const S = MusicKit.PlaybackStates;
      mk.addEventListener('playbackStateDidChange', () => {
        if (mk.playbackState === S.ended || mk.playbackState === S.completed) finish();
        host.changed(NAME);
      });
      mk.addEventListener('playbackDurationDidChange', () => host.changed(NAME));
      mk.addEventListener('mediaPlaybackError', (e) => { scanner.fail('playback error'); host.failed(NAME, e); });
      mk.addEventListener('drmUnsupported', () => host.failed(NAME, { errorCode: 'DRM_UNSUPPORTED' }));
      return mk.isAuthorized;
    },
    setBitrate(bitrate) {
      mk.bitrate = bitrate === 'standard' ? MusicKit.PlaybackBitrate.STANDARD : MusicKit.PlaybackBitrate.HIGH;
    },
    async load(id, position, play) {
      songId = id;
      finished = false;
      parked = false;
      await mk.setQueue({ song: id });
      pendingSeek = play ? -1 : position;
      if (play) await start(position);
    },
    // Buffers `id` paused at `at` seconds, so play() later starts at once.
    async park(id, at = 0) {
      songId = id;
      finished = false;
      parked = true;
      await mk.setQueue({ song: id });
      await start(0);
      await waitForPlaying();
      mk.pause();
      await mk.seekToTime(at);
      await buffered(3000);
      return this.time();
    },
    async play() {
      parked = false;
      const position = pendingSeek;
      pendingSeek = -1;
      await start(Math.max(0, position));
    },
    // Plays `id` muted from its start at `rate` and streams every block to `sink`.
    // Resolves with the song time capture began at, so the caller can pad the gap.
    async scan(id, rate, sink) {
      await captureReady();
      await context.resume();
      songId = id;
      parked = true;
      setScanRate(rate);
      const done = scanner.begin(sink);
      try {
        await mk.setQueue({ song: id });
        setScanRate(rate);
        await mk.play();
        return await done;
      } finally {
        scanner.fail('scan stopped');
        setScanRate(0);
        if (mk.playbackState !== MusicKit.PlaybackStates.none) await mk.stop();
        songId = '';
        parked = false;
      }
    },
    // Captures `seconds` of `id` from about `start` at 1x, muted. Resolves with the song time
    // of the first captured sample (by the element clock; the caller refines it).
    async clip(id, start, seconds, sink) {
      await captureReady();
      await context.resume();
      songId = id;
      parked = true;
      setScanRate(0);
      try {
        await mk.setQueue({ song: id });
        await mk.play();
        await waitForPlaying();
        mk.pause();
        await mk.seekToTime(start);
        await buffered(3000);
        const done = scanner.begin(sink);
        await mk.play();
        while (scanner.job && (!active || active.currentTime < start + seconds)) await new Promise((r) => setTimeout(r, 20));
        scanner.stop();
        return await done;
      } finally {
        scanner.fail('clip stopped');
        if (mk.playbackState !== MusicKit.PlaybackStates.none) await mk.stop();
        songId = '';
        parked = false;
      }
    },
    pause() { if (mk) mk.pause(); },
    async seek(position) {
      finished = false;
      if (pendingSeek >= 0) pendingSeek = position;
      else await mk.seekToTime(position);
    },
    async stop() {
      // Ends a scan or clip too, so its caller unwinds and frees the engine's songs.
      scanner.fail('deck stopped');
      songId = '';
      parked = false;
      pendingSeek = -1;
      if (mk && mk.playbackState !== MusicKit.PlaybackStates.none) await mk.stop();
    },
    get id() { return songId; },
    get parked() { return parked; },
    get playing() { return !!playingElement(); },
    // The media element's own clock, which the mixer's audio locator refines.
    time() {
      if (active) return active.currentTime;
      return mk ? mk.currentPlaybackTime || 0 : 0;
    },
    // Seconds left in the playing song by the media element's own clock; -1 when not playing.
    remaining() {
      const element = playingElement();
      if (!element || parked) return -1;
      const duration = Number.isFinite(element.duration) && element.duration > 0 ? element.duration : mk.currentPlaybackDuration;
      return duration > 0 ? Math.max(0, duration - element.currentTime) : -1;
    },
    state() {
      const S = MusicKit.PlaybackStates;
      const element = playingElement();
      const position = element ? element.currentTime : mk.currentPlaybackTime;
      return {
        id: songId,
        playing: mk.playbackState === S.playing || (loading() && pendingSeek < 0 && !!active && !active.paused),
        loading: loading(),
        position: pendingSeek >= 0 ? pendingSeek : position || 0,
        duration: mk.currentPlaybackDuration || 0,
      };
    },
  };
  host.attached(NAME);
}

// Frame document for one deck.
function deckSource() {
  return '<meta charset="utf-8"><script>(' + deckMain.toString() + ')();<\/script>';
}

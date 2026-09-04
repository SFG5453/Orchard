<script setup>
import { onBeforeUnmount, onMounted } from 'vue';

const props = defineProps({ app: { type: Object, required: true } });
const bridge = window.orchardNativeAudio;
const decks = [];

class NativeAudioElement {
  constructor(deck, handlers) {
    this.__orchardNative = true;
    this.deck = deck;
    this.handlers = handlers;
    this.listeners = new Map();
    this._src = '';
    this._currentTime = 0;
    this._duration = 0;
    this._volume = 1;
    this._rate = 1;
    this.paused = true;
    this.readyState = 0;
    this.error = null;
    this._sampleRate = 0;
    this._samples = new Float32Array(0);
    this._spectrum = Array.from({ length: 32 }, () => 0);
    this.pendingLoad = null;
    this.loadedSource = '';
  }

  get src() { return this._src; }
  set src(value) {
    this._src = String(value || '');
    queueMicrotask(() => this.load());
  }
  get currentSrc() { return this._src; }
  get currentTime() { return this._currentTime; }
  set currentTime(value) {
    this._currentTime = Math.max(0, Number(value) || 0);
    void bridge.seek(this.deck, this._currentTime);
  }
  get duration() { return this._duration; }
  get volume() { return this._volume; }
  set volume(value) {
    this._volume = Math.max(0, Math.min(1, Number(value) || 0));
    bridge.setVolume(this.deck, this._volume);
  }
  get playbackRate() { return this._rate; }
  set playbackRate(value) {
    this._rate = Math.max(0.5, Math.min(2, Number(value) || 1));
    bridge.setRate(this.deck, this._rate);
  }

  canPlayType(type) { return /^audio\/(?:mp4|mpeg|webm|ogg|flac)/.test(type) ? 'probably' : 'maybe'; }
  setSinkId() { return Promise.resolve(); }
  removeAttribute(name) { if (name === 'src') this._src = ''; }

  spectrum(size = 32) {
    if (size === this._spectrum.length) return [...this._spectrum];
    return Array.from({ length: size }, (_, index) => {
      const sourceIndex = Math.min(
        this._spectrum.length - 1,
        Math.floor(index * this._spectrum.length / Math.max(1, size))
      );
      return this._spectrum[sourceIndex] || 0;
    });
  }

  samples() {
    if (!this._sampleRate || !this._samples.length) return null;
    return { samples: new Float32Array(this._samples), sampleRate: this._sampleRate };
  }

  addEventListener(event, callback) {
    if (!this.listeners.has(event)) this.listeners.set(event, new Set());
    this.listeners.get(event).add(callback);
  }
  removeEventListener(event, callback) { this.listeners.get(event)?.delete(callback); }

  dispatch(event) {
    const value = { target: this, type: event };
    this.handlers[event]?.(value);
    this.listeners.get(event)?.forEach((callback) => callback(value));
  }

  load() {
    if (!this._src) {
      this.loadedSource = '';
      this.readyState = 0;
      this.pendingLoad = bridge.clear(this.deck);
      return this.pendingLoad;
    }
    if (this.loadedSource === this._src && this.pendingLoad) return this.pendingLoad;
    this.loadedSource = this._src;
    this.readyState = 1;
    this.dispatch('waiting');
    this.pendingLoad = bridge.load(this.deck, this._src).then((state) => {
      this.apply(state);
      if (state.error) throw new Error(state.error);
      this.dispatch('loadedmetadata');
      this.dispatch('canplay');
      return state;
    }).catch((error) => {
      this.error = error;
      this.dispatch('error');
      throw error;
    });
    return this.pendingLoad;
  }

  async play() {
    if (this.pendingLoad) await this.pendingLoad;
    await bridge.play(this.deck);
    this.paused = false;
    this.dispatch('play');
    this.dispatch('playing');
  }

  pause() {
    this.paused = true;
    void bridge.pause(this.deck);
    this.dispatch('pause');
  }

  apply(state) {
    const wasPlaying = !this.paused;
    this._currentTime = Number(state.position) || 0;
    this._duration = Number(state.duration) || 0;
    this.readyState = state.ready ? 4 : (state.loading ? 1 : 0);
    this.paused = !state.playing;
    this.error = state.error ? new Error(state.error) : null;
    this._sampleRate = Number(state.sampleRate) || 0;
    this._samples = Float32Array.from(state.samples || []);
    this._spectrum = Array.from(state.spectrum || [], (value) => Number(value) || 0);
    if (state.playing) this.dispatch('timeupdate');
    if (wasPlaying && !state.playing && this._duration && this._currentTime >= this._duration - 0.05) {
      this.dispatch('ended');
    }
  }
}

function handlers() {
  return {
    timeupdate: props.app.onAudioTime,
    loadedmetadata: props.app.onAudioLoaded,
    waiting: props.app.onAudioWaiting,
    playing: props.app.onAudioPlaying,
    canplay: props.app.onAudioCanPlay,
    play: props.app.onAudioPlay,
    pause: props.app.onAudioPause,
    error: props.app.onAudioError,
    ended: props.app.onAudioEnded
  };
}

let timer = 0;
onMounted(() => {
  const main = new NativeAudioElement('main', handlers());
  const next = new NativeAudioElement('next', handlers());
  decks.push(main, next);
  props.app.audioRef.value = main;
  props.app.nextAudioRef.value = next;
  timer = window.setInterval(() => {
    decks.forEach((deck) => bridge.state(deck.deck).then((state) => deck.apply(state)).catch(() => {}));
  }, 80);
});

onBeforeUnmount(() => {
  window.clearInterval(timer);
  decks.forEach((deck) => void bridge.clear(deck.deck));
  props.app.audioRef.value = null;
  props.app.nextAudioRef.value = null;
});
</script>

<template><span hidden aria-hidden="true" /></template>

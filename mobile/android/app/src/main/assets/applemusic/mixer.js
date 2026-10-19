'use strict';
// One AudioContext for every deck and the mix render, so seams land on one sample clock.
// Each deck arrives as a MediaStream: stream -> delay -> fade gain -> master -> speakers.

const MIX_RATE = 48000;
// Monitor blocks are 4096 frames; 60 keep about five seconds per deck.
const MONITOR_FRAMES = 4096;
const MONITOR_BLOCKS = 60;
// Below this correlation the clip is not the reference's recording.
const MATCH_SCORE = 0.7;
// Element clock to mixer input, measured at about 105 ms. The search spans ±0.4 s around it,
// well inside one bar, so a repeated bar cannot pass for the right one.
const BRIDGE_LATENCY = 0.1;
const SEARCH_SECONDS = 0.4;

const MONITOR_SOURCE = `
class Monitor extends AudioWorkletProcessor {
  constructor() { super(); this.block = new Float32Array(${MONITOR_FRAMES}); this.n = 0; this.start = 0; }
  process(inputs) {
    const input = inputs[0];
    if (!input.length) { this.n = 0; return true; }
    const left = input[0], right = input[1] || input[0];
    if (this.n === 0) this.start = currentFrame;
    for (let i = 0; i < left.length; i++) this.block[this.n++] = (left[i] + right[i]) * 0.5;
    if (this.n === ${MONITOR_FRAMES}) {
      const data = this.block.slice();
      this.port.postMessage({ frame: this.start, data }, [data.buffer]);
      this.n = 0;
    }
    return true;
  }
}
registerProcessor('orchard-monitor', Monitor);`;

class MixChannel {
  constructor(mixer, stream) {
    const context = mixer.context;
    this.context = context;
    this.source = context.createMediaStreamSource(stream);
    this.delay = context.createDelay(2.0);
    this.gain = context.createGain();
    this.gain.gain.value = 0;
    this.source.connect(this.delay).connect(this.gain).connect(mixer.master);
    // Mono blocks of what reaches the delay, stamped with mixer frames.
    this.blocks = [];
    const monitor = new AudioWorkletNode(context, 'orchard-monitor', { numberOfInputs: 1, numberOfOutputs: 1, outputChannelCount: [1] });
    const silent = context.createGain();
    silent.gain.value = 0;
    this.source.connect(monitor).connect(silent).connect(context.destination);
    monitor.port.onmessage = (e) => {
      this.blocks.push(e.data);
      if (this.blocks.length > MONITOR_BLOCKS) this.blocks.shift();
    };
  }

  level(value) {
    const now = this.context.currentTime;
    this.gain.gain.cancelScheduledValues(now);
    this.gain.gain.setValueAtTime(value, now);
  }

  // Equal-power ramp to `target`: sine in, cosine out, so an overlap holds its loudness.
  fade(target, seconds, at = this.context.currentTime) {
    const from = this.gain.gain.value;
    const curve = new Float32Array(64);
    for (let i = 0; i < curve.length; i++) {
      const t = i / (curve.length - 1);
      curve[i] = target > from ? from + (target - from) * Math.sin(t * Math.PI / 2)
                               : target + (from - target) * Math.cos(t * Math.PI / 2);
    }
    this.gain.gain.cancelScheduledValues(at);
    this.gain.gain.setValueCurveAtTime(curve, at, Math.max(0.005, seconds));
  }

  release() {
    this.source.disconnect();
    this.gain.disconnect();
  }

  // Delay changes are only made while the channel is silent; a live change would warble.
  setDelay(seconds) { this.delay.delayTime.value = Math.min(1.95, Math.max(0, seconds)); }
  get lag() { return this.delay.delayTime.value; }

  // The newest contiguous `seconds` of monitored audio and the mixer time of its first sample.
  recent(seconds) {
    const need = Math.round(seconds * MIX_RATE);
    const parts = [];
    let frames = 0;
    for (let i = this.blocks.length - 1; i >= 0 && frames < need; i--) {
      if (parts.length && this.blocks[i].frame + MONITOR_FRAMES !== parts[0].frame) break;
      parts.unshift(this.blocks[i]);
      frames += MONITOR_FRAMES;
    }
    if (frames < need) return null;
    const data = new Float32Array(frames);
    parts.forEach((part, k) => data.set(part.data, k * MONITOR_FRAMES));
    return { time: parts[0].frame / MIX_RATE, data };
  }
}

function decimate(samples, step) {
  const out = new Float32Array(Math.floor(samples.length / step));
  for (let i = 0; i < out.length; i++) {
    let sum = 0;
    for (let j = i * step; j < i * step + step; j++) sum += samples[j];
    out[i] = sum / step;
  }
  return out;
}

// Normalised cross-correlation of `clip` against `window` at offsets nominal ± search.
function bestLag(window, clip, nominal, search) {
  let clipEnergy = 0;
  for (const v of clip) clipEnergy += v * v;
  let best = null;
  for (let lag = -search; lag <= search; lag++) {
    const start = nominal + lag;
    if (start < 0 || start + clip.length > window.length) continue;
    let dot = 0, energy = 0;
    for (let i = 0; i < clip.length; i++) {
      const w = window[start + i];
      dot += w * clip[i];
      energy += w * w;
    }
    const score = dot / Math.max(1e-12, Math.sqrt(energy * clipEnergy));
    if (!best || score > best.score) best = { lag, score };
  }
  return best;
}

// Where `clip` sits in `reference` near index `nominal`: at 1.5 kHz, then 12 kHz, then exact.
function align(reference, clip, nominal) {
  const coarse = bestLag(decimate(reference, 32), decimate(clip, 32), Math.round(nominal / 32),
                         Math.round(SEARCH_SECONDS * MIX_RATE / 32));
  if (!coarse) return null;
  let base = (Math.round(nominal / 32) + coarse.lag) * 32;
  const middle = bestLag(decimate(reference, 4), decimate(clip, 4), Math.round(base / 4), 24);
  if (!middle) return null;
  base = (Math.round(base / 4) + middle.lag) * 4;
  const fine = bestLag(reference, clip, base, 8);
  return fine ? { at: base + fine.lag, score: fine.score } : null;
}

class Mixer {
  constructor() {
    this.context = new AudioContext({ sampleRate: MIX_RATE });
    this.master = this.context.createGain();
    this.master.connect(this.context.destination);
    const url = URL.createObjectURL(new Blob([MONITOR_SOURCE], { type: 'text/javascript' }));
    this.ready = this.context.audioWorklet.addModule(url);
    this.render = null;
  }

  async channel(stream) {
    await this.ready;
    await this.context.resume();
    return new MixChannel(this, stream);
  }

  setVolume(value) { this.master.gain.setTargetAtTime(value, this.context.currentTime, 0.01); }
  get now() { return this.context.currentTime; }

  // Song time of the audio `channel` played, located in a reference clip ({start, data} at
  // MIX_RATE) near `approx`. `time` is the mixer time that audio reaches the speakers.
  locate(channel, reference, approx, seconds) {
    const got = channel.recent(seconds);
    if (!got) return null;
    const nominal = Math.round((approx - (this.now - got.time) - BRIDGE_LATENCY - reference.start) * MIX_RATE);
    const found = align(reference.data, got.data, nominal);
    if (!found || found.score < MATCH_SCORE) return null;
    return { song: reference.start + found.at / MIX_RATE, time: got.time + channel.lag };
  }

  // Interleaved int16 stereo (base64) at MIX_RATE as a buffer this context can play.
  buffer(base64) {
    return this.stereoBuffer(pcm16(base64));
  }

  stereoBuffer(samples) {
    const frames = samples.length / 2;
    const buffer = this.context.createBuffer(2, frames, MIX_RATE);
    const left = buffer.getChannelData(0), right = buffer.getChannelData(1);
    for (let i = 0; i < frames; i++) { left[i] = samples[2 * i]; right[i] = samples[2 * i + 1]; }
    return buffer;
  }

  // Plays `buffer` from mixer time `at`, rising in 5 ms. Replaces any render not yet started.
  playRender(buffer, at) {
    this.stopRender();
    const source = this.context.createBufferSource();
    const gain = this.context.createGain();
    source.buffer = buffer;
    source.connect(gain).connect(this.master);
    gain.gain.setValueAtTime(0, this.now);
    gain.gain.setValueAtTime(0, at);
    gain.gain.linearRampToValueAtTime(1, at + 0.005);
    source.start(at);
    this.render = { source, gain, at, end: at + buffer.duration };
    return this.render;
  }

  // Fades the render out over `seconds` ending at `end`.
  endRender(end, seconds) {
    if (!this.render) return;
    this.render.gain.gain.setValueAtTime(1, end - seconds);
    this.render.gain.gain.linearRampToValueAtTime(0, end);
  }

  stopRender() {
    if (!this.render) return;
    try { this.render.source.stop(); } catch (e) { /* never started */ }
    this.render.source.disconnect();
    this.render = null;
  }
}

function pcm16(base64) {
  const bytes = Uint8Array.from(atob(base64), (c) => c.charCodeAt(0));
  const view = new Int16Array(bytes.buffer, 0, bytes.length >> 1);
  const out = new Float32Array(view.length);
  for (let i = 0; i < view.length; i++) out[i] = view[i] / 32767;
  return out;
}

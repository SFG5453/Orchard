'use strict';
// Adaptive mix built for wasm32-wasip1 (crates/orchard-adaptive-mix-web), with Nirovi's int8
// beat model and the vocal model on onnxruntime-web. Loaded only for the scan fallback, then dropped.

const ASSETS = 'orchard-mix://assets/';

async function fetchAsset(name) {
  const response = await fetch(ASSETS + name);
  if (!response.ok) throw new Error('missing ' + name);
  return new Uint8Array(await response.arrayBuffer());
}

// The beat and vocal models run in a worker that is ended after each plan:
// onnxruntime-web's WebAssembly heap only grows, and ending the worker returns all of it.
const MODEL_WORKER = `
const ASSETS = '${ASSETS}';
const FILES = { beat: 'nirovi/nirovi_int8.onnx', vocal: 'vocal-separation/vocals_umxhq_fp32.onnx' };
let ort = null;
const sessions = {};
onmessage = async ({ data }) => {
  try {
    if (!ort) {
      ort = await import(ASSETS + 'ort.wasm.min.mjs');
      ort.env.wasm.wasmPaths = ASSETS;
      // The page is not cross-origin isolated, so no shared memory and no extra threads.
      ort.env.wasm.numThreads = 1;
    }
    if (!sessions[data.model]) {
      const response = await fetch(ASSETS + FILES[data.model]);
      if (!response.ok) throw new Error('missing ' + FILES[data.model]);
      // No arena: it keeps the largest intermediate tensors after a run.
      sessions[data.model] = await ort.InferenceSession.create(new Uint8Array(await response.arrayBuffer()),
        { executionProviders: ['wasm'], enableCpuMemArena: false, enableMemPattern: false });
    }
    const out = await sessions[data.model].run({ [data.inputName]: new ort.Tensor('float32', data.input, data.dims) });
    const outputs = {};
    const transfer = [];
    for (const [name, tensor] of Object.entries(out)) {
      outputs[name] = tensor.data.slice();
      transfer.push(outputs[name].buffer);
    }
    postMessage({ id: data.id, outputs }, transfer);
  } catch (e) {
    postMessage({ id: data.id, error: String((e && e.message) || e) });
  }
};`;

function modelWorker() {
  let worker = null;
  let serial = 0;
  const pending = new Map();
  const failAll = (message) => {
    for (const waiter of pending.values()) waiter.reject(new Error(message));
    pending.clear();
  };
  return {
    run(model, inputName, input, dims) {
      if (!worker) {
        worker = new Worker(URL.createObjectURL(new Blob([MODEL_WORKER], { type: 'text/javascript' })));
        worker.onmessage = ({ data }) => {
          const waiter = pending.get(data.id);
          pending.delete(data.id);
          if (waiter) data.error ? waiter.reject(new Error(data.error)) : waiter.resolve(data.outputs);
        };
        worker.onerror = (e) => failAll('model worker failed: ' + e.message);
      }
      const id = ++serial;
      return new Promise((resolve, reject) => {
        pending.set(id, { resolve, reject });
        worker.postMessage({ id, model, inputName, input, dims }, [input.buffer]);
      });
    },
    release() {
      if (worker) worker.terminate();
      worker = null;
      failAll('model worker ended');
    },
  };
}

async function loadMixEngine() {
  return instantiateMixEngine(await fetchAsset('mixwasm.wasm'), modelWorker());
}

async function instantiateMixEngine(wasmBytes, models) {
  let instance;
  const memory = () => instance.exports.memory.buffer;
  const view = () => new DataView(memory());
  const BADF = 8, NOSYS = 52;
  let line = '';
  // The minimum WASI the crate touches: clocks, randomness and stderr for panics.
  const wasi = {
    random_get: (pointer, length) => { crypto.getRandomValues(new Uint8Array(memory(), pointer, length)); return 0; },
    environ_get: () => 0,
    environ_sizes_get: (count, size) => { view().setUint32(count, 0, true); view().setUint32(size, 0, true); return 0; },
    clock_time_get: (id, precision, out) => {
      view().setBigUint64(out, BigInt(Math.round((id === 0 ? Date.now() : performance.now()) * 1e6)), true);
      return 0;
    },
    fd_close: () => 0,
    fd_fdstat_get: (fd, out) => {
      if (fd > 2) return BADF;
      new Uint8Array(memory(), out, 24).fill(0);
      view().setUint8(out, 2);
      return 0;
    },
    fd_prestat_get: () => BADF,
    fd_prestat_dir_name: () => BADF,
    fd_seek: () => NOSYS,
    path_open: () => NOSYS,
    fd_write: (fd, iovs, count, written) => {
      let total = 0;
      for (let i = 0; i < count; i++) {
        const pointer = view().getUint32(iovs + i * 8, true), length = view().getUint32(iovs + i * 8 + 4, true);
        line += new TextDecoder().decode(new Uint8Array(memory(), pointer, length));
        total += length;
      }
      const parts = line.split('\n');
      line = parts.pop();
      for (const part of parts) note('mix engine: ' + part);
      view().setUint32(written, total, true);
      return 0;
    },
    proc_exit: (code) => { throw new Error('mix engine exited ' + code); },
  };
  // JSPI suspends the wasm stack while a session runs, so the Rust side calls these synchronously.
  const orchard = {
    beat_run: new WebAssembly.Suspending(async (input, length, beat, downbeat, frames) => {
      try {
        const data = new Float32Array(memory(), input, length).slice();
        const out = await models.run('beat', 'input_spectrogram', data, [1, frames, 128]);
        new Float32Array(memory(), beat, frames).set(out.beat);
        new Float32Array(memory(), downbeat, frames).set(out.downbeat);
        return 0;
      } catch (e) { note('mix engine: beat model failed: ' + e); return 1; }
    }),
    vocal_run: new WebAssembly.Suspending(async (input, length, output) => {
      try {
        const data = new Float32Array(memory(), input, length).slice();
        const out = await models.run('vocal', 'mix_magnitude', data, [1, 2, 2049, length / (2 * 2049)]);
        new Float32Array(memory(), output, length).set(out.target_magnitude);
        return 0;
      } catch (e) { note('mix engine: vocal model failed: ' + e); return 1; }
    }),
  };
  ({ instance } = await WebAssembly.instantiate(wasmBytes, { wasi_snapshot_preview1: wasi, orchard }));
  const x = instance.exports;
  if (x._initialize) x._initialize();
  const mix = WebAssembly.promising(x.mk_mix);
  const result = () => ({
    json: JSON.parse(new TextDecoder().decode(new Uint8Array(memory(), x.mk_result_json(), x.mk_result_json_len()))),
    pcm: new Float32Array(memory(), x.mk_result_pcm(), x.mk_result_pcm_len()).slice(),
  });
  const withCopy = (floats, use) => {
    const pointer = x.mk_alloc(floats.length * 4);
    new Float32Array(memory(), pointer, floats.length).set(floats);
    try { return use(pointer); } finally { x.mk_free(pointer, floats.length * 4); }
  };

  return {
    // Streams interleaved stereo into a bounded SongBuilder; the whole track is never kept.
    song(nativeRate, { head, tail }) {
      let builder = x.mk_builder(nativeRate, head ? 1 : 0, tail ? 1 : 0);
      if (!builder) throw new Error('mix engine refused the song');
      return {
        push(stereo) {
          if (!builder) return;
          if (withCopy(stereo, (pointer) => x.mk_push(builder, pointer, stereo.length)) !== 0) throw new Error('song rejected audio');
        },
        finish() {
          const song = x.mk_finish(builder);
          builder = 0;
          if (!song) throw new Error(result().json.error || 'song could not be analysed');
          return { pointer: song, duration: x.mk_song_duration(song) };
        },
        drop() { if (builder) x.mk_drop_builder(builder); builder = 0; },
      };
    },
    dropSong(song) { if (song && song.pointer) x.mk_drop_song(song.pointer); },
    // Plans and renders the pair: {json, pcm} with interleaved stereo at 48 kHz, or json.error.
    async run(request, outgoing, incoming) {
      const bytes = new TextEncoder().encode(JSON.stringify(request));
      const pointer = x.mk_alloc(bytes.length);
      new Uint8Array(memory(), pointer, bytes.length).set(bytes);
      try {
        await mix(pointer, bytes.length, outgoing.pointer, incoming.pointer);
      } finally {
        x.mk_free(pointer, bytes.length);
        models.release();
      }
      return result();
    },
    // Splices a 1x clip over a render window; returns the clip start's correction in seconds.
    patch(song, tail, start, stereo, rate) {
      const correction = withCopy(stereo, (pointer) => x.mk_patch(song.pointer, tail ? 1 : 0, start, pointer, stereo.length, rate));
      if (Number.isNaN(correction)) throw new Error(result().json.error || 'clip did not match');
      return correction;
    },
  };
}

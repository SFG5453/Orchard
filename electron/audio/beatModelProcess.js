// Entry point of the beat-model utility process.
//
// ONNX inference runs here, in a child of the Electron main process, for two
// reasons. Isolation: a session over a quantized transformer is the one piece
// of this codebase that can plausibly crash native code on odd input, and in
// a utility process that costs a transition its model verdict instead of
// costing the user their app. Responsiveness: inference holds two cores for
// about 1.5 s per window, and the main process hosts IPC for playback -- work
// of that size does not belong in its event loop's process, worker pool or
// not.
//
// The protocol is one message per request: {id, spectrogram, modelPath} in,
// {id, result} out, where result is trackBeats' verdict or null. Every failure
// mode inside the request resolves to a null result rather than an error --
// the parent treats "no model opinion" and "model broke" identically, as a
// routing decision back to the Essentia pass.
import { trackBeats } from './beatThisTracker.js';

process.parentPort.on('message', (event) => {
  const { id, spectrogram, modelPath } = event?.data || {};
  if (!Number.isFinite(Number(id))) return;
  void (async () => {
    let result = null;
    try {
      result = await trackBeats(spectrogram, modelPath ? { modelPath } : {});
    } catch {
      result = null;
    }
    try {
      process.parentPort.postMessage({ id, result });
    } catch {
      // The parent is gone; exit quietly rather than linger orphaned.
      process.exit(0);
    }
  })();
});

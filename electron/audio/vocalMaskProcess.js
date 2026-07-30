// Entry point of the vocal-mask utility process. Mirrors beatModelProcess.js
// exactly -- see that file for why inference runs isolated from the main
// process at all. Same one-message-per-request protocol: {id, spectrogram,
// modelPath} in, {id, result} out, where result is trackVocalMask's verdict
// or null.
import { trackVocalMask } from './vocalMaskTracker.js';

process.parentPort.on('message', (event) => {
  const { id, spectrogram, modelPath } = event?.data || {};
  if (!Number.isFinite(Number(id))) return;
  void (async () => {
    let result = null;
    try {
      result = await trackVocalMask(spectrogram, modelPath ? { modelPath } : {});
    } catch {
      result = null;
    }
    try {
      process.parentPort.postMessage({ id, result });
    } catch {
      process.exit(0);
    }
  })();
});

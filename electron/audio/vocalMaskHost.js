// Parent-side handle on the vocal-mask utility process. A thin wrapper over
// the shared lifecycle in modelProcessHost.js -- see that file and
// beatModelHost.js, its sibling wrapper for the beat model.

import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { createModelProcessHost } from './modelProcessHost.js';

const here = path.dirname(fileURLToPath(import.meta.url));
// Resolved out of the asar for the same reason as beatModelHost.js's
// ENTRY_PATH: utilityProcess.fork needs a real file on disk.
const ENTRY_PATH = path
  .join(here, 'vocalMaskProcess.js')
  .replace(`app.asar${path.sep}`, `app.asar.unpacked${path.sep}`);

export function createVocalMaskHost({ modelPath, log, timeoutMs, fork } = {}) {
  const host = createModelProcessHost({
    entryPath: ENTRY_PATH,
    serviceName: 'orchard-vocal-mask',
    modelPath,
    log,
    timeoutMs,
    fork
  });

  /**
   * Runs one spectrogram through the model in the utility process. Resolves
   * to trackVocalMask's verdict, or null whenever the child cannot answer.
   */
  return { track: host.run, stop: host.stop };
}

/*
 * Copyright (C) 2026 SFG545
 *
 * This file is part of Orchard.
 *
 * Orchard is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * Orchard is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Orchard. If not, see <https://www.gnu.org/licenses/>.
 */

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

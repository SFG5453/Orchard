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

// Parent-side handle on the beat-model utility process.
//
// A thin wrapper over the shared model-process lifecycle in
// modelProcessHost.js (fork lazily, re-fork after any exit, kill on timeout,
// resolve every failure to null) -- see that file for why the lifecycle
// itself lives there rather than here. This module only supplies the beat
// model's own entry point, and renames `run` to `track` to keep the name
// meaningful at call sites.

import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { createModelProcessHost } from './modelProcessHost.js';

const here = path.dirname(fileURLToPath(import.meta.url));
// Resolved out of the asar: the entry is listed in asarUnpack, and
// utilityProcess.fork needs the real file. In development the two paths are
// identical, so the replace is a no-op.
const ENTRY_PATH = path
  .join(here, 'beatModelProcess.js')
  .replace(`app.asar${path.sep}`, `app.asar.unpacked${path.sep}`);

export function createBeatModelHost({ modelPath, log, timeoutMs, fork } = {}) {
  // Destructuring defaults in createModelProcessHost apply whenever a value
  // is `undefined`, whether or not the key was present here, so passing
  // these straight through preserves its defaults for callers that omit them.
  const host = createModelProcessHost({
    entryPath: ENTRY_PATH,
    serviceName: 'orchard-beat-model',
    modelPath,
    log,
    timeoutMs,
    fork
  });

  /**
   * Runs one spectrogram through the model in the utility process. Resolves
   * to trackBeats' verdict, or null whenever the child cannot answer.
   */
  return { track: host.run, stop: host.stop };
}

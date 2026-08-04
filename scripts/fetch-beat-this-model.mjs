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

// Downloads the Beat This! ONNX beat/downbeat model.
//
// The model is 83 MB, so it is fetched rather than committed, and it is
// optional at runtime: without it the analyzer falls back to its own
// autocorrelation grid, which still tracks tempo and phase but reads downbeats
// from bass-band onset strength rather than from a trained model.
//
// Licensing, which is the reason this model and not another one: Beat This!
// (CPJKU, ISMIR 2024) releases *both* its code and its trained weights under
// MIT. That is unusual and it is the whole point -- Essentia's pretrained
// models are CC BY-NC-SA, which Orchard cannot ship. The ONNX conversion here
// comes from mosynthkey/beat_this_cpp, also MIT.

import { createHash } from 'node:crypto';
import { mkdir, rename, stat, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
export const MODEL_DIRECTORY = path.join(here, '..', 'models', 'beat-this');
export const MODEL_PATH = path.join(MODEL_DIRECTORY, 'beat_this.onnx');

// Pinned to a commit rather than a branch: a model file that changes underneath
// a release would change every analysis result it produces, and the cache has
// no way to notice.
const MODEL_COMMIT = '07ab790a9ec2eda8093d52d249e3ec4f0510ee72';
const MODEL_URL =
  `https://raw.githubusercontent.com/mosynthkey/beat_this_cpp/${MODEL_COMMIT}/onnx/beat_this.onnx`;
const MODEL_SHA256 = 'c5c1466e08abdb03fdeb50668a06f244b787d564c212490482231a9cfbe9ccbd';
const MODEL_BYTES = 83077778;

/** Resolves true when a verified model is already on disk. */
export async function modelPresent(modelPath = MODEL_PATH) {
  try {
    const info = await stat(modelPath);
    return info.isFile() && info.size === MODEL_BYTES;
  } catch {
    return false;
  }
}

export async function fetchBeatThisModel({ log = console.log } = {}) {
  if (await modelPresent()) {
    log(`[beat-this] model already present at ${MODEL_PATH}`);
    return MODEL_PATH;
  }

  log(`[beat-this] downloading ${(MODEL_BYTES / 1e6).toFixed(0)} MB from ${MODEL_URL}`);
  const response = await fetch(MODEL_URL, { redirect: 'follow' });
  if (!response.ok) {
    throw new Error(`Beat This model download failed: ${response.status} ${response.statusText}`);
  }
  const bytes = Buffer.from(await response.arrayBuffer());

  // Verified before it is installed, not after: an ONNX session given a
  // truncated or substituted graph is a much worse failure than no model.
  const digest = createHash('sha256').update(bytes).digest('hex');
  if (digest !== MODEL_SHA256) {
    throw new Error(
      `Beat This model checksum mismatch: expected ${MODEL_SHA256}, got ${digest}`
    );
  }

  await mkdir(MODEL_DIRECTORY, { recursive: true });
  // Written to a temporary name and renamed so an interrupted download can
  // never leave a half-written file that `modelPresent` would go on to accept.
  const temporary = `${MODEL_PATH}.download`;
  await writeFile(temporary, bytes);
  await rename(temporary, MODEL_PATH);
  log(`[beat-this] installed ${MODEL_PATH}`);
  return MODEL_PATH;
}

if (import.meta.url === `file://${process.argv[1]}`) {
  fetchBeatThisModel().catch((error) => {
    console.error(`[beat-this] ${error?.message || error}`);
    process.exitCode = 1;
  });
}

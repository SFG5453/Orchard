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

// Prints the addon's translation units, one absolute path per line, read from
// native/binding.gyp.
//
// The cross-compile scripts (build-native-windows.sh, build-native-macos-cross.sh)
// invoke the compiler by hand instead of going through node-gyp. Reading the
// shared source list keeps those builds aligned with the host build.
//
// gyp files are JSON plus #-comments, so stripping whole-line comments is enough
// to parse the ones we write here.

import { readFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const nativeDir = path.join(path.dirname(fileURLToPath(import.meta.url)), '..', 'native');
const raw = readFileSync(path.join(nativeDir, 'binding.gyp'), 'utf8');
const stripped = raw
  .split('\n')
  .filter((line) => !line.trimStart().startsWith('#'))
  .join('\n');

const { targets } = JSON.parse(stripped);
const target = targets.find((candidate) => candidate.target_name === 'orchard_audio_analysis');
if (!target) throw new Error('binding.gyp has no orchard_audio_analysis target');

process.stdout.write(target.sources.map((source) => path.join(nativeDir, source)).join('\n') + '\n');

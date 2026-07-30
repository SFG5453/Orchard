// Prints the addon's translation units, one absolute path per line, read from
// native/binding.gyp.
//
// The cross-compile scripts (build-native-windows.sh, build-native-macos-cross.sh)
// invoke the compiler by hand instead of going through node-gyp, so they used to
// carry their own copy of the source list. That copy silently rotted: it never
// gained transition_render.cpp when the offline transition renderer landed, so
// every Windows and cross-built macOS addon since then linked without it and
// failed at link time only once addon.cpp actually referenced the symbols.
// Reading the one list both builds already agree on makes that class of drift
// impossible rather than merely unlikely.
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

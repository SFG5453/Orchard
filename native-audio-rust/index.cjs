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

// Resolves the per-platform binary produced by scripts/build-native-audio*.sh.
// Unlike the node-gyp addon this one is not tied to an Electron version: napi-rs
// resolves every napi_* symbol from the host executable at runtime, so the same
// binary loads under node and electron alike.
const path = require('node:path');

const BINARY_DIRECTORY = path.join(__dirname, 'build');

function binaryName() {
  const architecture = process.arch === 'arm64' ? 'arm64' : 'x64';
  return `orchard-audio-transition-${process.platform}-${architecture}.node`;
}

module.exports = require(path.join(BINARY_DIRECTORY, binaryName()));

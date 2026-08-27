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

import path from 'node:path';

/**
 * Resolves files from either the current application directory or a legacy
 * ASAR installation. Keeping these rules here prevents source-folder depth
 * from affecting development or packaged builds.
 */
export function resolveRuntimePaths({ app, isDev }) {
  const appRoot = app.getAppPath();
  // Legacy ASAR installations kept native assets in app.asar.unpacked. Current
  // package-service and system-Electron installations use a normal directory,
  // where these replacements are no-ops. Never derive application files from
  // process.resourcesPath: shared Electron runtimes live somewhere else.
  const unpackedRoot = appRoot.replace(`app.asar${path.sep}`, `app.asar.unpacked${path.sep}`)
    .replace(/app\.asar$/, 'app.asar.unpacked');

  return {
    appIconPath: path.join(appRoot, isDev ? 'build/icon.png' : 'dist/orchard-logo.png'),
    licensePath: path.join(appRoot, 'LICENSE'),
    nativeModulePath: path.join(unpackedRoot, 'native/build/Release/orchard_audio_analysis.node'),
    // The transition engine is a separate napi-rs addon. Its loader picks the
    // per-platform binary itself, so this points at the resolver rather than a
    // .node file. Unpacked for the same reason as the addon above.
    transitionModulePath: path.join(unpackedRoot, 'native-audio-rust/index.cjs'),
    // Opened by ONNX Runtime's native code, so legacy ASAR installations keep
    // the packaged copy in app.asar.unpacked like the addon above.
    beatModelPath: path.join(unpackedRoot, 'models/beat-this/beat_this_int8.onnx'),
    vocalModelPath: path.join(unpackedRoot, 'models/vocal-separation/vocals_umxhq_int8.onnx'),
    preloadPath: path.join(appRoot, 'electron/preload/index.cjs'),
    rendererEntryPath: path.join(appRoot, 'dist/index.html')
  };
}

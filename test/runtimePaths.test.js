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

import assert from 'node:assert/strict';
import path from 'node:path';
import test from 'node:test';

import { resolveRuntimePaths } from '../electron/main/runtimePaths.js';

test('development runtime paths stay rooted at the application directory', () => {
  const appRoot = path.resolve('fixture-app');
  const resolved = resolveRuntimePaths({
    app: { isPackaged: false, getAppPath: () => appRoot },
    isDev: true
  });

  assert.equal(resolved.appIconPath, path.join(appRoot, 'build/icon.png'));
  assert.equal(resolved.preloadPath, path.join(appRoot, 'electron/preload/index.cjs'));
  assert.equal(resolved.rendererEntryPath, path.join(appRoot, 'dist/index.html'));
  assert.equal(resolved.nativeModulePath, path.join(appRoot, 'native/build/Release/orchard_audio_analysis.node'));
});

test('packaged runtime paths keep native code outside app.asar', () => {
  const resourcesPath = path.resolve('fixture-resources');
  const appRoot = path.join(resourcesPath, 'app.asar');
  const resolved = resolveRuntimePaths({
    app: { isPackaged: true, getAppPath: () => appRoot },
    isDev: false
  });

  assert.equal(resolved.appIconPath, path.join(appRoot, 'dist/orchard-logo.png'));
  assert.equal(resolved.preloadPath, path.join(appRoot, 'electron/preload/index.cjs'));
  assert.equal(resolved.rendererEntryPath, path.join(appRoot, 'dist/index.html'));
  assert.equal(
    resolved.nativeModulePath,
    path.join(resourcesPath, 'app.asar.unpacked/native/build/Release/orchard_audio_analysis.node')
  );
});

test('system-Electron runtime paths stay inside the staged application directory', () => {
  const appRoot = '/usr/lib/orchard/app';
  const resolved = resolveRuntimePaths({
    app: { isPackaged: false, getAppPath: () => appRoot },
    isDev: false
  });

  assert.equal(resolved.nativeModulePath, path.join(appRoot, 'native/build/Release/orchard_audio_analysis.node'));
  assert.equal(resolved.beatModelPath, path.join(appRoot, 'models/beat-this/beat_this_int8.onnx'));
  assert.equal(resolved.vocalModelPath, path.join(appRoot, 'models/vocal-separation/vocals_umxhq_int8.onnx'));
  assert.equal(resolved.preloadPath, path.join(appRoot, 'electron/preload/index.cjs'));
  assert.equal(resolved.rendererEntryPath, path.join(appRoot, 'dist/index.html'));
});

test('legacy system-Electron runtime paths follow the archive, not the shared runtime', () => {
  const appRoot = '/usr/lib/orchard/app.asar';
  const resolved = resolveRuntimePaths({
    app: { isPackaged: true, getAppPath: () => appRoot },
    isDev: false
  });

  const unpacked = '/usr/lib/orchard/app.asar.unpacked';
  assert.equal(resolved.nativeModulePath, path.join(unpacked, 'native/build/Release/orchard_audio_analysis.node'));
  assert.equal(resolved.beatModelPath, path.join(unpacked, 'models/beat-this/beat_this_int8.onnx'));
  assert.equal(resolved.vocalModelPath, path.join(unpacked, 'models/vocal-separation/vocals_umxhq_int8.onnx'));

  assert.equal(resolved.preloadPath, path.join(appRoot, 'electron/preload/index.cjs'));
  assert.equal(resolved.rendererEntryPath, path.join(appRoot, 'dist/index.html'));
});

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

// The system-Electron package installs the payload under its own prefix and
// runs it on a shared runtime, so the unpacked files do not live beside that
// runtime's resources. Anything derived from process.resourcesPath here points
// into the Electron install, where the payload has never existed.
test('system-Electron runtime paths follow the archive, not the shared runtime', () => {
  const appRoot = '/usr/lib/orchard/app.asar';
  const resolved = resolveRuntimePaths({
    app: { isPackaged: true, getAppPath: () => appRoot },
    isDev: false
  });

  const unpacked = '/usr/lib/orchard/app.asar.unpacked';
  assert.equal(resolved.nativeModulePath, path.join(unpacked, 'native/build/Release/orchard_audio_analysis.node'));
  assert.equal(resolved.beatModelPath, path.join(unpacked, 'models/beat-this/beat_this_int8.onnx'));
  assert.equal(resolved.vocalModelPath, path.join(unpacked, 'models/vocal-separation/vocals_umxhq_int8.onnx'));

  // The archive itself is still read through asar for everything Electron can
  // load from inside it.
  assert.equal(resolved.preloadPath, path.join(appRoot, 'electron/preload/index.cjs'));
  assert.equal(resolved.rendererEntryPath, path.join(appRoot, 'dist/index.html'));
});

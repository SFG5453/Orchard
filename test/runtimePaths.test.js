import assert from 'node:assert/strict';
import path from 'node:path';
import test from 'node:test';

import { resolveRuntimePaths } from '../electron/main/runtimePaths.js';

test('development runtime paths stay rooted at the application directory', () => {
  const appRoot = path.resolve('fixture-app');
  const resolved = resolveRuntimePaths({
    app: { isPackaged: false, getAppPath: () => appRoot },
    isDev: true,
    resourcesPath: path.join(appRoot, 'resources')
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
    isDev: false,
    resourcesPath
  });

  assert.equal(resolved.appIconPath, path.join(appRoot, 'dist/orchard-logo.png'));
  assert.equal(resolved.preloadPath, path.join(appRoot, 'electron/preload/index.cjs'));
  assert.equal(resolved.rendererEntryPath, path.join(appRoot, 'dist/index.html'));
  assert.equal(
    resolved.nativeModulePath,
    path.join(resourcesPath, 'app.asar.unpacked/native/build/Release/orchard_audio_analysis.node')
  );
});

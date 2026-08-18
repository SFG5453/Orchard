/*
 * Copyright (C) 2026 SFG545
 *
 * This file is part of Orchard.
 *
 * Orchard is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option)
 * any later version.
 */

import assert from 'node:assert/strict';
import test from 'node:test';

import {
  configureOnnxWebRuntime,
  isIntelMacOS,
  loadOnnxModel,
  onnxExecutionProviders
} from '../electron/audio/onnxRuntime.js';

test('Intel macOS selects the WASM CPU backend', () => {
  assert.equal(isIntelMacOS('darwin', 'x64'), true);
  assert.deepEqual(onnxExecutionProviders('darwin', 'x64'), ['wasm']);
});

test('other release targets keep the native CPU backend', () => {
  for (const target of [
    ['darwin', 'arm64'],
    ['linux', 'x64'],
    ['linux', 'arm64'],
    ['win32', 'x64'],
    ['win32', 'arm64']
  ]) {
    assert.equal(isIntelMacOS(...target), false);
    assert.deepEqual(onnxExecutionProviders(...target), ['cpu']);
  }
});

test('WASM configuration disables proxy workers and points at the packaged binary', () => {
  const runtime = { env: { wasm: {} } };
  assert.equal(configureOnnxWebRuntime(runtime, '/tmp/onnx-wasm'), runtime);
  assert.equal(runtime.env.wasm.numThreads, 1);
  assert.equal(runtime.env.wasm.proxy, false);
  assert.match(runtime.env.wasm.wasmPaths, /onnx-wasm[\\/]$/);
});

test('WASM model loading reads local model bytes while native loading keeps its path', async () => {
  const nativeModel = await loadOnnxModel('models/beat-this/beat_this_int8.onnx', 'linux', 'x64');
  assert.equal(nativeModel, 'models/beat-this/beat_this_int8.onnx');

  const webModel = await loadOnnxModel('package.json', 'darwin', 'x64');
  assert.ok(webModel instanceof Uint8Array);
  assert.ok(webModel.byteLength > 0);
});

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

import path from 'node:path';
import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));

/**
 * onnxruntime-node stopped publishing an Intel macOS (darwin-x64) binding.
 * That one release target uses the CPU-only WebAssembly backend instead.
 */
export function isIntelMacOS(platform = process.platform, architecture = process.arch) {
  return platform === 'darwin' && architecture === 'x64';
}

export function onnxExecutionProviders(platform = process.platform, architecture = process.arch) {
  return isIntelMacOS(platform, architecture) ? ['wasm'] : ['cpu'];
}

export function configureOnnxWebRuntime(runtime, wasmDirectory) {
  if (runtime?.env?.wasm) {
    // The utility process is deliberately kept single-threaded. This avoids
    // worker startup and SharedArrayBuffer requirements while audio is playing.
    runtime.env.wasm.numThreads = 1;
    runtime.env.wasm.proxy = false;
    runtime.env.wasm.wasmPaths = `${wasmDirectory}${path.sep}`;
  }
  return runtime;
}

export async function loadOnnxModel(modelPath, platform = process.platform, architecture = process.arch) {
  // The WebAssembly package uses fetch for string model inputs. Utility
  // processes have a local unpacked model, so give it bytes explicitly.
  return isIntelMacOS(platform, architecture) ? readFile(modelPath) : modelPath;
}

export async function loadOnnxRuntime() {
  if (!isIntelMacOS()) return import('onnxruntime-node');

  const runtime = await import('onnxruntime-web/wasm');
  const wasmDirectory = path.join(
    here,
    '..',
    '..',
    'node_modules',
    'onnxruntime-web',
    'dist'
  );
  return configureOnnxWebRuntime(runtime, wasmDirectory);
}

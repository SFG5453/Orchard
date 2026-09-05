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
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
 * A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Orchard. If not, see <https://www.gnu.org/licenses/>.
 */

import { describe, expect, test } from "bun:test";
import { access, mkdir, mkdtemp, rm, writeFile } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { collectNativeAssets, launcherContents } from "../scripts/native-assets.ts";

function elfX64(): Buffer {
  const binary = Buffer.alloc(64);
  binary.set([0x7f, 0x45, 0x4c, 0x46, 0x02, 0x01]);
  binary.writeUInt16LE(0x3e, 18);
  return binary;
}

function peX64(): Buffer {
  const binary = Buffer.alloc(256);
  binary.set([0x4d, 0x5a]);
  binary.writeUInt32LE(128, 60);
  binary.set([0x50, 0x45, 0x00, 0x00], 128);
  binary.writeUInt16LE(0x8664, 132);
  return binary;
}

async function put(
  root: string,
  relativePath: string,
  contents: string | Buffer = ""
): Promise<void> {
  const target = path.join(root, relativePath);
  await mkdir(path.dirname(target), { recursive: true });
  await writeFile(target, contents);
}

async function exists(candidate: string): Promise<boolean> {
  return access(candidate).then(() => true, () => false);
}

describe("native package launchers", () => {
  test("terminates the Windows Electron app path after a dot", () => {
    expect(launcherContents("win32-x64", "43.4.1")).toBe(
      "@echo off\r\n" +
      "\"%~dp0..\\..\\runtimes\\electron\\43.4.1\\win32-x64\\electron.exe\" \"%~dp0.\" %*\r\n"
    );
  });

  test("one Rust audio addon satisfies both analysis and transition packaging", async () => {
    const root = await mkdtemp(path.join(os.tmpdir(), "orchard-native-assets-"));
    const projectRoot = path.join(root, "project");
    const sharedRoot = path.join(root, "shared");
    const overlaysRoot = path.join(root, "overlays");
    try {
      const binary = elfX64();
      await put(
        projectRoot,
        "native-audio-rust/build/orchard-audio-linux-x64.node",
        binary
      );
      await put(
        projectRoot,
        "native-media/build/orchard-system-media-linux-x64.node",
        binary
      );
      await put(
        sharedRoot,
        "node_modules/onnxruntime-node/bin/napi-v6/linux/x64/onnxruntime_binding.node",
        binary
      );
      for (const relativePath of [
        "package.json",
        "dist/ort.wasm.bundle.min.mjs",
        "dist/ort-wasm-simd-threaded.mjs",
        "dist/ort-wasm-simd-threaded.wasm"
      ]) {
        await put(sharedRoot, path.join("node_modules/onnxruntime-web", relativePath));
      }

      const collection = await collectNativeAssets({
        projectRoot,
        sharedRoot,
        overlaysRoot,
        electronVersion: "43.4.1"
      });

      expect(collection.completeTargets).toEqual(["linux-x64"]);
      const overlay = collection.overlay("linux-x64");
      expect(await exists(path.join(
        overlay,
        "native-audio-rust/build/orchard-audio-linux-x64.node"
      ))).toBe(true);
      expect(await exists(path.join(
        overlay,
        "native/build/Release/orchard_audio_analysis.node"
      ))).toBe(false);
    } finally {
      await rm(root, { recursive: true, force: true });
    }
  });

  test("keeps Windows runtime DLLs beside a complete native addon", async () => {
    const root = await mkdtemp(path.join(os.tmpdir(), "orchard-native-assets-"));
    const projectRoot = path.join(root, "project");
    const sharedRoot = path.join(root, "shared");
    const overlaysRoot = path.join(root, "overlays");
    try {
      const binary = peX64();
      await put(projectRoot, "native-audio-rust/build/orchard-audio-win32-x64.node", binary);
      await put(projectRoot, "native-audio-rust/build/libstdc++-6.dll", binary);
      await put(projectRoot, "native-media/build/orchard-system-media-win32-x64.node", binary);
      await put(sharedRoot, "node_modules/onnxruntime-node/bin/napi-v6/win32/x64/onnxruntime_binding.node", binary);
      await put(sharedRoot, "node_modules/@img/sharp-win32-x64/package.json", "{}");
      await put(sharedRoot, "node_modules/@img/sharp-win32-x64/lib/sharp-win32-x64.node", binary);
      for (const relativePath of [
        "package.json",
        "dist/ort.wasm.bundle.min.mjs",
        "dist/ort-wasm-simd-threaded.mjs",
        "dist/ort-wasm-simd-threaded.wasm"
      ]) {
        await put(sharedRoot, path.join("node_modules/onnxruntime-web", relativePath));
      }

      const collection = await collectNativeAssets({
        projectRoot,
        sharedRoot,
        overlaysRoot,
        electronVersion: "43.4.1"
      });

      expect(collection.completeTargets).toEqual(["win32-x64"]);
      expect(await exists(path.join(
        collection.overlay("win32-x64"),
        "native-audio-rust/build/libstdc++-6.dll"
      ))).toBe(true);
      expect(await exists(path.join(
        collection.overlay("win32-x64"),
        "node_modules/@img/sharp-win32-x64/package.json"
      ))).toBe(true);
      expect(await exists(path.join(
        sharedRoot,
        "node_modules/@img/sharp-win32-x64/package.json"
      ))).toBe(false);
    } finally {
      await rm(root, { recursive: true, force: true });
    }
  });
});

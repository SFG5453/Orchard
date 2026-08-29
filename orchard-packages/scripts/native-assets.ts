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

import { access, chmod, cp, mkdir, readdir, rm, writeFile } from "node:fs/promises";
import path from "node:path";
import { TARGETS, type Target } from "../src/backend/target.ts";
import { binaryTargets } from "./native-binary.ts";

type Evidence = {
  analysis: boolean;
  audio: boolean;
  media: boolean;
  onnx: boolean;
};

export type NativeCollection = {
  completeTargets: Target[];
  overlay: (target: Target) => string;
};

const NATIVE_NAME = /(?:\.node|\.dll|\.dylib|\.so(?:\.\d+)*|\.exe)$/i;
const ONNX_WEB_FILES = [
  "package.json",
  "dist/ort.wasm.bundle.min.mjs",
  "dist/ort-wasm-simd-threaded.mjs",
  "dist/ort-wasm-simd-threaded.wasm"
] as const;

async function exists(candidate: string): Promise<boolean> {
  try {
    await access(candidate);
    return true;
  } catch {
    return false;
  }
}

async function walk(root: string): Promise<string[]> {
  const files: string[] = [];
  const pending = [root];
  while (pending.length > 0) {
    const directory = pending.pop()!;
    for (const entry of await readdir(directory, { withFileTypes: true })) {
      const entryPath = path.join(directory, entry.name);
      if (entry.isDirectory()) pending.push(entryPath);
      else if (entry.isFile()) files.push(entryPath);
    }
  }
  return files;
}

async function copyToOverlay(source: string, relativePath: string, destination: string): Promise<void> {
  const output = path.join(destination, relativePath);
  await mkdir(path.dirname(output), { recursive: true });
  await cp(source, output, { force: true });
}

export async function collectNativeAssets(options: {
  projectRoot: string;
  sharedRoot: string;
  overlaysRoot: string;
  electronVersion: string;
}): Promise<NativeCollection> {
  const evidence = Object.fromEntries(TARGETS.map((target) => [target, {
    analysis: false,
    audio: false,
    media: false,
    onnx: false
  }])) as Record<Target, Evidence>;
  const overlay = (target: Target): string => path.join(options.overlaysRoot, target);
  await Promise.all(TARGETS.map((target) => mkdir(overlay(target), { recursive: true })));

  const nodeModulesRoot = path.join(options.sharedRoot, "node_modules");
  const onnxWebRoot = path.join(nodeModulesRoot, "onnxruntime-web");
  if (!(await exists(onnxWebRoot))) {
    throw new Error("onnxruntime-web is required for the Intel macOS fallback but was not installed.");
  }
  const missingOnnxWebFiles = [] as string[];
  for (const relativePath of ONNX_WEB_FILES) {
    if (!(await exists(path.join(onnxWebRoot, relativePath)))) missingOnnxWebFiles.push(relativePath);
  }
  if (missingOnnxWebFiles.length > 0) {
    throw new Error(`onnxruntime-web is missing required files: ${missingOnnxWebFiles.join(", ")}`);
  }
  for (const relativePath of ONNX_WEB_FILES) {
    await copyToOverlay(
      path.join(onnxWebRoot, relativePath),
      path.join("node_modules", "onnxruntime-web", relativePath),
      overlay("darwin-x64")
    );
  }
  await rm(onnxWebRoot, { recursive: true, force: true });
  evidence["darwin-x64"].onnx = true;

  for (const filePath of await walk(nodeModulesRoot)) {
    const relativePath = path.relative(options.sharedRoot, filePath);
    const targets = await binaryTargets(filePath);
    if (targets.length === 0) {
      if (NATIVE_NAME.test(filePath)) {
        throw new Error(`Native-looking dependency could not be classified: ${relativePath}`);
      }
      continue;
    }

    for (const target of targets) {
      await copyToOverlay(filePath, relativePath, overlay(target));
      const onnxPrefix = path.join("node_modules", "onnxruntime-node", "bin", "napi-v6");
      if (relativePath.startsWith(onnxPrefix) && path.basename(filePath) === "onnxruntime_binding.node") {
        evidence[target].onnx = true;
      }
    }
    await rm(filePath, { force: true });
  }

  const analysisCandidates = (await walk(path.join(options.projectRoot, "native", "build")))
    .filter((candidate) => /orchard_audio_analysis(?:-[\w-]+)?\.node$/.test(candidate))
    .filter((candidate) => !candidate.includes(`${path.sep}obj.target${path.sep}`));
  for (const candidate of analysisCandidates) {
    for (const target of await binaryTargets(candidate)) {
      await copyToOverlay(candidate, "native/build/Release/orchard_audio_analysis.node", overlay(target));
      evidence[target].analysis = true;
    }
  }

  const namedAddons: Array<{ root: string; pattern: RegExp; kind: "audio" | "media" }> = [
    {
      root: path.join(options.projectRoot, "native-audio-rust", "build"),
      pattern: /^orchard-audio-transition-(linux|win32|darwin)-(x64|arm64)\.node$/,
      kind: "audio"
    },
    {
      root: path.join(options.projectRoot, "native-media", "build"),
      pattern: /^orchard-system-media-(linux|win32|darwin)-(x64|arm64)\.node$/,
      kind: "media"
    }
  ];

  for (const addon of namedAddons) {
    for (const candidate of await walk(addon.root)) {
      const match = path.basename(candidate).match(addon.pattern);
      if (!match) continue;
      const target = `${match[1]}-${match[2]}` as Target;
      const detected = await binaryTargets(candidate);
      if (!detected.includes(target)) throw new Error(`${candidate} does not match its target name ${target}.`);
      const relativePath = path.relative(options.projectRoot, candidate);
      await copyToOverlay(candidate, relativePath, overlay(target));
      evidence[target][addon.kind] = true;
    }
  }

  const completeTargets = TARGETS.filter((target) => Object.values(evidence[target]).every(Boolean));
  for (const target of TARGETS) {
    if (!completeTargets.includes(target)) {
      await rm(overlay(target), { recursive: true, force: true });
      continue;
    }
    const marker = path.join(overlay(target), ".orchard-native", `${target}.json`);
    await mkdir(path.dirname(marker), { recursive: true });
    await writeFile(marker, `${JSON.stringify({ schemaVersion: 1, target }, null, 2)}\n`);

    const launcherPath = path.join(overlay(target), target.startsWith("win32-") ? "orchard.cmd" : "orchard");
    const launcher = target.startsWith("win32-")
      ? `@echo off\r\n"%~dp0..\\..\\runtimes\\electron\\${options.electronVersion}\\${target}\\electron.exe" "%~dp0" %*\r\n`
      : target.startsWith("darwin-")
        ? `#!/bin/sh\napp_root=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)\nexec "$app_root/../../runtimes/electron/${options.electronVersion}/${target}/Electron.app/Contents/MacOS/Electron" "$app_root" "$@"\n`
        : `#!/bin/sh\napp_root=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)\nexec "$app_root/../../runtimes/electron/${options.electronVersion}/${target}/electron" --disable-setuid-sandbox "$app_root" "$@"\n`;
    await writeFile(launcherPath, launcher);
    if (!target.startsWith("win32-")) await chmod(launcherPath, 0o755);
  }

  return { completeTargets, overlay };
}

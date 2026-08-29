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

import { access, mkdir, readFile, rename, rm } from "node:fs/promises";
import path from "node:path";
import { verifySha256 } from "./checksum.ts";
import { downloadFile } from "./downloader.ts";
import { extractTarZst } from "./extractor.ts";
import { fetchManifest, resolveAssetUrl, type PackageAsset } from "./manifest.ts";
import { getInstallPaths } from "./paths.ts";
import { detectTarget, type Target } from "./target.ts";

export type InstallProgress = {
  phase: string;
  message: string;
  detail?: string;
  percent: number;
};

export type InstallResult = {
  version: string;
  target: Target;
  installPath: string;
};

type ProgressReporter = (progress: InstallProgress) => void;

async function exists(candidate: string): Promise<boolean> {
  try {
    await access(candidate);
    return true;
  } catch {
    return false;
  }
}

function archiveName(asset: PackageAsset): string {
  const name = path.posix.basename(new URL(resolveAssetUrl(asset)).pathname);
  if (!name.endsWith(".tar.zst")) throw new Error(`Unexpected package filename: ${name}`);
  return name;
}

function formatBytes(bytes: number): string {
  const units = ["B", "KB", "MB", "GB"];
  let value = bytes;
  let unit = units[0]!;
  for (const candidate of units) {
    unit = candidate;
    if (value < 1024 || candidate === units.at(-1)) break;
    value /= 1024;
  }
  return `${value >= 100 || unit === "B" ? value.toFixed(0) : value.toFixed(1)} ${unit}`;
}

async function validateInstallation(directory: string, version: string, target: Target): Promise<void> {
  const required = [
    "package.json",
    "dist/index.html",
    "dist/welcome.html",
    "electron/main/index.js",
    ".orchard-package.json",
    `.orchard-native/${target}.json`,
    "native/build/Release/orchard_audio_analysis.node",
    `native-media/build/orchard-system-media-${target}.node`,
    `native-audio-rust/build/orchard-audio-transition-${target}.node`
  ];

  if (target !== "darwin-x64") {
    const [platform, architecture] = target.split("-");
    required.push(`node_modules/onnxruntime-node/bin/napi-v6/${platform}/${architecture}/onnxruntime_binding.node`);
  } else {
    required.push(
      "node_modules/onnxruntime-web/package.json",
      "node_modules/onnxruntime-web/dist/ort.wasm.bundle.min.mjs",
      "node_modules/onnxruntime-web/dist/ort-wasm-simd-threaded.mjs",
      "node_modules/onnxruntime-web/dist/ort-wasm-simd-threaded.wasm"
    );
  }
  required.push(target.startsWith("win32-") ? "orchard.cmd" : "orchard");

  for (const relativePath of required) {
    if (!(await exists(path.join(directory, relativePath)))) {
      throw new Error(`The staged installation is incomplete: ${relativePath} is missing.`);
    }
  }

  const packageMetadata = JSON.parse(await readFile(path.join(directory, ".orchard-package.json"), "utf8")) as {
    schemaVersion?: unknown;
    version?: unknown;
  };
  if (packageMetadata.schemaVersion !== 1 || packageMetadata.version !== version) {
    throw new Error("The staged installation metadata does not match the selected release.");
  }
}

async function activateInstallation(staging: string, destination: string): Promise<void> {
  const backup = `${destination}.backup-${crypto.randomUUID()}`;
  const hadExisting = await exists(destination);
  let existingMoved = false;

  try {
    if (hadExisting) {
      await rename(destination, backup);
      existingMoved = true;
    }
    await rename(staging, destination);
  } catch (error) {
    if (existingMoved && !(await exists(destination))) {
      try {
        await rename(backup, destination);
      } catch (restoreError) {
        throw new Error(
          `Activation failed and the previous installation could not be restored automatically. ` +
          `It remains at ${backup}. ${restoreError instanceof Error ? restoreError.message : String(restoreError)}`
        );
      }
    }
    throw error;
  }

  if (existingMoved) await rm(backup, { recursive: true, force: true }).catch(() => undefined);
}

export async function installRelease(
  version: string,
  report: ProgressReporter,
  signal?: AbortSignal
): Promise<InstallResult> {
  report({ phase: "manifest", message: "Reading the release manifest…", percent: 2 });
  const manifest = await fetchManifest(signal);
  const release = manifest.releases.find((candidate) => candidate.version === version);
  if (!release) throw new Error(`Orchard ${version} is not present in the package manifest.`);

  const target = detectTarget();
  const native = release.native[target];
  if (!native) throw new Error(`Orchard ${version} has no native package for ${target}.`);

  const { installDirectory, cacheDirectory } = getInstallPaths(target, version, release.electronVersion);
  const installParent = path.dirname(installDirectory);
  const sessionDirectory = path.join(cacheDirectory, "sessions", crypto.randomUUID());
  const stagingDirectory = path.join(installParent, `.orchard.staging-${crypto.randomUUID()}`);
  const sharedPath = path.join(sessionDirectory, archiveName(release.shared));
  const nativePath = path.join(sessionDirectory, archiveName(native));
  const totalBytes = release.shared.size + native.size;

  await mkdir(sessionDirectory, { recursive: true });
  await mkdir(installParent, { recursive: true });
  await rm(stagingDirectory, { recursive: true, force: true });

  let downloadedBefore = 0;
  const download = async (asset: PackageAsset, destination: string, label: string): Promise<void> => {
    await downloadFile({
      url: resolveAssetUrl(asset),
      destination,
      expectedSize: asset.size,
      signal,
      onProgress: ({ downloaded }) => {
        const ratio = (downloadedBefore + downloaded) / totalBytes;
        report({
          phase: `download-${label}`,
          message: `Downloading ${label} package…`,
          detail: `${formatBytes(Math.min(downloaded, asset.size))} of ${formatBytes(asset.size)}`,
          percent: 5 + ratio * 57
        });
      }
    });
    downloadedBefore += asset.size;
  };

  try {
    await download(release.shared, sharedPath, "common");
    await download(native, nativePath, "native");

    report({ phase: "verify-shared", message: "Verifying common package…", percent: 64 });
    await verifySha256(sharedPath, release.shared.sha256);
    report({ phase: "verify-native", message: "Verifying native package…", percent: 69 });
    await verifySha256(nativePath, native.sha256);

    report({ phase: "staging", message: "Creating a safe staging area…", percent: 73 });
    await mkdir(stagingDirectory, { recursive: true });
    report({ phase: "extract-shared", message: "Extracting common package…", percent: 78 });
    await extractTarZst(sharedPath, stagingDirectory);
    report({ phase: "extract-native", message: "Adding native package…", percent: 87 });
    await extractTarZst(nativePath, stagingDirectory);

    report({ phase: "validate", message: "Validating Orchard…", percent: 94 });
    await validateInstallation(stagingDirectory, version, target);
    report({
      phase: "activate",
      message: "Activating the installation…",
      detail: "The previous installation is protected until this completes.",
      percent: 97
    });
    await activateInstallation(stagingDirectory, installDirectory);
    report({ phase: "cleanup", message: "Cleaning temporary files…", percent: 99 });

    return { version, target, installPath: installDirectory };
  } finally {
    await rm(sessionDirectory, { recursive: true, force: true }).catch(() => undefined);
    await rm(stagingDirectory, { recursive: true, force: true }).catch(() => undefined);
  }
}

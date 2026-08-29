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

import { access, cp, mkdir, mkdtemp, readFile, readdir, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { createTarZst, fileMetadata, runCommand } from "./archive.ts";
import { binaryTargets } from "./native-binary.ts";
import { collectNativeAssets } from "./native-assets.ts";
import { extractTarZst } from "../src/backend/extractor.ts";

const VERSION_PATTERN = /^\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?$/;
const packageVersion = process.argv[2];
if (!packageVersion || !VERSION_PATTERN.test(packageVersion)) {
  throw new Error("Usage: bun run package:orchard <version> (for example, 5.0.0)");
}

const managerRoot = path.resolve(import.meta.dir, "..");
const projectRoot = path.resolve(managerRoot, "..");
const outputRoot = path.join(projectRoot, "artifacts", "r2");
const workRoot = await mkdtemp(path.join(tmpdir(), `orchard-package-${packageVersion}-`));
const sharedRoot = path.join(workRoot, "shared");
const overlaysRoot = path.join(workRoot, "native");

async function exists(candidate: string): Promise<boolean> {
  try {
    await access(candidate);
    return true;
  } catch {
    return false;
  }
}

async function copyRequired(source: string, destination: string): Promise<void> {
  if (!(await exists(source))) throw new Error(`Required Orchard build input is missing: ${source}`);
  await mkdir(path.dirname(destination), { recursive: true });
  await cp(source, destination, { recursive: true, dereference: true, force: true });
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

const STRIPPED_DEPENDENCY_DIRECTORIES = new Set([
  "test",
  "tests",
  "docs",
  "demo",
  "demos",
  "example",
  "examples"
]);

async function pruneProductionDependencyPayload(nodeModulesRoot: string): Promise<void> {
  const pending = [nodeModulesRoot];
  while (pending.length > 0) {
    const directory = pending.pop()!;
    for (const entry of await readdir(directory, { withFileTypes: true })) {
      const entryPath = path.join(directory, entry.name);
      if (entry.isDirectory()) {
        if (STRIPPED_DEPENDENCY_DIRECTORIES.has(entry.name)) {
          await rm(entryPath, { recursive: true, force: true });
        } else {
          pending.push(entryPath);
        }
      } else if (entry.isFile() && /\.map$/i.test(entry.name)) {
        await rm(entryPath, { force: true });
      }
    }
  }
}

async function assertSharedIsPlatformNeutral(): Promise<void> {
  for (const filePath of await walk(sharedRoot)) {
    const targets = await binaryTargets(filePath);
    if (targets.length > 0) {
      throw new Error(`Platform binary leaked into the common archive: ${path.relative(sharedRoot, filePath)}`);
    }
    if (/(?:\.node|\.dll|\.dylib|\.so(?:\.\d+)*|\.exe)$/i.test(filePath)) {
      throw new Error(`Unclassified native-looking file in common archive: ${path.relative(sharedRoot, filePath)}`);
    }
  }
}

async function validateComposedInstall(directory: string, target: string): Promise<void> {
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
  for (const relativePath of required) {
    if (!(await exists(path.join(directory, relativePath)))) {
      throw new Error(`Composed ${target} installation is missing ${relativePath}.`);
    }
  }
  if (target === "darwin-x64") {
    for (const relativePath of [
      "node_modules/onnxruntime-web/package.json",
      "node_modules/onnxruntime-web/dist/ort.wasm.bundle.min.mjs",
      "node_modules/onnxruntime-web/dist/ort-wasm-simd-threaded.mjs",
      "node_modules/onnxruntime-web/dist/ort-wasm-simd-threaded.wasm"
    ]) {
      if (!(await exists(path.join(directory, relativePath)))) {
        throw new Error(`Composed ${target} installation is missing ${relativePath}.`);
      }
    }
  }
}

try {
  await rm(outputRoot, { recursive: true, force: true });
  await mkdir(outputRoot, { recursive: true });
  await mkdir(sharedRoot, { recursive: true });

  console.log("Building Orchard application files and host native addons…");
  await runCommand("npm", ["run", "build"], { cwd: projectRoot });
  const nativeInputs = process.env.ORCHARD_NATIVE_INPUTS
    ? path.resolve(process.env.ORCHARD_NATIVE_INPUTS)
    : "";
  if (nativeInputs && await exists(nativeInputs)) {
    console.log("Merging native inputs from CI…");
    for (const input of await walk(nativeInputs)) {
      const relative = path.relative(nativeInputs, input);
      if (relative.startsWith("..") || path.isAbsolute(relative)) {
        throw new Error(`Native input escaped its root: ${input}`);
      }
      await copyRequired(input, path.join(projectRoot, relative));
    }
  }

  console.log("Staging platform-neutral Orchard files…");
  await Promise.all([
    copyRequired(path.join(projectRoot, "dist", "index.html"), path.join(sharedRoot, "dist", "index.html")),
    copyRequired(path.join(projectRoot, "dist", "welcome.html"), path.join(sharedRoot, "dist", "welcome.html")),
    copyRequired(path.join(projectRoot, "dist", "assets"), path.join(sharedRoot, "dist", "assets")),
    copyRequired(path.join(projectRoot, "dist", "favicon.png"), path.join(sharedRoot, "dist", "favicon.png")),
    copyRequired(path.join(projectRoot, "dist", "orchard-logo.png"), path.join(sharedRoot, "dist", "orchard-logo.png")),
    copyRequired(path.join(projectRoot, "electron"), path.join(sharedRoot, "electron")),
    copyRequired(path.join(projectRoot, "shared"), path.join(sharedRoot, "shared")),
    copyRequired(
      path.join(projectRoot, "models", "beat-this", "beat_this_int8.onnx"),
      path.join(sharedRoot, "models", "beat-this", "beat_this_int8.onnx")
    ),
    copyRequired(
      path.join(projectRoot, "models", "vocal-separation", "vocals_umxhq_int8.onnx"),
      path.join(sharedRoot, "models", "vocal-separation", "vocals_umxhq_int8.onnx")
    ),
    copyRequired(
      path.join(projectRoot, "native-media", "index.cjs"),
      path.join(sharedRoot, "native-media", "index.cjs")
    ),
    copyRequired(
      path.join(projectRoot, "native-audio-rust", "index.cjs"),
      path.join(sharedRoot, "native-audio-rust", "index.cjs")
    ),
    copyRequired(path.join(projectRoot, "LICENSE"), path.join(sharedRoot, "LICENSE")),
    copyRequired(path.join(projectRoot, "build", "icon.png"), path.join(sharedRoot, "resources", "icon.png"))
  ]);

  const sourcePackage = JSON.parse(await readFile(path.join(projectRoot, "package.json"), "utf8")) as Record<string, unknown>;
  const electronVersion = String((sourcePackage.devDependencies as Record<string, unknown> | undefined)?.electron ?? "");
  if (!VERSION_PATTERN.test(electronVersion)) {
    throw new Error("package.json devDependencies.electron must be an exact version.");
  }
  sourcePackage.version = packageVersion;
  await writeFile(path.join(sharedRoot, "package.json"), `${JSON.stringify(sourcePackage, null, 2)}\n`);
  await writeFile(
    path.join(sharedRoot, ".orchard-package.json"),
    `${JSON.stringify({ schemaVersion: 1, version: packageVersion }, null, 2)}\n`
  );

  console.log("Installing a clean production dependency tree…");
  const dependencyRoot = path.join(workRoot, "dependencies");
  await mkdir(dependencyRoot, { recursive: true });
  await Promise.all([
    cp(path.join(projectRoot, "package.json"), path.join(dependencyRoot, "package.json")),
    cp(path.join(projectRoot, "package-lock.json"), path.join(dependencyRoot, "package-lock.json"))
  ]);
  await runCommand("npm", ["ci", "--omit=dev", "--ignore-scripts", "--no-audit", "--no-fund"], {
    cwd: dependencyRoot
  });
  await cp(path.join(dependencyRoot, "node_modules"), path.join(sharedRoot, "node_modules"), {
    recursive: true,
    dereference: true,
    force: true
  });
  await pruneProductionDependencyPayload(path.join(sharedRoot, "node_modules"));
  await rm(path.join(sharedRoot, "node_modules", ".bin"), { recursive: true, force: true });

  console.log("Separating and validating native assets…");
  const native = await collectNativeAssets({ projectRoot, sharedRoot, overlaysRoot, electronVersion });
  if (native.completeTargets.length === 0) {
    throw new Error("No target has a complete Orchard native addon and ONNX runtime set.");
  }
  for (const target of native.completeTargets) {
    const marker = path.join(native.overlay(target), ".orchard-native", `${target}.json`);
    await writeFile(marker, `${JSON.stringify({ schemaVersion: 1, version: packageVersion, target }, null, 2)}\n`);
  }
  await assertSharedIsPlatformNeutral();

  const sharedName = `orchard-${packageVersion}.tar.zst`;
  const sharedArchive = path.join(outputRoot, sharedName);
  console.log(`Compressing ${sharedName}…`);
  await createTarZst(sharedRoot, sharedArchive);

  const nativeArchives = new Map<string, string>();
  for (const target of native.completeTargets) {
    const name = `orchard-native-${target}-${packageVersion}.tar.zst`;
    const archive = path.join(outputRoot, name);
    console.log(`Compressing ${name}…`);
    await createTarZst(native.overlay(target), archive);
    nativeArchives.set(target, archive);
  }

  console.log("Composing each split package pair for verification…");
  for (const [target, archive] of nativeArchives) {
    const verificationRoot = path.join(workRoot, "verify", target);
    await mkdir(verificationRoot, { recursive: true });
    await extractTarZst(sharedArchive, verificationRoot);
    await extractTarZst(archive, verificationRoot);
    await validateComposedInstall(verificationRoot, target);
  }

  const sharedMetadata = await fileMetadata(sharedArchive);
  const nativeManifest: Record<string, { url: string; size: number; sha256: string }> = {};
  for (const [target, archive] of nativeArchives) {
    const metadata = await fileMetadata(archive);
    nativeManifest[target] = { url: path.basename(archive), ...metadata };
  }

  const manifest = {
    schemaVersion: 1,
    releases: [{
      version: packageVersion,
      channel: packageVersion.includes("-") ? "beta" : "stable",
      electronVersion,
      shared: { url: sharedName, ...sharedMetadata },
      native: nativeManifest
    }]
  };
  await writeFile(path.join(outputRoot, "manifest.json"), `${JSON.stringify(manifest, null, 2)}\n`);

  const expectedFiles = [
    "manifest.json",
    sharedName,
    ...[...nativeArchives.values()].map((archive) => path.basename(archive))
  ].sort();
  const actualFiles = (await readdir(outputRoot)).sort();
  if (JSON.stringify(actualFiles) !== JSON.stringify(expectedFiles)) {
    throw new Error(`Unexpected files in ${outputRoot}: ${actualFiles.join(", ")}`);
  }

  console.log("\nUpload exactly these files:");
  for (const file of expectedFiles) console.log(path.join(outputRoot, file));
} finally {
  await rm(workRoot, { recursive: true, force: true }).catch(() => undefined);
}

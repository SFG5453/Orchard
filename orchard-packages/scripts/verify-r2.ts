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

import { access, mkdtemp, readFile, readdir, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { installRelease } from "../src/backend/installer.ts";
import { detectTarget } from "../src/backend/target.ts";

const managerRoot = path.resolve(import.meta.dir, "..");
const repositoryRoot = path.resolve(managerRoot, "..");
const r2Root = path.resolve(process.argv[2] || path.join(repositoryRoot, "artifacts", "r2"));
const verificationRoot = await mkdtemp(path.join(tmpdir(), "orchard-r2-install-"));
const originalFetch = globalThis.fetch;
const previousConfigHome = process.env.XDG_CONFIG_HOME;
const previousCacheHome = process.env.XDG_CACHE_HOME;

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
      else files.push(entryPath);
    }
  }
  return files;
}

try {
  const manifestPath = path.join(r2Root, "manifest.json");
  const manifest = JSON.parse(await readFile(manifestPath, "utf8")) as {
    releases: Array<{ version: string; native: Record<string, unknown> }>;
  };
  const release = manifest.releases[0];
  if (!release) throw new Error("R2 manifest has no release to verify.");
  const target = detectTarget();
  if (!release.native[target]) throw new Error(`R2 manifest has no ${target} package.`);

  process.env.XDG_CONFIG_HOME = path.join(verificationRoot, "config");
  process.env.XDG_CACHE_HOME = path.join(verificationRoot, "cache");
  globalThis.fetch = (async (input: string | URL | Request): Promise<Response> => {
    const requestUrl = new URL(input instanceof Request ? input.url : input.toString());
    const localPath = path.join(r2Root, path.posix.basename(requestUrl.pathname));
    if (!(await exists(localPath))) return new Response("Not found", { status: 404 });
    const file = Bun.file(localPath);
    return new Response(file, {
      status: 200,
      headers: {
        "Content-Length": String(file.size),
        "Content-Type": localPath.endsWith(".json") ? "application/json" : "application/octet-stream"
      }
    });
  }) as typeof fetch;

  const phases = new Set<string>();
  const result = await installRelease(release.version, (progress) => phases.add(progress.phase));
  const requiredPhases = [
    "manifest", "download-common", "download-native", "verify-shared", "verify-native",
    "staging", "extract-shared", "extract-native", "validate", "activate", "cleanup"
  ];
  for (const phase of requiredPhases) {
    if (!phases.has(phase)) throw new Error(`Installer did not report required phase ${phase}.`);
  }
  if (!(await exists(path.join(result.installPath, ".orchard-package.json")))) {
    throw new Error("Installer did not activate the composed Orchard installation.");
  }

  const leftovers = (await walk(verificationRoot)).filter((candidate) => {
    const name = path.basename(candidate);
    return name.endsWith(".part") || name.includes(".staging-") || name.includes(".backup-");
  });
  if (leftovers.length > 0) throw new Error(`Installer left temporary files: ${leftovers.join(", ")}`);

  console.log(`Verified ${release.version} ${target} install at ${result.installPath}`);
} finally {
  globalThis.fetch = originalFetch;
  if (previousConfigHome === undefined) delete process.env.XDG_CONFIG_HOME;
  else process.env.XDG_CONFIG_HOME = previousConfigHome;
  if (previousCacheHome === undefined) delete process.env.XDG_CACHE_HOME;
  else process.env.XDG_CACHE_HOME = previousCacheHome;
  await rm(verificationRoot, { recursive: true, force: true });
}

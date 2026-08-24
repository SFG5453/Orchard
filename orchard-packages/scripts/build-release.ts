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

import { cp, mkdir, readFile, rm, chmod } from "node:fs/promises";
import { spawn } from "node:child_process";
import path from "node:path";
import { detectTarget, isTarget, TARGETS, type Target } from "../src/backend/target.ts";

const projectRoot = path.resolve(import.meta.dir, "..");
const supportedShellTargets: Target[] = [...TARGETS];
const shellNames: Record<Target, string> = {
  "linux-x64": "orchard-packages-linux_x64",
  "linux-arm64": "orchard-packages-linux_arm64",
  "win32-x64": "orchard-packages-win_x64.exe",
  // Neutralino has no Windows ARM64 shell. Windows runs its x64 shell under
  // emulation while the native ARM64 Go backend selects ARM64 Orchard assets.
  "win32-arm64": "orchard-packages-win_x64.exe",
  "darwin-x64": "orchard-packages-mac_x64",
  "darwin-arm64": "orchard-packages-mac_arm64"
};

function run(command: string, args: string[]): Promise<void> {
  return new Promise((resolve, reject) => {
    const child = spawn(command, args, { cwd: projectRoot, stdio: "inherit", windowsHide: true });
    child.on("error", reject);
    child.on("close", (code) => code === 0 ? resolve() : reject(new Error(`${command} exited with ${code}.`)));
  });
}

const requested = process.argv[2] ?? "host";
let targets: Target[];
if (requested === "all") targets = supportedShellTargets;
else {
  const target = requested === "host" ? detectTarget() : requested;
  if (!isTarget(target)) throw new Error(`Unknown release target: ${target}`);
  targets = [target];
}

await run("bun", ["run", "build:frontend"]);
await run("bun", ["run", "scripts/build-backend.ts", "host", "--extension"]);
await run("bunx", ["neu", "update"]);
await run("bunx", ["neu", "build"]);

const packageJson = JSON.parse(await readFile(path.join(projectRoot, "package.json"), "utf8")) as { version: string };
const neutralinoOutput = path.join(projectRoot, "dist", "neutralino", "orchard-packages");
const releasesRoot = path.join(projectRoot, "dist", "releases");

for (const target of targets) {
  await run("bun", ["run", "scripts/build-backend.ts", target]);
  const windows = target.startsWith("win32-");
  const bundleRoot = path.join(releasesRoot, `orchard-packages-${packageJson.version}-${target}`);
  const extensionRoot = path.join(bundleRoot, "extensions");
  await rm(bundleRoot, { recursive: true, force: true });
  await mkdir(extensionRoot, { recursive: true });

  const appName = windows ? "orchard-packages.exe" : "orchard-packages";
  const backendName = windows ? "orchard-packages-backend.exe" : "orchard-packages-backend";
  await Promise.all([
    cp(path.join(neutralinoOutput, shellNames[target]), path.join(bundleRoot, appName)),
    cp(path.join(neutralinoOutput, "resources.neu"), path.join(bundleRoot, "resources.neu")),
    cp(
      path.join(projectRoot, "dist", "backend", target, backendName),
      path.join(extensionRoot, backendName)
    )
  ]);
  if (!windows) {
    await chmod(path.join(bundleRoot, appName), 0o755);
    await chmod(path.join(extensionRoot, backendName), 0o755);
  }
  console.log(`${target}: ${bundleRoot}`);
}

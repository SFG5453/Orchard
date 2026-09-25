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

import { chmod, mkdir, rm } from "node:fs/promises";
import { spawn } from "node:child_process";
import path from "node:path";
import { detectTarget, isTarget, TARGETS, type Target } from "../src/backend/target.ts";

const projectRoot = path.resolve(import.meta.dir, "..");
const requested = process.argv.slice(2).filter((argument) => !argument.startsWith("--"));
const extensionMode = process.argv.includes("--extension");

const targetMap: Record<Target, { os: string; architecture: string }> = {
  "linux-x64": { os: "linux", architecture: "amd64" },
  "linux-arm64": { os: "linux", architecture: "arm64" },
  "win32-x64": { os: "windows", architecture: "amd64" },
  "win32-arm64": { os: "windows", architecture: "arm64" },
  "darwin-x64": { os: "darwin", architecture: "amd64" },
  "darwin-arm64": { os: "darwin", architecture: "arm64" }
};

function runGo(target: Target, outputPath: string): Promise<void> {
  const buildTarget = targetMap[target];
  return new Promise((resolve, reject) => {
    const child = spawn("go", [
      "build",
      "-trimpath",
      "-buildvcs=false",
      "-ldflags=-s -w -buildid=",
      "-o",
      outputPath,
      "./backend"
    ], {
      cwd: projectRoot,
      env: {
        ...process.env,
        CGO_ENABLED: "0",
        GOOS: buildTarget.os,
        GOARCH: buildTarget.architecture
      },
      stdio: "inherit",
      windowsHide: true
    });
    child.on("error", reject);
    child.on("close", (code) => code === 0
      ? resolve()
      : reject(new Error(`Go backend build exited with ${code}.`)));
  });
}

function selectedTargets(): Target[] {
  const selection = requested[0] ?? "host";
  if (selection === "all") return [...TARGETS];
  if (selection === "host") return [detectTarget()];
  if (!isTarget(selection)) throw new Error(`Unknown backend target: ${selection}`);
  return [selection];
}

for (const target of selectedTargets()) {
  const windows = target.startsWith("win32-");
  const outputDirectory = extensionMode
    ? path.join(projectRoot, "extensions")
    : path.join(projectRoot, "dist", "backend", target);
  const outputPath = path.join(outputDirectory, `orchard-packages-backend${windows ? ".exe" : ""}`);
  await mkdir(outputDirectory, { recursive: true });
  await rm(outputPath, { force: true });

  await runGo(target, outputPath);
  if (!windows) await chmod(outputPath, 0o755);
  console.log(`${target}: ${outputPath}`);
}

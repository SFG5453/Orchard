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

import { createHash } from "node:crypto";
import { createReadStream } from "node:fs";
import { mkdir, stat } from "node:fs/promises";
import { spawn } from "node:child_process";
import path from "node:path";

export function runCommand(
  command: string,
  args: string[],
  options: { cwd?: string; env?: NodeJS.ProcessEnv; capture?: boolean } = {}
): Promise<string> {
  return new Promise((resolve, reject) => {
    const capture = options.capture ?? false;
    const child = spawn(command, args, {
      cwd: options.cwd,
      env: options.env,
      stdio: capture ? ["ignore", "pipe", "pipe"] : "inherit",
      windowsHide: true
    });
    const stdout: Buffer[] = [];
    const stderr: Buffer[] = [];
    child.stdout?.on("data", (chunk: Buffer) => stdout.push(chunk));
    child.stderr?.on("data", (chunk: Buffer) => stderr.push(chunk));
    child.on("error", reject);
    child.on("close", (code) => {
      if (code === 0) resolve(Buffer.concat(stdout).toString("utf8"));
      else reject(new Error(
        `${command} exited with ${code}${stderr.length ? `: ${Buffer.concat(stderr).toString("utf8").trim()}` : ""}`
      ));
    });
  });
}

export async function createTarZst(sourceDirectory: string, outputPath: string): Promise<void> {
  await mkdir(path.dirname(outputPath), { recursive: true });
  await runCommand("tar", [
    "--sort=name",
    "--mtime=@0",
    "--owner=0",
    "--group=0",
    "--numeric-owner",
    "--dereference",
    "--zstd",
    "-cf",
    outputPath,
    "-C",
    sourceDirectory,
    "."
  ], {
    env: { ...process.env, ZSTD_CLEVEL: "6", ZSTD_NBTHREADS: "0" }
  });
  await runCommand("tar", ["--zstd", "-tf", outputPath], { capture: true });
}

export async function fileMetadata(filePath: string): Promise<{ size: number; sha256: string }> {
  const hash = createHash("sha256");
  for await (const chunk of createReadStream(filePath)) hash.update(chunk);
  return { size: (await stat(filePath)).size, sha256: hash.digest("hex") };
}

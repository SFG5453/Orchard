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

import { spawn } from "node:child_process";
import { lstat, mkdir, readdir, realpath } from "node:fs/promises";
import path from "node:path";
import { detectTarget } from "./target.ts";

type CommandResult = { stdout: string; stderr: string; code: number };

function run(command: string, args: string[], capture = true): Promise<CommandResult> {
  return new Promise((resolve, reject) => {
    const child = spawn(command, args, {
      stdio: capture ? ["ignore", "pipe", "pipe"] : ["ignore", "ignore", "pipe"],
      windowsHide: true
    });
    const stdout: Buffer[] = [];
    const stderr: Buffer[] = [];
    let outputSize = 0;

    child.stdout?.on("data", (chunk: Buffer) => {
      outputSize += chunk.byteLength;
      if (outputSize > 32 * 1024 * 1024) {
        child.kill();
        reject(new Error("Archive listing exceeded the safety limit."));
        return;
      }
      stdout.push(chunk);
    });
    child.stderr?.on("data", (chunk: Buffer) => stderr.push(chunk));
    child.on("error", (error) => reject(new Error(`Could not start ${command}: ${error.message}`)));
    child.on("close", (code) => resolve({
      stdout: Buffer.concat(stdout).toString("utf8"),
      stderr: Buffer.concat(stderr).toString("utf8"),
      code: code ?? 1
    }));
  });
}

export function isSafeArchivePath(entry: string): boolean {
  const normalized = entry.replace(/^\.\//, "");
  if (!normalized || normalized === ".") return true;
  if (normalized.includes("\0") || normalized.includes("\\")) return false;
  if (path.posix.isAbsolute(normalized) || path.win32.isAbsolute(normalized)) return false;
  if (/^[a-z]:/i.test(normalized)) return false;
  return !normalized.split("/").some((part) => part === "..");
}

async function archiveArguments(archivePath: string, operation: "list" | "verbose"): Promise<{ zstd: boolean; output: string }> {
  const flag = operation === "list" ? "-tf" : "-tvf";
  const withZstd = await run("tar", ["--zstd", flag, archivePath]);
  if (withZstd.code === 0) return { zstd: true, output: withZstd.stdout };

  const automatic = await run("tar", [flag, archivePath]);
  if (automatic.code === 0) return { zstd: false, output: automatic.stdout };
  const message = automatic.stderr.trim() || withZstd.stderr.trim() || "unknown tar error";
  throw new Error(`Could not inspect the package archive: ${message}`);
}

async function validateExtractedLinks(root: string): Promise<void> {
  const canonicalRoot = `${await realpath(root)}${path.sep}`;
  const pending = [root];

  while (pending.length > 0) {
    const directory = pending.pop()!;
    for (const entry of await readdir(directory, { withFileTypes: true })) {
      const entryPath = path.join(directory, entry.name);
      const info = await lstat(entryPath);
      if (info.isSymbolicLink()) {
        const destination = await realpath(entryPath);
        if (destination !== canonicalRoot.slice(0, -1) && !destination.startsWith(canonicalRoot)) {
          throw new Error(`Archive symlink escapes the installation root: ${entry.name}`);
        }
      } else if (info.isDirectory()) {
        pending.push(entryPath);
      }
    }
  }
}

export async function extractTarZst(archivePath: string, destination: string): Promise<void> {
  const listing = await archiveArguments(archivePath, "list");
  for (const entry of listing.output.split(/\r?\n/)) {
    if (entry && !isSafeArchivePath(entry)) throw new Error(`Unsafe path in package archive: ${entry}`);
  }

  const verbose = await archiveArguments(archivePath, "verbose");
  for (const line of verbose.output.split(/\r?\n/)) {
    if (line.startsWith("l") || line.startsWith("h")) {
      throw new Error("Package archives may not contain symbolic or hard links.");
    }
  }

  await mkdir(destination, { recursive: true });
  const args = [...(listing.zstd ? ["--zstd"] : []), "-xf", archivePath, "-C", destination];
  if (!detectTarget().startsWith("win32-")) args.push("--no-same-owner", "--no-same-permissions");
  const extracted = await run("tar", args, false);
  if (extracted.code !== 0) {
    throw new Error(`Package extraction failed: ${extracted.stderr.trim() || `tar exited with ${extracted.code}`}`);
  }
  await validateExtractedLinks(destination);
}

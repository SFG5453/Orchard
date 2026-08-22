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

import { afterEach, describe, expect, test } from "bun:test";
import { mkdtemp, readFile, readdir, rm, writeFile, mkdir } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { downloadFile } from "../src/backend/downloader.ts";
import { extractTarZst, isSafeArchivePath } from "../src/backend/extractor.ts";

const temporaryDirectories: string[] = [];

async function temporaryDirectory(): Promise<string> {
  const directory = await mkdtemp(path.join(tmpdir(), "orchard-packages-test-"));
  temporaryDirectories.push(directory);
  return directory;
}

afterEach(async () => {
  await Promise.all(temporaryDirectories.splice(0).map((directory) => rm(directory, { recursive: true, force: true })));
});

describe("streamed downloads", () => {
  test("writes through a part file and enforces manifest size", async () => {
    const payload = new Uint8Array(128 * 1024).fill(42);
    const server = Bun.serve({ port: 0, fetch: () => new Response(payload) });
    const directory = await temporaryDirectory();
    const destination = path.join(directory, "archive.tar.zst");
    const progress: number[] = [];

    try {
      await downloadFile({
        url: server.url.toString(),
        destination,
        expectedSize: payload.byteLength,
        onProgress: ({ downloaded }) => progress.push(downloaded)
      });
      expect((await readFile(destination)).byteLength).toBe(payload.byteLength);
      expect(progress.at(-1)).toBe(payload.byteLength);
      expect(await readdir(directory)).toEqual(["archive.tar.zst"]);
    } finally {
      server.stop(true);
    }
  });

  test("removes a part file when size validation fails", async () => {
    const server = Bun.serve({ port: 0, fetch: () => new Response("short") });
    const directory = await temporaryDirectory();
    const destination = path.join(directory, "archive.tar.zst");
    try {
      await expect(downloadFile({ url: server.url.toString(), destination, expectedSize: 99 })).rejects.toThrow();
      expect(await readdir(directory)).toEqual([]);
    } finally {
      server.stop(true);
    }
  });
});

describe("archive extraction", () => {
  test("rejects unsafe archive paths", () => {
    expect(isSafeArchivePath("dist/index.html")).toBe(true);
    expect(isSafeArchivePath("./node_modules/package/index.js")).toBe(true);
    expect(isSafeArchivePath("../outside")).toBe(false);
    expect(isSafeArchivePath("/etc/passwd")).toBe(false);
    expect(isSafeArchivePath("C:\\Windows\\file")).toBe(false);
  });

  test("extracts a real tar.zst archive", async () => {
    const directory = await temporaryDirectory();
    const source = path.join(directory, "source");
    const output = path.join(directory, "output");
    const archive = path.join(directory, "sample.tar.zst");
    await mkdir(path.join(source, "dist"), { recursive: true });
    await writeFile(path.join(source, "dist", "index.html"), "orchard");

    const command = Bun.spawn(["tar", "--zstd", "-cf", archive, "-C", source, "."], { stderr: "pipe" });
    expect(await command.exited).toBe(0);
    await extractTarZst(archive, output);
    expect(await readFile(path.join(output, "dist", "index.html"), "utf8")).toBe("orchard");
  });
});

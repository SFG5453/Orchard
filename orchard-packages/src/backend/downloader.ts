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

import { createWriteStream } from "node:fs";
import { rename, rm, stat } from "node:fs/promises";
import { Readable, Transform } from "node:stream";
import { pipeline } from "node:stream/promises";

export type DownloadProgress = {
  downloaded: number;
  expected: number;
};

export async function downloadFile(options: {
  url: string;
  destination: string;
  expectedSize: number;
  signal?: AbortSignal;
  onProgress?: (progress: DownloadProgress) => void;
}): Promise<void> {
  const partPath = `${options.destination}.part`;
  await rm(partPath, { force: true });

  let response: Response;
  try {
    response = await fetch(options.url, { redirect: "follow", signal: options.signal });
  } catch (error) {
    throw new Error(`Download failed: ${error instanceof Error ? error.message : String(error)}`);
  }

  if (!response.ok || !response.body) {
    throw new Error(`Download failed with HTTP ${response.status}: ${new URL(options.url).pathname.split("/").at(-1)}`);
  }

  let downloaded = 0;
  const meter = new Transform({
    transform(chunk: Buffer, _encoding, callback) {
      downloaded += chunk.byteLength;
      options.onProgress?.({ downloaded, expected: options.expectedSize });
      callback(null, chunk);
    }
  });

  try {
    await pipeline(
      Readable.fromWeb(response.body as never),
      meter,
      createWriteStream(partPath, { flags: "wx" }),
      { signal: options.signal }
    );
    const result = await stat(partPath);
    if (result.size !== options.expectedSize) {
      throw new Error(`Downloaded ${result.size} bytes, but the manifest requires ${options.expectedSize}.`);
    }
    await rename(partPath, options.destination);
  } catch (error) {
    await rm(partPath, { force: true }).catch(() => undefined);
    throw error;
  }
}

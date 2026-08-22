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

import { open } from "node:fs/promises";
import type { Target } from "../src/backend/target.ts";

function architectureTarget(platform: "linux" | "win32" | "darwin", machine: number): Target | undefined {
  if (machine === 0x3e || machine === 0x8664 || machine === 0x01000007) return `${platform}-x64`;
  if (machine === 0xb7 || machine === 0xaa64 || machine === 0x0100000c) return `${platform}-arm64`;
  return undefined;
}

export async function binaryTargets(filePath: string): Promise<Target[]> {
  const handle = await open(filePath, "r");
  try {
    const buffer = Buffer.alloc(4096);
    const { bytesRead } = await handle.read(buffer, 0, buffer.length, 0);
    const data = buffer.subarray(0, bytesRead);
    if (data.length < 20) return [];

    if (data[0] === 0x7f && data.subarray(1, 4).toString("ascii") === "ELF") {
      const littleEndian = data[5] === 1;
      const machine = littleEndian ? data.readUInt16LE(18) : data.readUInt16BE(18);
      const target = architectureTarget("linux", machine);
      return target ? [target] : [];
    }

    if (data[0] === 0x4d && data[1] === 0x5a && data.length >= 64) {
      const headerOffset = data.readUInt32LE(60);
      if (headerOffset + 6 <= data.length && data.subarray(headerOffset, headerOffset + 4).toString("binary") === "PE\0\0") {
        const target = architectureTarget("win32", data.readUInt16LE(headerOffset + 4));
        return target ? [target] : [];
      }
    }

    const littleMagic = data.readUInt32LE(0);
    const bigMagic = data.readUInt32BE(0);
    if (littleMagic === 0xfeedfacf) {
      const target = architectureTarget("darwin", data.readUInt32LE(4));
      return target ? [target] : [];
    }
    if (bigMagic === 0xfeedfacf) {
      const target = architectureTarget("darwin", data.readUInt32BE(4));
      return target ? [target] : [];
    }
    if (bigMagic === 0xcafebabe || bigMagic === 0xcafebabf) {
      const count = data.readUInt32BE(4);
      const stride = bigMagic === 0xcafebabf ? 32 : 20;
      const targets = new Set<Target>();
      for (let index = 0; index < count; index += 1) {
        const offset = 8 + index * stride;
        if (offset + 4 > data.length) break;
        const target = architectureTarget("darwin", data.readUInt32BE(offset));
        if (target) targets.add(target);
      }
      return [...targets];
    }

    return [];
  } finally {
    await handle.close();
  }
}

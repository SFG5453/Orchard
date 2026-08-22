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

import { describe, expect, test } from "bun:test";
import { PACKAGE_BASE_URL, parseManifest, resolveAssetUrl } from "../src/backend/manifest.ts";

const checksum = "a".repeat(64);

function validManifest(): unknown {
  return {
    schemaVersion: 1,
    releases: [{
      version: "5.0.0",
      channel: "stable",
      electronVersion: "43.4.1",
      shared: { url: "orchard-5.0.0.tar.zst", size: 100, sha256: checksum },
      native: {
        "linux-x64": { url: "orchard-native-linux-x64-5.0.0.tar.zst", size: 50, sha256: checksum }
      }
    }]
  };
}

describe("manifest parsing", () => {
  test("accepts a strict split-package manifest", () => {
    const parsed = parseManifest(validManifest());
    expect(parsed.releases[0]?.native["linux-x64"]?.size).toBe(50);
    expect(resolveAssetUrl(parsed.releases[0]!.shared)).toBe(`${PACKAGE_BASE_URL}orchard-5.0.0.tar.zst`);
  });

  test("rejects placeholders and external URLs", () => {
    const placeholder = validManifest() as any;
    placeholder.releases[0].shared.size = 0;
    expect(() => parseManifest(placeholder)).toThrow("invalid byte size");

    const external = validManifest() as any;
    external.releases[0].shared.url = "https://example.com/archive.tar.zst";
    expect(() => parseManifest(external)).toThrow("relative");
  });

  test("rejects traversal and unknown targets", () => {
    const traversal = validManifest() as any;
    traversal.releases[0].shared.url = "../archive.tar.zst";
    expect(() => parseManifest(traversal)).toThrow("unsafe relative URL");

    const target = validManifest() as any;
    target.releases[0].native["linux-ia32"] = target.releases[0].native["linux-x64"];
    expect(() => parseManifest(target)).toThrow("unknown target");
  });
});

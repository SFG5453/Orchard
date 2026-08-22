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
import path from "node:path";
import { getInstallPaths } from "../src/backend/paths.ts";
import { detectTarget, normalizeTarget } from "../src/backend/target.ts";

describe("target normalization", () => {
  test("normalizes supported aliases", () => {
    expect(normalizeTarget("linux", "x86_64")).toBe("linux-x64");
    expect(normalizeTarget("darwin", "aarch64")).toBe("darwin-arm64");
  });

  test("detects Windows ARM through the native OS environment", () => {
    expect(detectTarget("win32", "x64", { PROCESSOR_ARCHITEW6432: "ARM64" })).toBe("win32-arm64");
  });

  test("rejects unsupported systems", () => {
    expect(() => normalizeTarget("freebsd", "x64")).toThrow("Unsupported operating system");
    expect(() => normalizeTarget("linux", "ia32")).toThrow("Unsupported processor architecture");
  });
});

describe("installation paths", () => {
  test("uses XDG locations on Linux", () => {
    expect(getInstallPaths("linux-x64", "5.9.2", "43.4.1", {
      XDG_CONFIG_HOME: "/config",
      XDG_CACHE_HOME: "/cache"
    }, "/home/test")).toEqual({
      installDirectory: path.join("/config", "orchard", "versions", "5.0.0"),
      runtimeDirectory: path.join("/config", "orchard", "runtimes", "electron", "43.4.1", "linux-x64"),
      cacheDirectory: path.join("/cache", "orchard-packages")
    });
  });

  test("uses per-user locations on macOS and Windows", () => {
    expect(getInstallPaths("darwin-arm64", "6.1.0", "43.4.1", {}, "/Users/test").installDirectory)
      .toBe(path.join("/Users/test", "Library", "Application Support", "orchard", "versions", "6.0.0"));
    expect(getInstallPaths("win32-x64", "5.0.0", "43.4.1", { APPDATA: "C:\\Users\\test\\Roaming" }, "C:\\Users\\test").installDirectory)
      .toBe(path.join("C:\\Users\\test\\Roaming", "orchard", "versions", "5.0.0"));
  });
});

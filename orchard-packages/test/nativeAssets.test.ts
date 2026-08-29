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
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
 * A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Orchard. If not, see <https://www.gnu.org/licenses/>.
 */

import { describe, expect, test } from "bun:test";
import { launcherContents } from "../scripts/native-assets.ts";

describe("native package launchers", () => {
  test("terminates the Windows Electron app path after a dot", () => {
    expect(launcherContents("win32-x64", "43.4.1")).toBe(
      "@echo off\r\n" +
      "\"%~dp0..\\..\\runtimes\\electron\\43.4.1\\win32-x64\\electron.exe\" \"%~dp0.\" %*\r\n"
    );
  });
});

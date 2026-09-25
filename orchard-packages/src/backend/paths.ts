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

import { homedir } from "node:os";
import path from "node:path";
import type { Target } from "./target.ts";

export type InstallPaths = {
  installDirectory: string;
  runtimeDirectory: string;
  cacheDirectory: string;
};

export function getInstallPaths(
  target: Target,
  version: string,
  electronVersion: string,
  environment: NodeJS.ProcessEnv = process.env,
  homeDirectory = homedir()
): InstallPaths {
  const platform = target.split("-")[0];
  const majorVersion = `${version.split(".")[0]}.0.0`;

  if (platform === "win32") {
    const configRoot = environment.APPDATA || path.join(homeDirectory, "AppData", "Roaming");
    const localData = environment.LOCALAPPDATA || path.join(homeDirectory, "AppData", "Local");
    return {
      installDirectory: path.join(configRoot, "orchard", "versions", majorVersion),
      runtimeDirectory: path.join(configRoot, "orchard", "runtimes", "electron", electronVersion, target),
      cacheDirectory: path.join(localData, "Orchard Packages", "Cache")
    };
  }

  if (platform === "darwin") {
    const applicationSupport = path.join(homeDirectory, "Library", "Application Support");
    return {
      installDirectory: path.join(applicationSupport, "orchard", "versions", majorVersion),
      runtimeDirectory: path.join(applicationSupport, "orchard", "runtimes", "electron", electronVersion, target),
      cacheDirectory: path.join(homeDirectory, "Library", "Caches", "Orchard Packages")
    };
  }

  const configRoot = environment.XDG_CONFIG_HOME || path.join(homeDirectory, ".config");
  const cacheRoot = environment.XDG_CACHE_HOME || path.join(homeDirectory, ".cache");
  return {
    installDirectory: path.join(configRoot, "orchard", "versions", majorVersion),
    runtimeDirectory: path.join(configRoot, "orchard", "runtimes", "electron", electronVersion, target),
    cacheDirectory: path.join(cacheRoot, "orchard-packages")
  };
}

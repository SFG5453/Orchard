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

export const TARGETS = [
  "linux-x64",
  "linux-arm64",
  "win32-x64",
  "win32-arm64",
  "darwin-x64",
  "darwin-arm64"
] as const;

export type Target = (typeof TARGETS)[number];

type Platform = "linux" | "win32" | "darwin";
type Architecture = "x64" | "arm64";

export function isTarget(value: string): value is Target {
  return (TARGETS as readonly string[]).includes(value);
}

export function normalizeTarget(platform: string, architecture: string): Target {
  if (platform !== "linux" && platform !== "win32" && platform !== "darwin") {
    throw new Error(`Unsupported operating system: ${platform}`);
  }

  const normalizedArchitecture = architecture === "x86_64" || architecture === "amd64"
    ? "x64"
    : architecture === "aarch64"
      ? "arm64"
      : architecture;

  if (normalizedArchitecture !== "x64" && normalizedArchitecture !== "arm64") {
    throw new Error(`Unsupported processor architecture: ${architecture}`);
  }

  return `${platform as Platform}-${normalizedArchitecture as Architecture}`;
}

export function detectTarget(
  platform = process.platform,
  architecture = process.arch,
  environment: NodeJS.ProcessEnv = process.env
): Target {
  // An x64 process can run under emulation on Windows ARM. Prefer the native OS
  // architecture so Orchard receives the addon package its machine can run.
  const windowsArchitecture = platform === "win32"
    ? String(environment.PROCESSOR_ARCHITEW6432 || environment.PROCESSOR_ARCHITECTURE || "").toLowerCase()
    : "";
  const detectedArchitecture = windowsArchitecture.includes("arm64") ? "arm64" : architecture;
  return normalizeTarget(platform, detectedArchitecture);
}

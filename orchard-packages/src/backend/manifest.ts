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

import type { Target } from "./target.ts";

export const PACKAGE_BASE_URL = "https://packages.sfg545.dev/";
export const MANIFEST_URL = new URL("manifest.json", PACKAGE_BASE_URL).toString();

export type PackageAsset = {
  url: string;
  size: number;
  sha256: string;
};

export type Release = {
  version: string;
  channel: "stable" | "beta";
  electronVersion: string;
  shared: PackageAsset;
  native: Partial<Record<Target, PackageAsset>>;
};

export type PackageManifest = {
  schemaVersion: 1;
  releases: Release[];
};

const SHA256_PATTERN = /^[a-f0-9]{64}$/;
const VERSION_PATTERN = /^\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?$/;

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function parseAsset(value: unknown, label: string): PackageAsset {
  if (!isRecord(value)) throw new Error(`${label} is missing.`);

  const url = String(value.url ?? "");
  const size = Number(value.size);
  const sha256 = String(value.sha256 ?? "").toLowerCase();

  if (!url || url.startsWith("/") || url.includes("\\") || url.split("/").includes("..")) {
    throw new Error(`${label} has an unsafe relative URL.`);
  }
  if (/^[a-z][a-z\d+.-]*:/i.test(url)) {
    throw new Error(`${label} must use a URL relative to the package base URL.`);
  }
  if (!Number.isSafeInteger(size) || size <= 0) throw new Error(`${label} has an invalid byte size.`);
  if (!SHA256_PATTERN.test(sha256)) throw new Error(`${label} has an invalid SHA-256 checksum.`);

  return { url, size, sha256 };
}

export function parseManifest(value: unknown): PackageManifest {
  if (!isRecord(value) || value.schemaVersion !== 1 || !Array.isArray(value.releases)) {
    throw new Error("The package manifest is invalid or uses an unsupported schema.");
  }

  const releases = value.releases.map((candidate, index): Release => {
    if (!isRecord(candidate)) throw new Error(`Release ${index + 1} is invalid.`);
    const version = String(candidate.version ?? "");
    if (!VERSION_PATTERN.test(version)) throw new Error(`Release ${index + 1} has an invalid version.`);
    const electronVersion = String(candidate.electronVersion ?? "");
    if (!VERSION_PATTERN.test(electronVersion)) throw new Error(`Release ${version} has an invalid Electron version.`);
    if (candidate.channel !== "stable" && candidate.channel !== "beta") {
      throw new Error(`Release ${version} has an invalid channel.`);
    }
    if (!isRecord(candidate.native)) throw new Error(`Release ${version} has no native package map.`);

    const native: Release["native"] = {};
    for (const [target, asset] of Object.entries(candidate.native)) {
      if (![
        "linux-x64", "linux-arm64", "win32-x64", "win32-arm64", "darwin-x64", "darwin-arm64"
      ].includes(target)) {
        throw new Error(`Release ${version} contains unknown target ${target}.`);
      }
      native[target as Target] = parseAsset(asset, `Release ${version} native package ${target}`);
    }

    return {
      version,
      channel: candidate.channel,
      electronVersion,
      shared: parseAsset(candidate.shared, `Release ${version} common package`),
      native
    };
  });

  return { schemaVersion: 1, releases };
}

export async function fetchManifest(signal?: AbortSignal): Promise<PackageManifest> {
  let response: Response;
  try {
    response = await fetch(MANIFEST_URL, {
      headers: { Accept: "application/json" },
      redirect: "follow",
      signal
    });
  } catch (error) {
    throw new Error(`Could not reach the Orchard package service: ${error instanceof Error ? error.message : String(error)}`);
  }

  if (!response.ok) {
    throw new Error(`The Orchard package service returned HTTP ${response.status}.`);
  }

  try {
    return parseManifest(await response.json());
  } catch (error) {
    if (error instanceof Error) throw error;
    throw new Error("The package manifest could not be read.");
  }
}

export function resolveAssetUrl(asset: PackageAsset): string {
  const resolved = new URL(asset.url, PACKAGE_BASE_URL);
  const base = new URL(PACKAGE_BASE_URL);
  if (resolved.origin !== base.origin || !resolved.pathname.startsWith(base.pathname)) {
    throw new Error(`Package URL escapes the configured package base: ${asset.url}`);
  }
  return resolved.toString();
}

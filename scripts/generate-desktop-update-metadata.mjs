/*
 * Copyright (C) 2026 SFG545
 *
 * This file is part of Orchard.
 *
 * Orchard is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 */

import { createHash } from 'node:crypto';
import { createReadStream } from 'node:fs';
import { readdir, readFile, stat, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { pathToFileURL } from 'node:url';

function artifactIdentity(name) {
  const lower = name.toLowerCase();
  const arch = /(?:arm64|aarch64)/.test(lower) ? 'arm64' : 'x64';
  if (lower.endsWith('.appimage')) return { type: 'appimage', arch };
  if (lower.endsWith('.deb')) return { type: 'deb', arch };
  if (lower.endsWith('.flatpak')) return { type: 'flatpak', arch };
  if (lower.endsWith('.rpm')) return { type: 'rpm', arch };
  if (/\.pkg\.tar\.(?:zst|xz)$/.test(lower)) return { type: 'arch', arch };
  return null;
}

export async function generateDesktopUpdateMetadata({
  artifactsDirectory,
  baseUrl,
  channel,
  releaseNotes = '',
  releaseDate = new Date().toISOString(),
  version
}) {
  const normalizedBaseUrl = new URL(baseUrl);
  if (normalizedBaseUrl.protocol !== 'https:') throw new Error('Desktop artifact URLs must use HTTPS.');
  if (!normalizedBaseUrl.pathname.endsWith('/')) normalizedBaseUrl.pathname += '/';

  const names = (await readdir(artifactsDirectory)).sort();
  const assets = [];
  for (const name of names) {
    const identity = artifactIdentity(name);
    if (!identity) continue;
    const filePath = path.join(artifactsDirectory, name);
    const details = await stat(filePath);
    const hash = createHash('sha256');
    for await (const chunk of createReadStream(filePath)) hash.update(chunk);
    assets.push({
      ...identity,
      name,
      url: new URL(encodeURIComponent(name), normalizedBaseUrl).toString(),
      sha256: hash.digest('hex'),
      size: details.size
    });
  }

  if (!assets.length) throw new Error('No desktop package artifacts were found.');
  return JSON.stringify({
    schemaVersion: 1,
    version,
    channel: channel === 'beta' ? 'beta' : 'stable',
    releaseDate,
    releaseNotes: String(releaseNotes || '').split(/\r?\n/).map((line) => line.trim()).filter(Boolean),
    assets
  }, null, 2) + '\n';
}

async function main() {
  const [version, channel, baseUrl, artifactsDirectory, releaseNotesPath, outputPath] = process.argv.slice(2);
  if (!version || !channel || !baseUrl || !artifactsDirectory || !releaseNotesPath || !outputPath) {
    throw new Error('Usage: generate-desktop-update-metadata.mjs <version> <stable|beta> <base-url> <artifacts-dir> <release-notes> <output>');
  }

  const releaseNotes = await readFile(releaseNotesPath, 'utf8');
  const metadata = await generateDesktopUpdateMetadata({
    artifactsDirectory,
    baseUrl,
    channel,
    releaseNotes,
    version
  });
  await writeFile(outputPath, metadata);
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  await main();
}

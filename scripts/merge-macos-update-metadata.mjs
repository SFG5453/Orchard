import { readFile, writeFile } from 'node:fs/promises';
import { pathToFileURL } from 'node:url';

function metadataParts(source, label) {
  const version = source.match(/^version:\s*([^\r\n]+)$/m)?.[1]?.trim();
  const files = source.match(/^files:\r?\n([\s\S]*?)^path:/m)?.[1];

  if (!version || !files || !/^\s+- url:/m.test(files)) {
    throw new Error(`${label} is not valid electron-updater macOS metadata.`);
  }

  return { version, files };
}

export function mergeMacUpdateMetadata(x64Source, arm64Source) {
  const x64 = metadataParts(x64Source, 'x64 metadata');
  const arm64 = metadataParts(arm64Source, 'arm64 metadata');
  if (x64.version !== arm64.version) {
    throw new Error(`macOS metadata versions do not match (${x64.version} and ${arm64.version}).`);
  }

  return x64Source.replace(
    /^files:\r?\n[\s\S]*?^path:/m,
    `files:\n${arm64.files}${x64.files}path:`
  );
}

async function main() {
  const [x64Path, arm64Path, outputPath] = process.argv.slice(2);
  if (!x64Path || !arm64Path || !outputPath) {
    throw new Error('Usage: merge-macos-update-metadata.mjs <x64.yml> <arm64.yml> <output.yml>');
  }

  const [x64Source, arm64Source] = await Promise.all([
    readFile(x64Path, 'utf8'),
    readFile(arm64Path, 'utf8')
  ]);
  await writeFile(outputPath, mergeMacUpdateMetadata(x64Source, arm64Source));
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  await main();
}

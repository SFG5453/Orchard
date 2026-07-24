import assert from 'node:assert/strict';
import test from 'node:test';
import { mergeMacUpdateMetadata } from '../scripts/merge-macos-update-metadata.mjs';

function metadata(version, url, sha512) {
  return `version: ${version}\nfiles:\n  - url: ${url}\n    sha512: ${sha512}\n    size: 123\npath: ${url}\nsha512: ${sha512}\nreleaseNotes: |\n  Notes for ${version}\n`;
}

test('merges x64 and arm64 files into macOS update metadata', () => {
  const result = mergeMacUpdateMetadata(
    metadata('3.1.0', 'Orchard-3.1.0-mac-x64.zip', 'x64hash'),
    metadata('3.1.0', 'Orchard-3.1.0-mac-arm64.zip', 'arm64hash')
  );

  assert.match(result, /files:\n  - url: Orchard-3\.1\.0-mac-arm64\.zip/);
  assert.match(result, /  - url: Orchard-3\.1\.0-mac-x64\.zip/);
  assert.match(result, /path: Orchard-3\.1\.0-mac-x64\.zip/);
  assert.match(result, /releaseNotes: \|\n  Notes for 3\.1\.0/);
});

test('rejects mismatched macOS update versions', () => {
  assert.throws(
    () => mergeMacUpdateMetadata(
      metadata('3.1.0', 'Orchard-3.1.0-mac-x64.zip', 'x64hash'),
      metadata('3.2.1', 'Orchard-3.2.1-mac-arm64.zip', 'arm64hash')
    ),
    /versions do not match/
  );
});

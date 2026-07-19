import path from 'node:path';

const [x64Input, arm64Input, output] = process.argv.slice(2);

if (!x64Input || !arm64Input || !output) {
  throw new Error('Usage: merge-macos-apps.mjs <x64.app> <arm64.app> <output.app>');
}

// @electron/universal only guards on the host platform; its merge work is
// portable when an OSXCross-compatible `lipo` command is available on PATH.
Object.defineProperty(process, 'platform', { value: 'darwin' });
const { makeUniversalApp } = await import('@electron/universal');

await makeUniversalApp({
  x64AppPath: path.resolve(x64Input),
  arm64AppPath: path.resolve(arm64Input),
  outAppPath: path.resolve(output),
  force: true,
  mergeASARs: true
});

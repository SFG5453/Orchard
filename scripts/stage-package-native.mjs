import { copyFile, mkdir } from 'node:fs/promises';
import path from 'node:path';

const target = process.argv[2];
const outputRoot = path.resolve(process.argv[3] || 'native-inputs');
if (!/^(linux|win32|darwin)-(x64|arm64)$/.test(target || '')) {
  throw new Error('Usage: node scripts/stage-package-native.mjs <target> [output-directory]');
}

const files = [
  {
    source: 'native/build/Release/orchard_audio_analysis.node',
    destination: `native/build/Release/orchard_audio_analysis-${target}.node`
  },
  {
    source: `native-media/build/orchard-system-media-${target}.node`,
    destination: `native-media/build/orchard-system-media-${target}.node`
  },
  {
    source: `native-audio-rust/build/orchard-audio-transition-${target}.node`,
    destination: `native-audio-rust/build/orchard-audio-transition-${target}.node`
  }
];

for (const file of files) {
  const destination = path.join(outputRoot, file.destination);
  await mkdir(path.dirname(destination), { recursive: true });
  await copyFile(path.resolve(file.source), destination);
  console.log(`${target}: ${destination}`);
}

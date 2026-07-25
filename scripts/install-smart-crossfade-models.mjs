import { createHash } from 'node:crypto';
import { createWriteStream } from 'node:fs';
import { mkdir, readFile, rename, rm, stat, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { pipeline } from 'node:stream/promises';
import { Transform } from 'node:stream';

const ALL_IN_ONE_REVISION = '1299c10d828ff1a53c137ed7cb588c0e3852a340';
const ALL_IN_ONE_BASE_URL = `https://huggingface.co/zukky/allinone-DLL-ONNX/resolve/${ALL_IN_ONE_REVISION}`;
const HTDEMUCS_REVISION = 'd54ed9eb60e258ea82131c6ee14578628816456a';
const HTDEMUCS_BASE_URL = `https://huggingface.co/StemSplitio/htdemucs-onnx/resolve/${HTDEMUCS_REVISION}`;
const ASSETS = Object.freeze([
  {
    file: 'htdemucs.onnx',
    size: 165_612_636,
    sha256: 'd05c269d0178d2a72ad484b10b11dd370193fc923201c3b27a99f848745db70a',
    url: `${HTDEMUCS_BASE_URL}/htdemucs_fp16weights.onnx`
  },
  {
    file: 'harmonix-fold0.onnx',
    size: 3_469_940,
    sha256: 'e6cc7c465f38b60070e0532446c6dd74d6b57ee85f31f421ab8a3d8359706d00',
    url: `${ALL_IN_ONE_BASE_URL}/onnx/harmonix-fold0.onnx`
  },
  {
    file: 'harmonix-fold0.json',
    size: 444,
    sha256: '67892f6f0bd95fa7f49b840cecbdf43ec1bc6cedffa91abf0da69fa2cab6a34b',
    url: `${ALL_IN_ONE_BASE_URL}/onnx/harmonix-fold0.json`
  },
  {
    file: 'LICENSE-all-in-one',
    sha256: 'f75e7cd094466fd9a06b31eff35068d07f120fcd0c04e2893df75384ae1c4abf',
    url: 'https://raw.githubusercontent.com/mir-aidj/all-in-one/18e78903c0365147a2c5d4e5e57ebf88cb7d800e/LICENSE'
  },
  {
    file: 'LICENSE-demucs',
    sha256: 'cf9b17822d1fcd4ff32ccbe14183386fb3adf6f2ff92dc184130823f7fc28173',
    url: 'https://raw.githubusercontent.com/facebookresearch/demucs/e976d93ecc3865e5757426930257e200846a520a/LICENSE'
  }
]);

function parsedArguments(argv) {
  let directory = path.resolve('models/smart-crossfade');
  let force = false;
  for (let index = 0; index < argv.length; index += 1) {
    if (argv[index] === '--force') {
      force = true;
      continue;
    }
    if (argv[index] === '--dir' && argv[index + 1]) {
      directory = path.resolve(argv[index + 1]);
      index += 1;
      continue;
    }
    throw new Error(`Unknown argument: ${argv[index]}`);
  }
  return { directory, force };
}

async function sha256(filePath) {
  const contents = await readFile(filePath);
  return createHash('sha256').update(contents).digest('hex');
}

async function alreadyInstalled(filePath, asset) {
  try {
    const details = await stat(filePath);
    if (asset.size && details.size !== asset.size) return false;
    return await sha256(filePath) === asset.sha256;
  } catch {
    return false;
  }
}

async function download(asset, directory, force) {
  const destination = path.join(directory, asset.file);
  if (!force && await alreadyInstalled(destination, asset)) {
    process.stdout.write(`Using verified ${asset.file}\n`);
    return;
  }

  const temporary = `${destination}.partial`;
  await rm(temporary, { force: true });
  const response = await fetch(asset.url, { redirect: 'follow' });
  if (!response.ok || !response.body) {
    throw new Error(`Could not download ${asset.file}: HTTP ${response.status}`);
  }
  const digest = createHash('sha256');
  let received = 0;
  const verifier = new Transform({
    transform(chunk, _encoding, callback) {
      received += chunk.length;
      digest.update(chunk);
      callback(null, chunk);
    }
  });
  process.stdout.write(`Downloading ${asset.file}${asset.size ? ` (${Math.round(asset.size / 1_000_000)} MB)` : ''}\n`);
  try {
    await pipeline(response.body, verifier, createWriteStream(temporary, { flags: 'wx' }));
    const actualHash = digest.digest('hex');
    if ((asset.size && received !== asset.size) || actualHash !== asset.sha256) {
      throw new Error(`Integrity check failed for ${asset.file}.`);
    }
    await rm(destination, { force: true });
    await rename(temporary, destination);
  } catch (error) {
    await rm(temporary, { force: true });
    throw error;
  }
}

async function main() {
  const { directory, force } = parsedArguments(process.argv.slice(2));
  await mkdir(directory, { recursive: true });
  for (const asset of ASSETS) await download(asset, directory, force);
  const manifest = {
    schemaVersion: 1,
    id: 'all-in-one-htdemucs-fold0',
    version: `aio-${ALL_IN_ONE_REVISION.slice(0, 12)}-demucs-${HTDEMUCS_REVISION.slice(0, 12)}`,
    pipeline: 'all-in-one-htdemucs',
    demucs: {
      file: 'htdemucs.onnx',
      inputName: 'mix',
      outputName: 'stems'
    },
    structure: {
      file: 'harmonix-fold0.onnx',
      config: 'harmonix-fold0.json',
      inputName: 'spec'
    },
    sources: {
      allInOne: `https://huggingface.co/zukky/allinone-DLL-ONNX/tree/${ALL_IN_ONE_REVISION}`,
      htdemucs: `https://huggingface.co/StemSplitio/htdemucs-onnx/tree/${HTDEMUCS_REVISION}`
    },
    licenses: {
      allInOne: 'MIT',
      demucs: 'MIT'
    }
  };
  await writeFile(
    path.join(directory, 'manifest.json'),
    `${JSON.stringify(manifest, null, 2)}\n`,
    'utf8'
  );
  process.stdout.write(`Smart Crossfade model pack is ready at ${directory}\n`);
}

main().catch((error) => {
  process.stderr.write(`${error?.message || error}\n`);
  process.exitCode = 1;
});

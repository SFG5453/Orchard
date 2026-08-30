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

import { existsSync } from 'node:fs';
import { cp, mkdir, mkdtemp, readFile, readdir, rm, writeFile } from 'node:fs/promises';
import { homedir, tmpdir } from 'node:os';
import { spawnSync } from 'node:child_process';
import path from 'node:path';
import process from 'node:process';

function option(name, fallback = '') {
  const prefix = `--${name}=`;
  const argument = process.argv.slice(2).find((value) => value.startsWith(prefix));
  return argument ? argument.slice(prefix.length) : fallback;
}

async function copyRequired(source, destination) {
  if (!existsSync(source)) throw new Error(`Required system-Electron input is missing: ${source}`);
  await mkdir(path.dirname(destination), { recursive: true });
  await cp(source, destination, { recursive: true, dereference: true, force: true });
}

function run(command, args, cwd) {
  const result = spawnSync(command, args, {
    cwd,
    env: { ...process.env, ELECTRON_SKIP_BINARY_DOWNLOAD: '1' },
    stdio: 'inherit'
  });
  if (result.error) throw result.error;
  if (result.status !== 0) throw new Error(`${command} exited with ${result.status ?? 'no status'}.`);
}

const STRIPPED_DEPENDENCY_DIRECTORIES = new Set([
  'test',
  'tests',
  'docs',
  'demo',
  'demos',
  'example',
  'examples'
]);

async function pruneProductionDependencyPayload(nodeModulesRoot) {
  const pending = [nodeModulesRoot];
  while (pending.length > 0) {
    const directory = pending.pop();
    for (const entry of await readdir(directory, { withFileTypes: true })) {
      const entryPath = path.join(directory, entry.name);
      if (entry.isDirectory()) {
        if (STRIPPED_DEPENDENCY_DIRECTORIES.has(entry.name)) {
          await rm(entryPath, { recursive: true, force: true });
        } else {
          pending.push(entryPath);
        }
      } else if (entry.isFile() && /\.map$/i.test(entry.name)) {
        await rm(entryPath, { force: true });
      }
    }
  }
}

async function pruneOnnxRuntime(appDir, architecture) {
  const runtimeRoot = path.join(appDir, 'node_modules', 'onnxruntime-node', 'bin', 'napi-v6');
  const platformRoot = path.join(runtimeRoot, 'linux');
  const targetRoot = path.join(platformRoot, architecture);

  for (const platform of await readdir(runtimeRoot)) {
    if (platform !== 'linux') await rm(path.join(runtimeRoot, platform), { recursive: true, force: true });
  }
  for (const candidate of await readdir(platformRoot)) {
    if (candidate !== architecture) {
      await rm(path.join(platformRoot, candidate), { recursive: true, force: true });
    }
  }

  const targetFiles = await readdir(targetRoot);
  if (!targetFiles.includes('onnxruntime_binding.node')) {
    throw new Error(`ONNX Runtime has no Linux ${architecture} binding.`);
  }
  await Promise.all(
    targetFiles
      .filter((file) => file.startsWith('libonnxruntime_providers_'))
      .map((file) => rm(path.join(targetRoot, file), { force: true }))
  );

  // Orchard uses the native runtime on Linux. The browser/WASM fallback is
  // needed only by Intel macOS installations.
  await rm(path.join(appDir, 'node_modules', 'onnxruntime-web'), { recursive: true, force: true });
}

const projectDir = process.cwd();
const electronDist = path.resolve(option('electron-dist', '/usr/lib/electron43'));
const outputDir = path.resolve(option('output', 'release-system-electron'));
const architecture = option('arch', process.arch === 'arm64' ? 'arm64' : 'x64');
const runtimeVersionFile = path.join(electronDist, 'version');

if ([path.parse(outputDir).root, homedir(), projectDir].includes(outputDir)) {
  throw new Error(`Refusing unsafe system-Electron output directory: ${outputDir}`);
}

if (process.platform !== 'linux' || !['x64', 'arm64'].includes(architecture)) {
  throw new Error(`System Electron packaging supports Linux x64 and arm64, not ${process.platform}-${architecture}.`);
}
if (!existsSync(runtimeVersionFile)) {
  throw new Error(`System Electron version file not found: ${runtimeVersionFile}`);
}

const runtimeVersion = (await readFile(runtimeVersionFile, 'utf8')).trim().replace(/^v/, '');
const packageJsonPath = path.join(projectDir, 'package.json');
const packageLockPath = path.join(projectDir, 'package-lock.json');
const packageJson = JSON.parse(await readFile(packageJsonPath, 'utf8'));
const expectedMajor = String(packageJson.devDependencies?.electron || '').match(/\d+/)?.[0];
const runtimeMajor = runtimeVersion.split('.')[0];

if (!expectedMajor || expectedMajor !== runtimeMajor) {
  throw new Error(`Orchard expects Electron ${expectedMajor || 'unknown'}, but ${electronDist} provides ${runtimeVersion}.`);
}

const workDir = await mkdtemp(path.join(tmpdir(), 'orchard-system-electron-'));
const dependencyDir = path.join(workDir, 'dependencies');
const appDir = path.join(outputDir, 'app');
const target = `linux-${architecture}`;

try {
  await rm(outputDir, { recursive: true, force: true });
  await mkdir(appDir, { recursive: true });

  await Promise.all([
    copyRequired(path.join(projectDir, 'dist'), path.join(appDir, 'dist')),
    copyRequired(path.join(projectDir, 'electron'), path.join(appDir, 'electron')),
    copyRequired(path.join(projectDir, 'shared'), path.join(appDir, 'shared')),
    copyRequired(path.join(projectDir, 'native-media', 'index.cjs'), path.join(appDir, 'native-media', 'index.cjs')),
    copyRequired(
      path.join(projectDir, 'native-media', 'build', `orchard-system-media-${target}.node`),
      path.join(appDir, 'native-media', 'build', `orchard-system-media-${target}.node`)
    ),
    copyRequired(path.join(projectDir, 'native-audio-rust', 'index.cjs'), path.join(appDir, 'native-audio-rust', 'index.cjs')),
    copyRequired(
      path.join(projectDir, 'native-audio-rust', 'build', `orchard-audio-${target}.node`),
      path.join(appDir, 'native-audio-rust', 'build', `orchard-audio-${target}.node`)
    ),
    copyRequired(
      path.join(projectDir, 'models', 'beat-this', 'beat_this_int8.onnx'),
      path.join(appDir, 'models', 'beat-this', 'beat_this_int8.onnx')
    ),
    copyRequired(
      path.join(projectDir, 'models', 'vocal-separation', 'vocals_umxhq_int8.onnx'),
      path.join(appDir, 'models', 'vocal-separation', 'vocals_umxhq_int8.onnx')
    ),
    copyRequired(packageJsonPath, path.join(appDir, 'package.json')),
    copyRequired(path.join(projectDir, 'LICENSE'), path.join(appDir, 'LICENSE'))
  ]);

  await writeFile(
    path.join(appDir, '.orchard-package.json'),
    `${JSON.stringify({ schemaVersion: 1, version: packageJson.version }, null, 2)}\n`
  );

  await mkdir(dependencyDir, { recursive: true });
  await Promise.all([
    cp(packageJsonPath, path.join(dependencyDir, 'package.json')),
    cp(packageLockPath, path.join(dependencyDir, 'package-lock.json'))
  ]);
  run('npm', ['ci', '--omit=dev', '--ignore-scripts', '--no-audit', '--no-fund'], dependencyDir);
  await cp(path.join(dependencyDir, 'node_modules'), path.join(appDir, 'node_modules'), {
    recursive: true,
    dereference: true,
    force: true
  });
  await rm(path.join(appDir, 'node_modules', '.bin'), { recursive: true, force: true });
  await pruneProductionDependencyPayload(path.join(appDir, 'node_modules'));
  await pruneOnnxRuntime(appDir, architecture);

  const required = [
    'package.json',
    '.orchard-package.json',
    'dist/index.html',
    'dist/welcome.html',
    'electron/main/index.js',
    `native-media/build/orchard-system-media-${target}.node`,
    `native-audio-rust/build/orchard-audio-${target}.node`,
    `node_modules/onnxruntime-node/bin/napi-v6/linux/${architecture}/onnxruntime_binding.node`
  ];
  for (const relativePath of required) {
    if (!existsSync(path.join(appDir, relativePath))) {
      throw new Error(`System Electron payload is incomplete: ${relativePath}`);
    }
  }

  console.log(`System Electron application written to ${appDir}`);
} finally {
  await rm(workDir, { recursive: true, force: true });
}

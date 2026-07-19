import { existsSync } from 'node:fs';
import { cp, mkdir, readFile, rm } from 'node:fs/promises';
import { spawnSync } from 'node:child_process';
import path from 'node:path';
import process from 'node:process';

function option(name, fallback = '') {
  const prefix = `--${name}=`;
  const argument = process.argv.slice(2).find((value) => value.startsWith(prefix));
  return argument ? argument.slice(prefix.length) : fallback;
}

const projectDir = process.cwd();
const electronDist = path.resolve(option('electron-dist', '/usr/lib/electron43'));
const outputDir = path.resolve(option('output', 'release-system-electron'));
const runtimeVersionFile = path.join(electronDist, 'version');

if (!existsSync(runtimeVersionFile)) {
  throw new Error(`System Electron version file not found: ${runtimeVersionFile}`);
}

const runtimeVersion = (await readFile(runtimeVersionFile, 'utf8')).trim().replace(/^v/, '');
const packageJson = JSON.parse(await readFile(path.join(projectDir, 'package.json'), 'utf8'));
const expectedMajor = String(packageJson.devDependencies?.electron || '').match(/\d+/)?.[0];
const runtimeMajor = runtimeVersion.split('.')[0];

if (!expectedMajor || expectedMajor !== runtimeMajor) {
  throw new Error(`Orchard expects Electron ${expectedMajor || 'unknown'}, but ${electronDist} provides ${runtimeVersion}.`);
}

const stageDir = path.join(outputDir, '.electron-builder');
await rm(outputDir, { recursive: true, force: true });
await mkdir(outputDir, { recursive: true });

const builder = path.join(projectDir, 'node_modules', '.bin', 'electron-builder');
const result = spawnSync(builder, [
  '--linux', 'dir',
  '--config', 'electron-builder.config.cjs',
  `-c.directories.output=${stageDir}`,
  `-c.electronDist=${electronDist}`,
  `-c.electronVersion=${runtimeVersion}`,
  '--publish', 'never'
], { cwd: projectDir, stdio: 'inherit' });

if (result.status !== 0) process.exit(result.status ?? 1);

const resources = path.join(stageDir, 'linux-unpacked', 'resources');
await cp(path.join(resources, 'app.asar'), path.join(outputDir, 'app.asar'));

const unpacked = path.join(resources, 'app.asar.unpacked');
if (existsSync(unpacked)) {
  await cp(unpacked, path.join(outputDir, 'app.asar.unpacked'), { recursive: true });
}

await rm(stageDir, { recursive: true, force: true });
console.log(`System Electron payload written to ${outputDir}`);

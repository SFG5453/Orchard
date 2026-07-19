import { createHash } from 'node:crypto';
import { cp, mkdir, readFile, rm, stat, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { spawn } from 'node:child_process';
import { officialArtistPackArtists, officialArtistPackVersion } from './official-artist-pack-content.mjs';

const repoRoot = process.cwd();
const contentRoot = path.join(repoRoot, 'workers', 'artist-packs', 'content');
const outRoot = path.join(repoRoot, 'build', 'artist-packs');
const webRoot = path.join(outRoot, 'official-web');
const packRoot = path.join(outRoot, 'official-pack');
const zipArchivePath = path.join(outRoot, 'orchard-official-artists.orchardpack');
const publicBaseUrl = '.';
const version = officialArtistPackVersion;
const archiveFileName = `orchard-official-artists-${fileSafeVersion(version)}.orchardpack.zst`;
const archivePath = path.join(webRoot, archiveFileName);
const artists = officialArtistPackArtists;

async function main() {
  await rm(webRoot, { recursive: true, force: true });
  await rm(packRoot, { recursive: true, force: true });
  await rm(zipArchivePath, { force: true });
  await rm(archivePath, { force: true });
  await mkdir(webRoot, { recursive: true });
  await mkdir(packRoot, { recursive: true });

  const index = {
    schema: 1,
    kind: 'orchard-official-artist-pack-index',
    version,
    generatedAt: new Date().toISOString(),
    archive: {
      url: `${publicBaseUrl}/${archiveFileName}`,
      sha256: '',
      size: 0
    },
    notes: [
      'Official Orchard artist page pack with hosted SZA, NBA Youngboy, Tyler, Kendrick Lamar, Kanye West, Kehlani, and Jhené Aiko pages, using Album Wall banner metadata instead of custom banner images.'
    ],
    artists: {}
  };

  for (const artist of artists) {
    const packArtistDir = path.join(packRoot, 'artists', artist.id);
    await mkdir(packArtistDir, { recursive: true });

    const config = JSON.parse(await readFile(path.join(contentRoot, artist.config), 'utf8'));
    config.styles = ['style.css'];

    const css = await Promise.all(
      artist.styles.map((stylePath) => readFile(path.join(contentRoot, stylePath), 'utf8'))
    );
    await writePack(path.join('artists', artist.id, 'style.css'), `${css.join('\n\n')}\n`);
    await writePack(path.join('artists', artist.id, 'artist.json'), `${JSON.stringify(config, null, 2)}\n`);

    for (const [packPath, sourcePath] of Object.entries(artist.assets)) {
      await copyPack(path.join('artists', artist.id, packPath), path.join(contentRoot, sourcePath));
    }

    const profilePath = config.assets?.profile || config.assets?.thumbnail || '';

    index.artists[artist.id] = {
      artistId: artist.id,
      artistName: config.artistName,
      displayName: config.displayName,
      layout: config.layout,
      search: config.search || {},
      profileArtwork: profilePath
    };
  }

  const manifest = {
    schema: 1,
    kind: 'orchard-official-artist-pack',
    version,
    generatedAt: index.generatedAt,
    artists: Object.keys(index.artists)
  };
  await writePack('manifest.json', `${JSON.stringify(manifest, null, 2)}\n`);
  await zipPack();
  await zstdPack();

  index.archive.sha256 = await sha256File(archivePath);
  index.archive.size = (await stat(archivePath)).size;
  await writeFile(path.join(webRoot, 'index.json'), `${JSON.stringify(index, null, 2)}\n`);

  console.log(`Built ${archivePath}`);
  console.log(`Built ${path.join(webRoot, 'index.json')}`);
}

async function writePack(relativePath, content) {
  await mkdir(path.dirname(path.join(packRoot, relativePath)), { recursive: true });
  await writeFile(path.join(packRoot, relativePath), content);
}

async function copyPack(relativePath, sourcePath) {
  await mkdir(path.dirname(path.join(packRoot, relativePath)), { recursive: true });
  await cp(sourcePath, path.join(packRoot, relativePath));
}

async function sha256File(filePath) {
  return createHash('sha256').update(await readFile(filePath)).digest('hex');
}

function fileSafeVersion(value) {
  return String(value).replace(/[^a-zA-Z0-9._-]/g, '-');
}

function zipPack() {
  return new Promise((resolve, reject) => {
    const child = spawn('zip', ['-qr', zipArchivePath, 'manifest.json', 'artists'], {
      cwd: packRoot,
      stdio: 'inherit'
    });
    child.on('error', reject);
    child.on('exit', (code) => {
      if (code === 0) resolve();
      else reject(new Error(`zip exited with ${code}`));
    });
  });
}

function zstdPack() {
  return new Promise((resolve, reject) => {
    const child = spawn('zstd', ['-q', '--ultra', '-22', '-f', zipArchivePath, '-o', archivePath], {
      cwd: repoRoot,
      stdio: 'inherit'
    });
    child.on('error', reject);
    child.on('exit', (code) => {
      if (code === 0) resolve();
      else reject(new Error(`zstd exited with ${code}`));
    });
  });
}

main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});

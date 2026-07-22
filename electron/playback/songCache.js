// Owns on-disk song bytes and metadata, including eviction and partial-response handling.
import fs from 'node:fs';
import path from 'node:path';
import { createRequire } from 'node:module';

const require = createRequire(import.meta.url);
const cacheDirectoryName = 'song-cache';
const minCacheSizeMb = 128;
const maxCacheSizeMb = 4096;
const defaultCacheSizeMb = 512;

export function normalizeSongCacheSettings(settings = {}) {
  const rawSize = Number(settings.maxSizeMb);
  const maxSizeMb = Number.isFinite(rawSize)
    ? Math.min(maxCacheSizeMb, Math.max(minCacheSizeMb, Math.round(rawSize / 128) * 128))
    : defaultCacheSizeMb;

  return {
    enabled: settings.enabled !== false,
    maxSizeMb,
    maxBytes: maxSizeMb * 1024 * 1024
  };
}

export function createSongCache(options = {}) {
  let settings = normalizeSongCacheSettings(options);
  const directory = options.directory || defaultSongCacheDirectory();
  const createCacheWriteStream = options.createWriteStream || fs.createWriteStream;
  const activeWrites = new Set();
  let directoryReady = null;

  async function ensureDirectory() {
    if (!directoryReady) {
      directoryReady = fs.promises.mkdir(directory, { recursive: true })
        .then(() => removePartialFiles())
        .catch((error) => {
          directoryReady = null;
          throw error;
        });
    }
    await directoryReady;
  }

  async function removePartialFiles() {
    const entries = await fs.promises.readdir(directory, { withFileTypes: true });
    await Promise.all(entries
      .filter((entry) => entry.isFile() && entry.name.endsWith('.part'))
      .map((entry) => fs.promises.unlink(path.join(directory, entry.name)).catch(() => {})));
  }

  function cachePath(videoId, stream) {
    const itag = stream?.format?.itag || 'auto';
    const length = stream?.format?.contentLength || 'unknown';
    const key = `${videoId}-${itag}-${length}`.replace(/[^a-z0-9._-]/gi, '_');
    return path.join(directory, `${key}.bin`);
  }

  function metadataPath(filePath) {
    return filePath.replace(/\.bin$/, '.json');
  }

  async function writeMetadata(filePath, videoId, stream) {
    await writeMetadataFile(filePath, {
      videoId,
      title: stream?.cacheMetadata?.title || '',
      artist: stream?.cacheMetadata?.artist || '',
      album: stream?.cacheMetadata?.album || '',
      thumbnail: stream?.cacheMetadata?.thumbnail || '',
      durationSeconds: Number(stream?.cacheMetadata?.durationSeconds || 0),
      itag: stream?.format?.itag || '',
      mimeType: stream?.format?.mimeType || '',
      contentLength: Number(stream?.format?.contentLength || 0),
      cachedAt: new Date().toISOString()
    });
  }

  async function writeMetadataFile(filePath, metadata) {
    await fs.promises.writeFile(metadataPath(filePath), JSON.stringify(metadata, null, 2));
  }

  function cacheable(stream, range) {
    const totalLength = Number(stream?.format?.contentLength || 0);
    return settings.enabled &&
      stream?.mediaKind !== 'video' &&
      totalLength > 0 &&
      totalLength <= settings.maxBytes &&
      range?.ok;
  }

  async function serve({ videoId, stream, range, req, res }) {
    if (!cacheable(stream, range)) return false;

    const filePath = cachePath(videoId, stream);
    let stat;
    try {
      stat = await fs.promises.stat(filePath);
    } catch {
      return false;
    }

    const totalLength = Number(stream.format.contentLength || 0);
    if (stat.size !== totalLength) return false;

    await fs.promises.utimes(filePath, new Date(), new Date()).catch(() => {});
    const start = range.wantsRange ? range.start : 0;
    const end = range.wantsRange ? Math.min(totalLength - 1, range.requestedEnd ?? totalLength - 1) : totalLength - 1;
    const responseLength = end - start + 1;
    res.writeHead(range.wantsRange ? 206 : 200, cacheHeaders(stream, totalLength, start, end, responseLength, range.wantsRange));

    if (req.method === 'HEAD') {
      res.end();
      return true;
    }

    await new Promise((resolve, reject) => {
      fs.createReadStream(filePath, { start, end })
        .once('error', reject)
        .once('end', resolve)
        .pipe(res);
    });
    return true;
  }

  async function pipeAndStore({ videoId, stream, range, upstream, res }) {
    if (!cacheable(stream, range) || !upstream.body || range.start !== 0 || range.requestedEnd || range.suffixLength) {
      return false;
    }

    const totalLength = Number(stream.format.contentLength || 0);
    const filePath = cachePath(videoId, stream);
    if (activeWrites.has(filePath)) return false;
    activeWrites.add(filePath);

    try {
      await ensureDirectory();
    } catch {
      activeWrites.delete(filePath);
      return false;
    }

    const tempPath = `${filePath}.${process.pid}.${Date.now()}.part`;
    const [playbackBody, cacheBody] = upstream.body.tee();
    const cacheController = new AbortController();
    const cacheTask = storeBody(cacheBody, tempPath, filePath, totalLength, cacheController.signal)
      .finally(() => activeWrites.delete(filePath));

    try {
      const playbackComplete = await pipeResponseBody(playbackBody, res);
      if (!playbackComplete) cacheController.abort();
    } catch (error) {
      cacheController.abort();
      if (!res.destroyed) res.destroy(error);
    }
    await cacheTask;

    return true;

    async function storeBody(body, temporaryPath, destinationPath, expectedLength, signal) {
      let writer;
      let reader;
      let written = 0;
      const cancel = () => void reader?.cancel().catch(() => {});
      try {
        if (signal.aborted) throw new Error('Song cache write was cancelled');
        writer = createCacheWriteStream(temporaryPath);
        // Write callbacks carry failures to this task; this listener also keeps
        // a late stream error from becoming an uncaught process error.
        writer.on('error', () => {});
        reader = body.getReader();
        signal.addEventListener('abort', cancel, { once: true });
        while (true) {
          const chunk = await reader.read();
          if (chunk.done) break;
          if (!chunk.value?.byteLength) continue;
          written += chunk.value.byteLength;
          await writeChunk(writer, chunk.value);
        }
        await finishWriter(writer);

        if (written !== expectedLength) throw new Error('Song cache received an incomplete stream');
        await fs.promises.rename(temporaryPath, destinationPath);
        await writeMetadata(destinationPath, videoId, stream).catch(() => {});
        await prune();
      } catch {
        writer?.destroy();
        await reader?.cancel().catch(() => {});
        await fs.promises.unlink(temporaryPath).catch(() => {});
      } finally {
        signal.removeEventListener('abort', cancel);
        reader?.releaseLock();
      }
    }
  }

  async function prune() {
    if (!settings.enabled) return;
    await ensureDirectory();
    const files = await cacheFiles();
    let total = files.reduce((sum, file) => sum + file.size, 0);

    for (const file of files.sort((a, b) => a.mtimeMs - b.mtimeMs)) {
      if (total <= settings.maxBytes) break;
      await fs.promises.unlink(file.filePath).catch(() => {});
      await fs.promises.unlink(metadataPath(file.filePath)).catch(() => {});
      total -= file.size;
    }
  }

  async function cacheFiles() {
    await ensureDirectory();
    const entries = await fs.promises.readdir(directory, { withFileTypes: true });

    return Promise.all(entries
      .filter((entry) => entry.isFile() && entry.name.endsWith('.bin'))
      .map(async (entry) => {
        const filePath = path.join(directory, entry.name);
        const stat = await fs.promises.stat(filePath);
        return { filePath, name: entry.name, mtimeMs: stat.mtimeMs, size: stat.size };
      }));
  }

  async function readMetadata(file) {
    try {
      return normalizeMetadata(JSON.parse(await fs.promises.readFile(metadataPath(file.filePath), 'utf8')), file);
    } catch {
      const videoId = videoIdFromCacheName(file.name);
      return normalizeMetadata({ videoId, title: '', artist: '', album: '', thumbnail: '' }, file);
    }
  }

  async function hydrateMissingMetadata(resolveMetadata, { limit = 50 } = {}) {
    if (typeof resolveMetadata !== 'function') return 0;

    const files = (await cacheFiles()).sort((a, b) => b.mtimeMs - a.mtimeMs);
    let hydrated = 0;

    for (const file of files) {
      if (hydrated >= limit) break;
      const metadata = await readMetadata(file);
      if (!metadata.videoId || friendlyTitle(metadata)) continue;

      try {
        const resolved = normalizeMetadata(await resolveMetadata(metadata.videoId), file);
        if (!friendlyTitle(resolved)) continue;

        await writeMetadataFile(file.filePath, {
          ...metadata,
          title: resolved.title,
          artist: resolved.artist || metadata.artist,
          album: resolved.album || metadata.album,
          thumbnail: resolved.thumbnail || metadata.thumbnail,
          durationSeconds: resolved.durationSeconds || metadata.durationSeconds,
          videoId: metadata.videoId,
          cachedAt: metadata.cachedAt || new Date(file.mtimeMs).toISOString()
        });
        hydrated += 1;
      } catch {
        // A failed metadata lookup should not block the cache list.
      }
    }

    return hydrated;
  }

  async function list() {
    const files = await cacheFiles();
    const entries = await Promise.all(files.map(async (file) => {
      const metadata = await readMetadata(file);

      return {
        key: file.name,
        size: file.size,
        cachedAt: new Date(file.mtimeMs).toISOString(),
        ...metadata
      };
    }));

    return {
      settings,
      directory,
      totalBytes: entries.reduce((sum, entry) => sum + entry.size, 0),
      entries: entries.sort((a, b) => new Date(b.cachedAt) - new Date(a.cachedAt))
    };
  }

  async function remove(key) {
    const safeKey = path.basename(String(key || ''));
    if (!safeKey || !safeKey.endsWith('.bin')) return list();

    const filePath = path.join(directory, safeKey);
    await fs.promises.unlink(filePath).catch(() => {});
    await fs.promises.unlink(metadataPath(filePath)).catch(() => {});
    return list();
  }

  async function clear() {
    const files = await cacheFiles();
    await Promise.all(files.flatMap((file) => [
      fs.promises.unlink(file.filePath).catch(() => {}),
      fs.promises.unlink(metadataPath(file.filePath)).catch(() => {})
    ]));
    return list();
  }

  function update(nextSettings = {}) {
    settings = normalizeSongCacheSettings(nextSettings);
    void prune().catch(() => {});
    return settings;
  }

  return { clear, hydrateMissingMetadata, list, pipeAndStore, remove, serve, update };
}

function normalizeMetadata(metadata = {}, file = {}) {
  return {
    videoId: String(metadata.videoId || videoIdFromCacheName(file.name || '') || '').trim(),
    title: String(metadata.title || '').trim(),
    artist: String(metadata.artist || '').trim(),
    album: String(metadata.album || '').trim(),
    thumbnail: String(metadata.thumbnail || '').trim(),
    durationSeconds: Number(metadata.durationSeconds || 0),
    itag: metadata.itag || '',
    mimeType: metadata.mimeType || '',
    contentLength: Number(metadata.contentLength || 0),
    cachedAt: metadata.cachedAt || ''
  };
}

function friendlyTitle(metadata = {}) {
  return metadata.title && metadata.title !== metadata.videoId;
}

function videoIdFromCacheName(name = '') {
  const match = String(name).match(/^(.*)-[^-]+-[^-]+\.bin$/);
  return match?.[1] || '';
}

function cacheHeaders(stream, totalLength, start, end, responseLength, partial) {
  return {
    'Content-Type': (stream.format.mimeType || 'audio/mp4').split(';', 1)[0],
    'Accept-Ranges': 'bytes',
    'Cache-Control': 'no-store',
    'Access-Control-Allow-Origin': '*',
    'Content-Length': String(responseLength),
    ...(partial ? { 'Content-Range': `bytes ${start}-${end}/${totalLength}` } : {})
  };
}

function defaultSongCacheDirectory() {
  try {
    return path.join(require('electron').app.getPath('userData'), cacheDirectoryName);
  } catch {
    return path.join(process.cwd(), '.orchard-song-cache');
  }
}

function onceDrain(stream) {
  return new Promise((resolve) => stream.once('drain', resolve));
}

async function pipeResponseBody(body, res) {
  const reader = body.getReader();
  let complete = false;
  try {
    while (!res.destroyed) {
      const chunk = await reader.read();
      if (chunk.done) {
        complete = true;
        break;
      }
      if (chunk.value?.byteLength && !res.write(chunk.value)) await onceDrain(res);
    }
  } finally {
    if (!complete) await reader.cancel().catch(() => {});
    reader.releaseLock();
  }
  if (!res.destroyed) res.end();
  return complete;
}

function writeChunk(writer, chunk) {
  return new Promise((resolve, reject) => {
    writer.write(chunk, (error) => error ? reject(error) : resolve());
  });
}

function finishWriter(writer) {
  return new Promise((resolve, reject) => {
    const onError = (error) => reject(error);
    writer.once('error', onError);
    writer.end(() => {
      writer.removeListener('error', onError);
      resolve();
    });
  });
}

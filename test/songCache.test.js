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
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Orchard. If not, see <https://www.gnu.org/licenses/>.
 */

import assert from 'node:assert/strict';
import { EventEmitter } from 'node:events';
import { access, mkdtemp, readdir, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { Writable } from 'node:stream';
import test from 'node:test';

import { createSongCache } from '../electron/playback/songCache.js';

function responseBody(bytes) {
  return new Response(Uint8Array.from(bytes)).body;
}

function chunkedResponseBody(chunks) {
  let index = 0;
  return new ReadableStream({
    pull(controller) {
      if (index >= chunks.length) {
        controller.close();
        return;
      }
      controller.enqueue(Uint8Array.from(chunks[index]));
      index += 1;
    }
  });
}

function fakeResponse() {
  const response = new EventEmitter();
  response.chunks = [];
  response.destroyed = false;
  response.ended = false;
  response.write = (chunk) => {
    response.chunks.push(Buffer.from(chunk));
    return true;
  };
  response.end = () => {
    response.ended = true;
  };
  response.destroy = () => {
    response.destroyed = true;
  };
  return response;
}

function writableHttpResponse() {
  const response = new Writable({
    write(chunk, _encoding, callback) {
      response.chunks.push(Buffer.from(chunk));
      callback();
    }
  });
  response.chunks = [];
  response.statusCode = 0;
  response.headers = {};
  response.writeHead = (statusCode, headers) => {
    response.statusCode = statusCode;
    response.headers = headers;
  };
  return response;
}

function cacheRequest(bytes = [1, 2, 3, 4]) {
  return {
    videoId: 'cache-track',
    stream: {
      format: {
        contentLength: bytes.length,
        itag: 140,
        mimeType: 'audio/mp4'
      },
      mediaKind: 'audio'
    },
    range: { ok: true, wantsRange: false, start: 0 },
    upstream: { body: responseBody(bytes) },
    res: fakeResponse()
  };
}

test('song cache removes abandoned partial files before serving entries', async () => {
  const directory = await mkdtemp(path.join(tmpdir(), 'orchard-song-cache-parts-'));
  const partialPath = path.join(directory, 'stale.bin.123.456.part');
  await writeFile(partialPath, 'partial');
  const cache = createSongCache({ directory });

  try {
    await cache.list();
    await assert.rejects(access(partialPath));
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
});

test('song cache allows only one writer for the same complete stream', async () => {
  const directory = await mkdtemp(path.join(tmpdir(), 'orchard-song-cache-writer-'));
  const cache = createSongCache({ directory });
  const first = cacheRequest();
  const duplicate = cacheRequest();

  try {
    const firstWrite = cache.pipeAndStore(first);
    assert.equal(await cache.pipeAndStore(duplicate), false);
    assert.equal(await firstWrite, true);
    assert.equal(first.res.ended, true);
    assert.deepEqual(Buffer.concat(first.res.chunks), Buffer.from([1, 2, 3, 4]));

    const inventory = await cache.list();
    assert.equal(inventory.entries.length, 1);
    assert.equal(inventory.entries[0].size, 4);
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
});

test('song cache write failures never interrupt the playback response', async () => {
  const directory = await mkdtemp(path.join(tmpdir(), 'orchard-song-cache-failure-'));
  const cache = createSongCache({
    directory,
    createWriteStream() {
      return new Writable({
        write(_chunk, _encoding, callback) {
          callback(new Error('disk write failed'));
        }
      });
    }
  });
  const request = cacheRequest([9, 8, 7, 6]);

  try {
    assert.equal(await cache.pipeAndStore(request), true);
    assert.equal(request.res.destroyed, false);
    assert.equal(request.res.ended, true);
    assert.deepEqual(Buffer.concat(request.res.chunks), Buffer.from([9, 8, 7, 6]));
    assert.equal((await cache.list()).entries.length, 0);
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
});

test('song cache abandons a write that falls too far behind playback', async () => {
  const directory = await mkdtemp(path.join(tmpdir(), 'orchard-song-cache-lag-'));
  let writes = 0;
  const cache = createSongCache({
    directory,
    maxWriteLagBytes: 4,
    createWriteStream() {
      return new Writable({
        write(_chunk, _encoding, callback) {
          writes += 1;
          setTimeout(callback, 20);
        }
      });
    }
  });
  const bytes = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12];
  const request = cacheRequest(bytes);
  request.upstream.body = chunkedResponseBody([
    bytes.slice(0, 4),
    bytes.slice(4, 8),
    bytes.slice(8)
  ]);

  try {
    assert.equal(await cache.pipeAndStore(request), true);
    assert.equal(request.res.destroyed, false);
    assert.equal(request.res.ended, true);
    assert.deepEqual(Buffer.concat(request.res.chunks), Buffer.from(bytes));
    assert.equal(writes, 1);
    assert.equal((await cache.list()).entries.length, 0);
    assert.deepEqual(await readdir(directory), []);
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
});

test('manual downloads remain pinned when the replay cache is cleared', async () => {
  const directory = await mkdtemp(path.join(tmpdir(), 'orchard-song-download-pin-'));
  const cache = createSongCache({ directory });
  const request = cacheRequest([4, 3, 2, 1]);
  request.stream.cacheMetadata = {
    title: 'Offline Song',
    artist: 'Offline Artist',
    album: 'Offline Album'
  };

  try {
    assert.equal(await cache.pipeAndStore(request), true);
    await cache.pin('cache-track', {
      videoId: 'library-track',
      title: 'Offline Song',
      artist: 'Offline Artist',
      artistBrowseIds: ['artist-id'],
      album: 'Offline Album',
      albumId: 'album-id',
      collections: [{ browseId: 'playlist-one', kind: 'playlist', title: 'Road Trip' }]
    });
    await cache.pin('cache-track', {
      videoId: 'library-track',
      collections: [{ browseId: 'playlist-two', kind: 'playlist', title: 'Favorites' }]
    });

    let inventory = await cache.list();
    assert.equal(inventory.downloads.length, 1);
    assert.equal(inventory.downloads[0].videoId, 'library-track');
    assert.equal(inventory.downloads[0].sourceVideoId, 'cache-track');
    assert.deepEqual(inventory.downloads[0].artistBrowseIds, ['artist-id']);
    assert.deepEqual(inventory.downloads[0].collections.map((collection) => collection.browseId), [
      'playlist-one',
      'playlist-two'
    ]);

    await cache.clear();
    inventory = await cache.list();
    assert.equal(inventory.downloads.length, 1);
    assert.equal((await cache.findDownloaded('library-track')).albumId, 'album-id');
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
});

test('downloaded songs serve byte ranges by their library track ID', async () => {
  const directory = await mkdtemp(path.join(tmpdir(), 'orchard-song-download-serve-'));
  const cache = createSongCache({ directory });
  const request = cacheRequest([8, 6, 4, 2]);

  try {
    await cache.pipeAndStore(request);
    await cache.pin('cache-track', { videoId: 'library-track', title: 'Range Song' });
    const response = writableHttpResponse();
    assert.equal(await cache.serveDownload('library-track', {
      method: 'GET',
      headers: { range: 'bytes=1-2' }
    }, response), true);
    assert.equal(response.statusCode, 206);
    assert.equal(response.headers['Content-Range'], 'bytes 1-2/4');
    assert.deepEqual(Buffer.concat(response.chunks), Buffer.from([6, 4]));
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
});

test('manual download requests write even when automatic song caching is disabled', async () => {
  const directory = await mkdtemp(path.join(tmpdir(), 'orchard-song-download-disabled-cache-'));
  const cache = createSongCache({ directory, enabled: false });
  const request = cacheRequest([7, 7, 7]);
  request.stream.cacheMetadata = { title: 'Saved', downloadRequested: true };

  try {
    assert.equal(await cache.pipeAndStore(request), true);
    const inventory = await cache.list();
    assert.equal(inventory.downloads.length, 1);
    assert.equal(inventory.downloads[0].title, 'Saved');
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
});

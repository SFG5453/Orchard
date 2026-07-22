import assert from 'node:assert/strict';
import { EventEmitter } from 'node:events';
import { access, mkdtemp, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { Writable } from 'node:stream';
import test from 'node:test';

import { createSongCache } from '../electron/playback/songCache.js';

function responseBody(bytes) {
  return new Response(Uint8Array.from(bytes)).body;
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

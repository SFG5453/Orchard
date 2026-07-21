import test from 'node:test';
import assert from 'node:assert/strict';
import { downloadAudioFile } from '../src/audio/engine/audioFetch.js';

function bytes(values) {
  return Uint8Array.from(values).buffer;
}

test('downloadAudioFile reconstructs a file with bounded concurrent ranges', async () => {
  const source = new Uint8Array(256 * 1024);
  source.forEach((_, index) => { source[index] = index % 251; });
  const requests = [];
  let activeRequests = 0;
  let maximumActiveRequests = 0;
  const fetchImpl = async (_url, options = {}) => {
    if (options.method === 'HEAD') {
      return new Response(null, {
        status: 200,
        headers: {
          'Accept-Ranges': 'bytes',
          'Content-Length': String(source.byteLength)
        }
      });
    }

    const match = /^bytes=(\d+)-(\d+)$/.exec(options.headers?.Range || '');
    assert.ok(match);
    const start = Number(match[1]);
    const end = Number(match[2]);
    requests.push([start, end]);
    activeRequests += 1;
    maximumActiveRequests = Math.max(maximumActiveRequests, activeRequests);
    await new Promise((resolve) => setTimeout(resolve, 5));
    activeRequests -= 1;
    return new Response(source.slice(start, end + 1), {
      status: 206,
      headers: { 'Content-Range': `bytes ${start}-${end}/${source.byteLength}` }
    });
  };

  const result = await downloadAudioFile('http://127.0.0.1/audio', {
    fetchImpl,
    chunkBytes: 64 * 1024,
    concurrency: 3
  });

  assert.equal(requests.length, 4);
  assert.ok(maximumActiveRequests > 1);
  assert.deepEqual(new Uint8Array(result), source);
});

test('downloadAudioFile falls back to one GET when ranges are unavailable', async () => {
  const calls = [];
  const fetchImpl = async (_url, options = {}) => {
    calls.push(options);
    if (options.method === 'HEAD') {
      return new Response(null, {
        status: 200,
        headers: { 'Content-Length': '4' }
      });
    }
    return new Response(bytes([4, 3, 2, 1]), { status: 200 });
  };

  const result = await downloadAudioFile('https://example.invalid/audio', { fetchImpl });
  assert.deepEqual(new Uint8Array(result), Uint8Array.from([4, 3, 2, 1]));
  assert.equal(calls.length, 2);
  assert.equal(calls[1].headers, undefined);
});

test('downloadAudioFile probes before falling back when Range is ignored', async () => {
  const calls = [];
  const fetchImpl = async (_url, options = {}) => {
    calls.push(options);
    if (options.method === 'HEAD') {
      return new Response(null, {
        status: 200,
        headers: {
          'Accept-Ranges': 'bytes',
          'Content-Length': '4'
        }
      });
    }
    return new Response(bytes([1, 2, 3, 4]), { status: 200 });
  };

  const result = await downloadAudioFile('https://example.invalid/audio', {
    fetchImpl,
    chunkBytes: 64 * 1024,
    concurrency: 6
  });
  assert.deepEqual(new Uint8Array(result), Uint8Array.from([1, 2, 3, 4]));
  assert.equal(calls.length, 3);
  assert.equal(calls.filter((call) => call.headers?.Range).length, 1);
});

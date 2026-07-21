import test from 'node:test';
import assert from 'node:assert/strict';
import { proxyHeadResponseHeaders, proxyResponseHeaders } from '../electron/playback/streamProxy.js';

test('proxyHeadResponseHeaders exposes range metadata without an upstream body', () => {
  assert.deepEqual(proxyHeadResponseHeaders('audio/webm; codecs="opus"', 3145728), {
    'Content-Type': 'audio/webm',
    'Accept-Ranges': 'bytes',
    'Cache-Control': 'no-store',
    'Access-Control-Allow-Origin': '*',
    'Access-Control-Expose-Headers': 'Accept-Ranges, Content-Length, Content-Range',
    'Content-Length': '3145728'
  });
});

test('proxyResponseHeaders exposes range headers to the renderer', () => {
  const upstream = new Response(null, {
    status: 206,
    headers: {
      'Accept-Ranges': 'bytes',
      'Content-Length': '512',
      'Content-Range': 'bytes 0-511/1024'
    }
  });
  const headers = proxyResponseHeaders(upstream, 'audio/mp4', 1024, true);
  assert.equal(
    headers['Access-Control-Expose-Headers'],
    'Accept-Ranges, Content-Length, Content-Range'
  );
});

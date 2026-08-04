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

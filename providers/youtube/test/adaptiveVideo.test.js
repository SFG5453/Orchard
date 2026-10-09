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
import test from 'node:test';
import {
  availableHeights,
  chooseAdaptiveVideoFormat,
  rawAdaptiveVideoFormats
} from '../src/playback/adaptiveVideo.js';

const formats = rawAdaptiveVideoFormats([
  { itag: 401, mimeType: 'video/mp4; codecs="av01.0.12M.08"', height: 2160, fps: 30, signatureCipher: 'a' },
  { itag: 313, mimeType: 'video/webm; codecs="vp9"', height: 2160, fps: 30, signatureCipher: 'b' },
  { itag: 248, mimeType: 'video/webm; codecs="vp9"', height: 1080, fps: 30, signatureCipher: 'c' },
  { itag: 137, mimeType: 'video/mp4; codecs="avc1.640028"', height: 1080, fps: 30, signatureCipher: 'd' },
  { itag: 136, mimeType: 'video/mp4; codecs="avc1.4d401f"', height: 720, fps: 30, signatureCipher: 'e' },
  { itag: 140, mimeType: 'audio/mp4; codecs="mp4a.40.2"', signatureCipher: 'f' },
  { itag: 18, mimeType: 'video/mp4; codecs="avc1.42001E, mp4a.40.2"', height: 360, signatureCipher: 'g' }
]);

test('keeps only video-only adaptive formats', () => {
  assert.deepEqual(formats.map((format) => format.itag), [401, 313, 248, 137, 136]);
  assert.deepEqual(availableHeights(formats), [2160, 1080, 720]);
});

test('prefers H.264 at the tallest height under the cap', () => {
  assert.equal(chooseAdaptiveVideoFormat(formats, 1080).itag, 137);
  assert.equal(chooseAdaptiveVideoFormat(formats, 1440).itag, 137);
});

test('falls back to VP9 before AV1 when H.264 lacks the height', () => {
  assert.equal(chooseAdaptiveVideoFormat(formats, 0).itag, 313);
});

test('takes the shortest height when nothing fits under the cap', () => {
  assert.equal(chooseAdaptiveVideoFormat(formats, 480).itag, 136);
  assert.equal(chooseAdaptiveVideoFormat([], 1080), null);
});

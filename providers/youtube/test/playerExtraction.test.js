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
import { extract } from '../src/runtime/extractor.js';
import { playerFromSource } from '../src/runtime/player.js';

test('player uses the native extractor and executes its returned signature program', async () => {
  const previous = globalThis.__orchardExtractPlayer;
  globalThis.__orchardExtractPlayer = source => {
    assert.equal(source, 'downloaded player source');
    return {
      output: `const exportedVars = { nsigFunction: (url, name, value) => {
        const params = new Map([['n', 'solved-n'], [name, 'solved-' + value]]);
        return new class { get(name) { return params.get(name); } };
      } };`,
      exported: ['nsigFunction'],
      exportedRawValues: { signatureTimestampVar: '20702' },
      timings: { parseMs: 2, analyzeMs: 3, emitMs: 1 }
    };
  };
  try {
    const player = playerFromSource('downloaded player source');
    assert.equal(player.signature_timestamp, 20702);
    assert.equal(player.timings.parseMs, 2);
    const cipher = new URLSearchParams({
      url: 'https://test.googlevideo.com/videoplayback?n=input', s: 'input-signature', sp: 'sig'
    }).toString();
    const result = new URL(await player.decipher('', cipher));
    assert.equal(result.searchParams.get('n'), 'solved-n');
    assert.equal(result.searchParams.get('sig'), 'solved-input-signature');
  } finally { globalThis.__orchardExtractPlayer = previous; }
});

test('native extraction errors are surfaced without executing downloaded source', () => {
  const previous = globalThis.__orchardExtractPlayer;
  globalThis.__orchardExtractPlayer = () => ({ error: 'Unsupported player shape' });
  try { assert.throws(() => extract('source'), /Unsupported player shape/); }
  finally { globalThis.__orchardExtractPlayer = previous; }
});

test('missing native extraction is reported clearly', () => {
  const previous = globalThis.__orchardExtractPlayer;
  delete globalThis.__orchardExtractPlayer;
  try { assert.throws(() => extract('source'), /Native YouTube player extraction is unavailable/); }
  finally { globalThis.__orchardExtractPlayer = previous; }
});

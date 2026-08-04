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
import { rowIndexAt, rowOffsets } from '../src/app/playback/useVirtualRows.js';

const ROW = 54;
const GROUP = 26;

function plainRows(count) {
  return Array.from({ length: count }, (_, index) => ({ id: `track-${index + 1}` }));
}

// Mirrors the continuous queue: a heading sits above the first entry of each
// section, so every section start pushes the rows after it further down.
function sectionedRows(sections) {
  return sections.flatMap(([section, count]) => Array.from({ length: count }, (_, index) => ({
    section,
    sectionStart: index === 0
  })));
}

const headerFor = (row) => (row?.sectionStart ? GROUP : 0);

test('uniform rows stack at exact multiples of the row height', () => {
  const table = rowOffsets(plainRows(5), ROW);

  assert.deepEqual(table, [0, 54, 108, 162, 216, 270]);
  assert.equal(table.at(-1), 5 * ROW, 'the last entry is the total height');
});

test('section headings push every row below them down', () => {
  const rows = sectionedRows([['previous', 2], ['current', 1], ['next', 2]]);
  const table = rowOffsets(rows, ROW, headerFor);

  assert.equal(table[0], GROUP, 'the first row clears its own heading');
  assert.equal(table[1], GROUP + ROW);
  assert.equal(table[2], GROUP + 2 * ROW + GROUP, 'the current section adds a second heading');
  assert.equal(table[4], 3 * GROUP + 4 * ROW, 'the third heading lands before the last section');
  assert.equal(table.at(-1), 3 * GROUP + 5 * ROW);
});

test('an empty list has no height', () => {
  assert.deepEqual(rowOffsets([], ROW, headerFor), [0]);
});

test('a pixel inside a row resolves to that row, not the next one', () => {
  const table = rowOffsets(plainRows(100), ROW);

  assert.equal(rowIndexAt(table, 0), 0);
  assert.equal(rowIndexAt(table, 53), 0, 'one pixel short of the boundary is still row 0');
  assert.equal(rowIndexAt(table, 54), 1, 'the boundary itself belongs to the next row');
  assert.equal(rowIndexAt(table, 55), 1);
  assert.equal(rowIndexAt(table, 50 * ROW), 50);
});

test('scrolling past the end clamps to the last row rather than running off it', () => {
  const table = rowOffsets(plainRows(10), ROW);

  assert.equal(rowIndexAt(table, 10 * ROW), 9);
  assert.equal(rowIndexAt(table, 1e9), 9);
});

test('a negative offset clamps to the first row', () => {
  const table = rowOffsets(plainRows(10), ROW);
  assert.equal(rowIndexAt(table, -400), 0);
});

test('the binary search agrees with a scan across a playlist-sized list', () => {
  const rows = sectionedRows([['previous', 30], ['current', 1], ['next', 4969]]);
  const table = rowOffsets(rows, ROW, headerFor);

  const scan = (pixel) => {
    let found = 0;
    for (let index = 0; index < rows.length; index += 1) {
      if (table[index] <= pixel) found = index;
    }
    return found;
  };

  for (const pixel of [0, 1, 25, 26, 79, 80, 1234, 54321, 99999, table.at(-1) - 1]) {
    assert.equal(rowIndexAt(table, pixel), scan(pixel), `mismatch at ${pixel}px`);
  }
});

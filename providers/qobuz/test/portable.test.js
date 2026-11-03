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
import { readFile, readdir } from 'node:fs/promises';
import test from 'node:test';

import { qobuzRequestSignature, selectQobuzMatch } from '../src/index.js';
import { explainQobuzMiss } from '../src/matcher.js';

test('runtime sources have no Node or Electron dependency', async () => {
  const sourceUrl = new URL('../src/', import.meta.url);
  const names = (await readdir(sourceUrl)).filter((name) => name.endsWith('.js'));
  for (const name of names) {
    const source = await readFile(new URL(name, sourceUrl), 'utf8');
    assert.doesNotMatch(source, /from\s+['\"]node:|require\s*\(|\belectron\b/i, name);
  }
});

test('portable MD5 matches the standard vector and request ordering is stable', () => {
  assert.equal(qobuzRequestSignature('', {}, '', ''), 'd41d8cd98f00b204e9800998ecf8427e');
  assert.equal(
    qobuzRequestSignature('fileurl', { track_id: 42, format_id: 27 }, 123, 'secret'),
    qobuzRequestSignature('fileurl', { format_id: 27, track_id: 42 }, '123', 'secret')
  );
});

test('matching behavior survived the move', () => {
  const target = {
    title: 'Example Song', artists: ['Example Artist'], album: 'Example',
    durationMs: 180_000, isrc: 'USABC1200001', explicit: false
  };
  const match = selectQobuzMatch(target, [{
    id: 2, title: 'Example Song', isrc: target.isrc, duration: 180,
    performer: { name: 'Example Artist' }, maximum_bit_depth: 24
  }], 'isrc');
  assert.equal(match.qobuzTrackId, 2);
});

test('artist names match across "The" and dotted initials', () => {
  const target = {
    title: 'Big Poppa (2005 Remaster)', artists: ['Notorious B.I.G.'], album: 'Ready to Die (2005 Remaster)',
    durationMs: 253_000, isrc: '', explicit: false
  };
  const match = selectQobuzMatch(target, [{
    id: 7, title: 'Big Poppa (2005 Remaster)', duration: 253, performer: { name: 'The Notorious B.I.G.' },
    album: { title: 'Ready To Die The Remaster (U.S. Explicit Version 94567)' }
  }]);
  assert.equal(match?.qobuzTrackId, 7);
});

test('matches an exact album track when the catalog has no duration', () => {
  const target = {
    title: 'Carter Son', artists: ['YoungBoy Never Broke Again'], album: 'AI YoungBoy 2',
    durationMs: 0, isrc: '', explicit: true
  };
  const candidates = [
    { id: 1, title: 'Carter Son', duration: 163, parental_warning: true,
      performer: { name: 'YoungBoy Never Broke Again' }, album: { title: 'AI YoungBoy 2' } },
    { id: 2, title: 'Carter Son', duration: 163,
      performer: { name: 'YoungBoy Never Broke Again' }, album: { title: 'AI YoungBoy 2' } }
  ];
  assert.equal(selectQobuzMatch(target, candidates)?.qobuzTrackId, 1);
  assert.equal(selectQobuzMatch({ ...target, durationMs: 180_000 }, candidates), null);
});

test('does not guess between different recordings without a duration', () => {
  const target = {
    title: 'Example Song', artists: ['Example Artist'], album: '',
    durationMs: 0, isrc: '', explicit: false
  };
  const candidates = [
    { id: 1, title: 'Example Song', duration: 163, isrc: 'USABC1200001',
      performer: { name: 'Example Artist' }, album: { title: 'First Album' } },
    { id: 2, title: 'Example Song', duration: 190, isrc: 'USABC1200002',
      performer: { name: 'Example Artist' }, album: { title: 'Second Album' } }
  ];
  assert.equal(selectQobuzMatch(target, candidates), null);
  assert.equal(selectQobuzMatch({ ...target, album: 'First Album' }, candidates)?.qobuzTrackId, 1);
});

test('miss reports distinguish missing source time from Qobuz candidate time', () => {
  const target = { title: 'Example Song', artists: ['Example Artist'], album: '', durationMs: 0, explicit: false };
  const candidate = { title: 'Example Song', duration: 163,
    performer: { name: 'Example Artist' }, album: { title: 'Example Album' } };
  assert.deepEqual(explainQobuzMiss(target, [candidate]), {
    reason: 'source track has no duration', candidate: 'Example Song / example artist / Example Album',
    candidateDurationSeconds: 163
  });
});

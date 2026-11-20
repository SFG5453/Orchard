import assert from 'node:assert/strict';
import test from 'node:test';
import { parseLocalLyrics } from '../src/catalog/lyricsParsers.js';

test('LRC offsets shift every stamp, positive meaning earlier', () => {
  const early = parseLocalLyrics('[offset:+500]\n[00:10.00]Line');
  assert.equal(early.synced[0].startTime, 9.5);
  const late = parseLocalLyrics('[offset:-250]\n[00:10.00]Line');
  assert.equal(late.synced[0].startTime, 10.25);
  assert.equal(parseLocalLyrics('[offset:+5000]\n[00:01.00]Intro').synced[0].startTime, 0);
});

test('enhanced LRC word stamps become words instead of leaking into the text', () => {
  const [line] = parseLocalLyrics('[00:10.00]<00:10.00>Hola <00:10.50>mundo <00:11.20>').synced;
  assert.equal(line.text, 'Hola mundo');
  assert.deepEqual(line.words, [
    { text: 'Hola', startTime: 10, endTime: 10.5 },
    { text: 'mundo', startTime: 10.5, endTime: 11.2 }
  ]);
});

test('a bare stamp ends the line before it so the instrumental pause lines up', () => {
  const { synced } = parseLocalLyrics('[00:10.00]Verse\n[00:14.00]\n[00:40.00]Chorus');
  assert.deepEqual(synced, [
    { text: 'Verse', startTime: 10, endTime: 14 },
    { text: 'Chorus', startTime: 40 }
  ]);
});

test('repeated stamps still repeat the line, in time order', () => {
  const { synced } = parseLocalLyrics('[00:30.00][01:10.00]Chorus\n[00:40.00]Verse');
  assert.deepEqual(synced.map((line) => [line.text, line.startTime]), [['Chorus', 30], ['Verse', 40], ['Chorus', 70]]);
});

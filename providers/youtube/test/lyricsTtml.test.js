import assert from 'node:assert/strict';
import test from 'node:test';
import { parseAmLyricsTtml } from '../src/catalog/lyricsParsers.js';

test('TTML retains word timing, whitespace, entities, background vocals and inherited agents', () => {
  const xml = `<tt xmlns:ttm="http://www.w3.org/ns/ttml#metadata" xmlns:itunes="urn:itunes">
    <body ttm:agent="v1"><div><p begin="1s" end="4s" itunes:key="L1">
      <span begin="1s" end="2s">Hello</span> <span begin="2s" end="3s">world &amp; &#x2665;</span>
      <span ttm:role="x-bg"><span begin="3s" end="4s">yeah</span></span>
    </p></div></body></tt>`;
  assert.deepEqual(parseAmLyricsTtml(xml), [{
    text: 'Hello world & ♥', startTime: 1, endTime: 4, agent: 'v1', key: 'L1',
    words: [
      { text: 'Hello', startTime: 1, endTime: 2 },
      { text: 'world', startTime: 2, endTime: 3 },
      { text: '&', startTime: 2, endTime: 3 },
      { text: '♥', startTime: 2, endTime: 3 }
    ],
    adlibs: [{ text: 'yeah', startTime: 3, endTime: 4 }]
  }]);
});

test('TTML reads CDATA and unsynced lyrics, and rejects malformed XML', () => {
  const xml = '<tt><body><p>One <![CDATA[& two]]> &#51;</p></body></tt>';
  assert.deepEqual(parseAmLyricsTtml(xml), [{ text: 'One & two 3' }]);
  assert.deepEqual(parseAmLyricsTtml('<tt><p>broken'), []);
});

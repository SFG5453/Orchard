import assert from 'node:assert/strict';
import test from 'node:test';
import { installVisualUtils } from '../src/app/appearance/visualUtils.js';

function ctxWithVisualUtils() {
  const ctx = {};
  installVisualUtils(ctx);
  return ctx;
}

test('itemMeta falls back to the album artist when a track subtitle is just a year', () => {
  const ctx = ctxWithVisualUtils();
  const track = { type: 'track', subtitle: '2016', artists: [] };

  assert.equal(ctx.itemMeta(track, 'Bruno Mars'), 'Bruno Mars');
});

test('itemMeta still returns a real subtitle for a track', () => {
  const ctx = ctxWithVisualUtils();
  const track = { type: 'track', subtitle: 'Bruno Mars', artists: [] };

  assert.equal(ctx.itemMeta(track, 'Fallback Artist'), 'Bruno Mars');
});

test('itemMeta prefers artists over subtitle', () => {
  const ctx = ctxWithVisualUtils();
  const track = { type: 'track', subtitle: '2016', artists: ['Bruno Mars'] };

  assert.equal(ctx.itemMeta(track, 'Fallback Artist'), 'Bruno Mars');
});

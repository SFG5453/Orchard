import assert from 'node:assert/strict';
import test from 'node:test';
import {
  createSmartCrossfadeMixPresentation,
  smartCrossfadeOverlayDuration
} from '../src/app/playback/smartCrossfadeMixPresentation.js';

test('smart crossfade overlay duration stays brief for long DJ transitions', () => {
  assert.equal(smartCrossfadeOverlayDuration(0), 2800);
  assert.equal(smartCrossfadeOverlayDuration(8), 3380);
  assert.equal(smartCrossfadeOverlayDuration(30), 4800);
});

test('smart crossfade presentation exposes both decks and analysis metadata', () => {
  const mix = createSmartCrossfadeMixPresentation({
    id: 7,
    fromTrack: { id: 'a', title: 'First', artist: 'Artist A', thumbnail: 'from.jpg' },
    toTrack: { id: 'b', title: 'Second', artists: ['Artist B'], thumbnail: 'to.jpg' },
    currentArtwork: 'enhanced.jpg',
    transition: {
      fadeSeconds: 12,
      transitionStyle: 'dj_switch',
      transitionBeats: 16,
      incomingPlaybackRate: 0.97
    },
    analysis: { bpm: 119.6, key: 'A minor' },
    nextAnalysis: { bpm: 123.3, key: 'C major' }
  });

  assert.equal(mix.id, 7);
  assert.equal(mix.visible, true);
  assert.equal(mix.styleLabel, 'Phrase switch');
  assert.deepEqual(mix.from, {
    id: 'a',
    title: 'First',
    artist: 'Artist A',
    artwork: 'enhanced.jpg'
  });
  assert.equal(mix.to.artist, 'Artist B');
  assert.equal(mix.fromBpm, 120);
  assert.equal(mix.toBpm, 123);
  assert.equal(mix.fromKey, 'A minor');
  assert.equal(mix.toKey, 'C major');
  assert.equal(mix.tempoShift, -3);
  assert.equal(mix.transitionBeats, 16);
});

import assert from 'node:assert/strict';
import test from 'node:test';
import { mergeShuffledTail, playlistPlayedTrackIds, playlistPreviousState, unusedPlaylistTracks } from '../src/app/playback/playbackCollectionQueue.js';

function tracks(count, start = 1) {
  return Array.from({ length: count }, (_, index) => ({ id: `track-${start + index}` }));
}

test('does not requeue playlist tracks that fell out of capped history', () => {
  const playlist = tracks(40);

  assert.deepEqual(unusedPlaylistTracks({
    allTracks: playlist,
    activeTrack: playlist[29],
    queue: playlist.slice(30),
    history: playlist.slice(1, 29),
    playedTrackIds: playlist.slice(0, 29).map((track) => track.id)
  }), []);
});

test('keeps genuinely unplayed playlist tracks available for refill', () => {
  const playlist = tracks(5);

  assert.deepEqual(unusedPlaylistTracks({
    allTracks: playlist,
    activeTrack: playlist[0],
    queue: [playlist[1]],
    playedTrackIds: [playlist[0].id]
  }), playlist.slice(2));
});

test('treats tracks before a manually selected playlist song as skipped', () => {
  const playlist = tracks(110);
  const activeTrack = playlist.at(-1);

  assert.deepEqual(playlistPlayedTrackIds(playlist, activeTrack.id), playlist.map((track) => track.id));
  assert.deepEqual(unusedPlaylistTracks({
    allTracks: playlist,
    activeTrack,
    playedTrackIds: playlistPlayedTrackIds(playlist, activeTrack.id)
  }), []);
});

test('walks backward through a playlist without crossing its first track', () => {
  const playlist = tracks(8);

  assert.deepEqual(playlistPreviousState(playlist, playlist[7].id), {
    activeIndex: 7,
    previousTrack: playlist[6]
  });
  assert.deepEqual(playlistPreviousState(playlist, playlist[0].id), {
    activeIndex: 0,
    previousTrack: null
  });
});

test('a late playlist page can land anywhere in the unplayed tail', () => {
  const queue = tracks(60);
  const arrivals = tracks(40, 61);
  // Reverse stands in for a shuffle: deterministic, but it does move every
  // element, so a merge that merely appends is visibly distinguishable.
  const merged = mergeShuffledTail(queue, arrivals, 20, (items) => [...items].reverse());

  assert.deepEqual(merged.slice(0, 20), queue.slice(0, 20), 'the pinned lookahead is left alone');
  assert.equal(merged.length, 100);
  assert.equal(merged[20].id, 'track-100', 'the last page reaches the front of the tail');
  assert.deepEqual(
    [...merged].map((track) => track.id).sort(),
    [...queue, ...arrivals].map((track) => track.id).sort(),
    'every track survives the merge exactly once'
  );
});

test('an empty page leaves the queue untouched', () => {
  const queue = tracks(30);
  assert.equal(mergeShuffledTail(queue, [], 20, () => []), queue);
});

test('a queue shorter than the pinned lookahead still takes arrivals', () => {
  const queue = tracks(3);
  const merged = mergeShuffledTail(queue, tracks(2, 4), 20, (items) => items);

  assert.deepEqual(merged.map((track) => track.id), ['track-1', 'track-2', 'track-3', 'track-4', 'track-5']);
});

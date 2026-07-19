import assert from 'node:assert/strict';
import test from 'node:test';
import { playlistPlayedTrackIds, playlistPreviousState, unusedPlaylistTracks } from '../src/app/playback/playbackCollectionQueue.js';

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

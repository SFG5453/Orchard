/*
 * Copyright (C) 2026 SFG545
 *
 * This file is part of Orchard.
 *
 * Orchard is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option)
 * any later version.
 *
 * Orchard is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License
 * for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Orchard. If not, see <https://www.gnu.org/licenses/>.
 */

import assert from 'node:assert/strict';
import test from 'node:test';
import { createBrowseItemNormalizers } from '../src/catalog/browseItemNormalizers.js';
import { asText, bestThumbnail, findDurationText, hasExplicitBadge, textParts } from '../src/catalog/musicText.js';

const { normalizeRawTwoRowItem, normalizeRawResponsiveListItem } = createBrowseItemNormalizers({
  asText, bestThumbnail, findDurationText, hasExplicitBadge, textParts
});

function browseEndpoint(browseId, pageType) {
  return {
    browseId,
    ...(pageType ? { browseEndpointContextSupportedConfigs: {
      browseEndpointContextMusicConfig: { pageType }
    } } : {})
  };
}

function albumCard(browse = browseEndpoint('MPREb_wuthering', 'MUSIC_PAGE_TYPE_ALBUM')) {
  return { musicTwoRowItemRenderer: {
    title: { runs: [{ text: 'Wuthering Heights' }] },
    subtitle: { runs: [{ text: 'Album • 2026' }] },
    navigationEndpoint: { browseEndpoint: browse },
    thumbnailOverlay: { musicItemThumbnailOverlayRenderer: { content: {
      musicPlayButtonRenderer: { playNavigationEndpoint: { watchEndpoint: {
        videoId: 'first-song', playlistId: 'OLAK5uy_album'
      } } }
    } } }
  } };
}

test('album card with a thumbnail play endpoint remains an album, not a downloadable song', () => {
  const result = normalizeRawTwoRowItem(albumCard());
  assert.equal(result.type, 'album');
  assert.equal(result.browseId, 'MPREb_wuthering');
  assert.equal(result.browsePayload.browseId, 'MPREb_wuthering');
  assert.equal(result.id, null);
  assert.equal(result.audioPlaylistId, 'OLAK5uy_album');
});

test('album browse-id prefix identifies releases when YouTube omits pageType', () => {
  const result = normalizeRawTwoRowItem(albumCard(browseEndpoint('MPREb_wuthering')));
  assert.equal(result.type, 'album');
  assert.equal(result.id, null);
  assert.equal(result.browseId, 'MPREb_wuthering');
});

test('responsive release rows prefer album navigation over a thumbnail play action', () => {
  const result = normalizeRawResponsiveListItem({ musicResponsiveListItemRenderer: {
    flexColumns: [{ musicResponsiveListItemFlexColumnRenderer: { text: { runs: [{
      text: 'Wuthering Heights',
      navigationEndpoint: { browseEndpoint: browseEndpoint('MPREb_wuthering', 'MUSIC_PAGE_TYPE_ALBUM') }
    }] } } }],
    overlay: { musicItemThumbnailOverlayRenderer: { content: {
      musicPlayButtonRenderer: { playNavigationEndpoint: { watchEndpoint: { videoId: 'first-song' } } }
    } } }
  } });
  assert.equal(result.type, 'album');
  assert.equal(result.id, null);
  assert.equal(result.browseId, 'MPREb_wuthering');
});

test('ordinary track tiles retain their song ID and playback actions', () => {
  const result = normalizeRawTwoRowItem({ musicTwoRowItemRenderer: {
    title: { runs: [{ text: 'Song' }] },
    navigationEndpoint: { watchEndpoint: { videoId: 'song-id',
      watchEndpointMusicSupportedConfigs: { watchEndpointMusicConfig: { musicVideoType: 'MUSIC_VIDEO_TYPE_ATV' } }
    } }
  } });
  assert.equal(result.type, 'track');
  assert.equal(result.id, 'song-id');
});

test('playlist tiles with a play-overlay remain playlists', () => {
  const card = albumCard(browseEndpoint('VL_playlist', 'MUSIC_PAGE_TYPE_PLAYLIST'));
  const result = normalizeRawTwoRowItem(card);
  assert.equal(result.type, 'playlist');
  assert.equal(result.id, null);
  assert.equal(result.browseId, 'VL_playlist');
});

function songRow(subtitleRuns) {
  return { musicResponsiveListItemRenderer: {
    flexColumns: [
      { musicResponsiveListItemFlexColumnRenderer: { text: { runs: [{
        text: 'Russian Roulette', navigationEndpoint: { watchEndpoint: { videoId: 'song-id' } }
      }] } } },
      { musicResponsiveListItemFlexColumnRenderer: { text: { runs: subtitleRuns } } }
    ]
  } };
}

test('song rows keep an artist credit that has no channel link', () => {
  const album = { text: 'Scandinavian Metal Attack', navigationEndpoint: { browseEndpoint: browseEndpoint('MPREb_x') } };
  const filtered = normalizeRawResponsiveListItem(songRow([
    { text: 'Mentzer' }, { text: ' • ' }, album, { text: ' • ' }, { text: '4:25' }
  ]));
  assert.equal(filtered.artist, 'Mentzer');
  assert.deepEqual(filtered.artists, ['Mentzer']);
  assert.deepEqual(filtered.artistBrowseIds, []);

  const labelled = normalizeRawResponsiveListItem(songRow([
    { text: 'Song' }, { text: ' • ' }, { text: 'Mentzer' }, { text: ' • ' }, album
  ]));
  assert.equal(labelled.artist, 'Mentzer');

  const uncredited = normalizeRawResponsiveListItem(songRow([album, { text: ' • ' }, { text: '4:25' }]));
  assert.equal(uncredited.artist, '');
});

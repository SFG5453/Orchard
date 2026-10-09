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
import { createBrowseNormalizers } from '../src/catalog/browseNormalizers.js';
import {
  asText,
  bestThumbnail,
  cleanedText,
  findDurationText,
  hasExplicitBadge,
  normalizeTrack,
  normalizedLooseText,
  textParts
} from '../src/catalog/musicText.js';

function normalizers() {
  return createBrowseNormalizers({
    asText,
    bestThumbnail,
    cleanedText,
    findDurationText,
    hasExplicitBadge,
    normalizeTrack,
    normalizedLooseText,
    textParts
  });
}

test('normalizeTrack recovers seconds from a visible clock', () => {
  const track = normalizeTrack({ title: 'Carter Son', duration: { text: '2:43' } });
  assert.equal(track.duration, '2:43');
  assert.equal(track.durationSeconds, 163);
  assert.equal(normalizeTrack({ title: 'Another Song', fixed_columns: [{ title: { text: '3:05' } }] }).durationSeconds, 185);
});

function realAlbumResponse({ includeShelfDescription = true } = {}) {
  return {
    contents: {
      twoColumnBrowseResultsRenderer: {
        tabs: [{
          tabRenderer: {
            content: {
              sectionListRenderer: {
                contents: [{
                  musicResponsiveHeaderRenderer: {
                    title: { runs: [{ text: '24K Magic' }] },
                    subtitle: { runs: [{ text: 'Album' }, { text: ' • ' }, { text: '2016' }] },
                    straplineTextOne: {
                      runs: [{
                        text: 'Bruno Mars',
                        navigationEndpoint: { browseEndpoint: { browseId: 'UCZn4r7heNOPY-C43YIywnVA' } }
                      }]
                    },
                    secondSubtitle: { runs: [{ text: '9 songs' }, { text: ' • ' }, { text: '33 minutes' }] },
                    ...(includeShelfDescription ? {
                      description: {
                        musicDescriptionShelfRenderer: {
                          description: { runs: [{ text: '24K Magic is the third studio album by Bruno Mars.' }] }
                        }
                      }
                    } : {})
                  }
                }]
              }
            }
          }
        }]
      }
    },
    microformat: {
      microformatDataRenderer: {
        title: '24K Magic',
        description: 'Album • Bruno Mars'
      }
    }
  };
}

test('normalizeAlbum finds the artist when the header is nested inside the tab content (current YouTube shape)', () => {
  const { normalizeAlbum } = normalizers();
  const result = normalizeAlbum(realAlbumResponse(), 'MPREb_test');

  assert.equal(result.artist, 'Bruno Mars');
});

test('normalizeAlbum reads the real prose description out of the description shelf, not the "Album • Artist" microformat tag', () => {
  const { normalizeAlbum } = normalizers();
  const result = normalizeAlbum(realAlbumResponse(), 'MPREb_test');

  assert.equal(result.description, '24K Magic is the third studio album by Bruno Mars.');
});

test('normalizeAlbum falls back to nothing (not the microformat tag) when there is no real description', () => {
  const { normalizeAlbum } = normalizers();
  const result = normalizeAlbum(realAlbumResponse({ includeShelfDescription: false }), 'MPREb_test');

  assert.notEqual(result.description, 'Album • Bruno Mars');
});

test('normalizeAlbum still reads the artist from the older header shape (artist run inside subtitle)', () => {
  const album = {
    header: {
      musicDetailHeaderRenderer: {
        title: { runs: [{ text: '24K Magic' }] },
        subtitle: {
          runs: [
            { text: 'Album' },
            { text: ' • ' },
            { text: 'Bruno Mars', navigationEndpoint: { browseEndpoint: { browseId: 'UCZn4r7heNOPY-C43YIywnVA' } } },
            { text: ' • ' },
            { text: '2016' }
          ]
        }
      }
    },
    contents: {
      twoColumnBrowseResultsRenderer: {
        secondaryContents: { sectionListRenderer: { contents: [] } }
      }
    }
  };

  const { normalizeAlbum } = normalizers();
  const result = normalizeAlbum(album, 'MPREb_test');

  assert.equal(result.artist, 'Bruno Mars');
});

for (const editable of [false, true]) {
  test(`normalizePlaylist reads creator and custom description (editable: ${editable})`, () => {
    const header = {
      title: { runs: [{ text: 'chill' }] },
      straplineTextOne: { runs: [{ text: 'SFG', navigationEndpoint: { browseEndpoint: { browseId: 'UC_creator' } } }] },
      description: { musicDescriptionShelfRenderer: { description: { runs: [{ text: 'Music for a quiet evening.' }] } } }
    };
    const data = { header: editable
      ? { musicEditablePlaylistDetailHeaderRenderer: { header: { musicResponsiveHeaderRenderer: header } } }
      : { musicResponsiveHeaderRenderer: header } };
    const result = normalizers().normalizePlaylist({ data, browseId: 'VL_chill' });
    assert.equal(result.author, 'SFG');
    assert.equal(result.description, 'Music for a quiet evening.');
  });
}

for (const description of ['Listen to chill on YouTube Music', 'Playlist • SFG', 'Playlist  •  SFG', '']) {
  test(`normalizePlaylist hides default description: ${description}`, () => {
    const data = {
      header: { musicDetailHeaderRenderer: { subtitle: { runs: [{ text: 'Playlist • SFG' }] } } },
      microformat: { microformatDataRenderer: { title: 'chill', description } }
    };
    assert.equal(normalizers().normalizePlaylist({ data, browseId: 'VL_chill' }).description, '');
  });
}

test('normalizeAlbum extracts audioPlaylistId from canonical microformat URL', () => {
  const { normalizeAlbum } = normalizers();
  const raw = realAlbumResponse();
  raw.microformat.microformatDataRenderer.urlCanonical = 'https://music.youtube.com/playlist?list=OLAK5uy_testCanonical123';
  const result = normalizeAlbum(raw, 'MPREb_test');
  assert.equal(result.audioPlaylistId, 'OLAK5uy_testCanonical123');
  assert.equal(result.playlistId, 'OLAK5uy_testCanonical123');
});

test('normalizeAlbum extracts audioPlaylistId from header play button', () => {
  const { normalizeAlbum } = normalizers();
  const raw = realAlbumResponse();
  const header = raw.contents.twoColumnBrowseResultsRenderer.tabs[0].tabRenderer.content.sectionListRenderer.contents[0].musicResponsiveHeaderRenderer;
  header.buttons = [{
    musicPlayButtonRenderer: {
      playNavigationEndpoint: {
        watchEndpoint: {
          playlistId: 'OLAK5uy_playButtonTest456'
        }
      }
    }
  }];
  const result = normalizeAlbum(raw, 'MPREb_test');
  assert.equal(result.audioPlaylistId, 'OLAK5uy_playButtonTest456');
});

test('normalizeAlbum extracts audioPlaylistId from tracks', () => {
  const { normalizeAlbum } = normalizers();
  const raw = realAlbumResponse();
  raw.contents.twoColumnBrowseResultsRenderer.secondaryContents = {
    sectionListRenderer: {
      contents: [{
        musicShelfRenderer: {
          contents: [{
            musicResponsiveListItemRenderer: {
              playlistItemData: { videoId: 'vid1' },
              navigationEndpoint: {
                watchEndpoint: {
                  videoId: 'vid1',
                  playlistId: 'OLAK5uy_trackPlaylist789'
                }
              }
            }
          }]
        }
      }]
    }
  };
  const result = normalizeAlbum(raw, 'MPREb_test');
  assert.equal(result.audioPlaylistId, 'OLAK5uy_trackPlaylist789');
});

test('normalizePlaylist strips VL prefix for playlistId', () => {
  const result = normalizers().normalizePlaylist({ data: {}, browseId: 'VLPL1234567890' });
  assert.equal(result.playlistId, 'PL1234567890');
  assert.equal(result.audioPlaylistId, 'PL1234567890');
});

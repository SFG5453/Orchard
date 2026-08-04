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
import { createBrowseNormalizers } from '../electron/catalog/browseNormalizers.js';
import {
  asText,
  bestThumbnail,
  cleanedText,
  findDurationText,
  hasExplicitBadge,
  normalizeTrack,
  normalizedLooseText,
  textParts
} from '../electron/catalog/musicText.js';

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

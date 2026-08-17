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

import test from 'node:test';
import assert from 'node:assert/strict';

import { normalizedLooseText, textMatchesArtist, textMatchesTitle } from '../electron/catalog/musicText.js';
import { createArtistCatalog } from '../electron/catalog/artistCatalog.js';

test('loose text keeps an identity for titles written in any script', () => {
  for (const title of ['梦醒时分', '小幸运', '夜長夢多', '告五人', 'ロード', 'Пыяла', '아이유']) {
    assert.notEqual(normalizedLooseText(title), '', `${title} lost its identity`);
  }

  // Distinct titles must stay distinct. Collapsing them to one key is the other
  // half of the same bug: it deduplicates a whole catalogue down to one release.
  assert.notEqual(normalizedLooseText('夜長夢多'), normalizedLooseText('小幸運'));
  assert.ok(textMatchesArtist('梦梦', '梦梦'));
  assert.ok(textMatchesTitle('漂浮', '漂浮'));
});

test('loose text still folds punctuation, case, and ampersands the way it did', () => {
  assert.equal(normalizedLooseText('AC/DC'), 'ac dc');
  assert.equal(normalizedLooseText('Tom & Jerry'), 'tom and jerry');
  assert.equal(normalizedLooseText('  Hello!!  '), 'hello');
  assert.equal(normalizedLooseText('Waa Wei'), 'waa wei');
});

test('loose text folds Latin accents without folding Japanese voiced kana', () => {
  assert.equal(normalizedLooseText('Sigur Rós'), 'sigur ros');
  assert.ok(textMatchesTitle('Beyonce', 'Beyoncé'));
  // Stripping every combining mark would turn "da" into "ta" and match two
  // different words, so the fold is limited to the Latin range.
  assert.notEqual(normalizedLooseText('ただ'), normalizedLooseText('たた'));
});

function artistCatalog() {
  return createArtistCatalog({
    asText: (value) => String(value || ''),
    artistBrowseSectionItemMatches: () => true,
    browseContinuationTokenFromData: () => '',
    dedupeMediaItems: (items) => [...new Map(items.map((item) => [item.id || item.browseId, item])).values()],
    isSingleOrEpRelease: (item) => item.kind === 'single',
    itemMatchesReleaseSection: () => true,
    mergeTrackMetadata: (track) => track,
    normalizeAlbum: (album, browseId) => ({ ...album, browseId }),
    normalizeBrowseSection: (section) => section,
    normalizeRawBrowseItem: (item) => item,
    // The real one, because this regression lives entirely in what it returns.
    normalizedLooseText,
    rawBrowseDescription: () => '',
    rawBrowseItemsFromData: () => [],
    rawBrowseThumbnail: () => '',
    rawHeader: (artist) => artist.header,
    rawMicroformat: () => ({}),
    rawSectionList: (artist) => artist.sections,
    searchArtistShelfFallback: async () => [],
    searchTrackAlbumMetadata: async () => null
  });
}

test('an artist keeps every release whose title carries no Latin characters', async () => {
  const collection = {
    browseId: 'mengmeng',
    data: {
      header: { title: '梦梦' },
      sections: [{
        title: 'Albums',
        items: [
          { browseId: 'a1', title: '梦醒时分', kind: 'album' },
          { browseId: 'a2', title: '小幸运', kind: 'album' },
          { browseId: 'a3', title: '漂浮', kind: 'album' },
          { browseId: 'a4', title: 'Waa Wei', kind: 'album' }
        ]
      }]
    }
  };

  const detail = await artistCatalog().normalizeArtist(collection);

  assert.deepEqual(
    detail.sections[0].items.map((item) => item.title),
    ['梦醒时分', '小幸运', '漂浮', 'Waa Wei'],
    'releases were dropped or collapsed by an empty identity key'
  );
});

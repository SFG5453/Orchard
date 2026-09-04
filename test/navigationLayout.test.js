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
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
 * A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Orchard. If not, see <https://www.gnu.org/licenses/>.
 */

import assert from 'node:assert/strict';
import test from 'node:test';
import { ref } from 'vue';
import {
  LIBRARY_HOME_SECTION_ID,
  RECENTLY_PLAYED_HOME_SECTION_ID,
  SIDEBAR_NAV_ITEM_IDS,
  homeSectionId,
  installNavigationLayout,
  normalizeLayoutIds,
  orderLayoutItems,
  visibleLayoutItems
} from '../src/app/core/navigationLayout.js';

test('home section identities remain stable across changing feed keys', () => {
  assert.equal(homeSectionId({ key: 'tv-library-0', title: ' Library ' }), LIBRARY_HOME_SECTION_ID);
  assert.equal(homeSectionId({ key: 'recently-played', isHistory: true }), RECENTLY_PLAYED_HOME_SECTION_ID);
  assert.equal(homeSectionId({ key: 'home-1-section-4', title: 'Made for You' }), 'home:made for you');
});

test('layout identifiers are normalized and de-duplicated', () => {
  assert.deepEqual(normalizeLayoutIds([' concerts ', 'concerts', '', null, 'home']), ['concerts', 'home']);
  assert.deepEqual(normalizeLayoutIds('concerts'), []);
});

test('preferred items move ahead of unconfigured feed additions', () => {
  const items = [{ id: 'library' }, { id: 'recent' }, { id: 'recommendations' }, { id: 'new-shelf' }];
  assert.deepEqual(
    orderLayoutItems(items, ['recommendations', 'library', 'recent']).map((item) => item.id),
    ['recommendations', 'library', 'recent', 'new-shelf']
  );
});

test('hidden layout items are removed after ordering', () => {
  const items = [{ id: 'home' }, { id: 'concerts' }, { id: 'radio' }];
  assert.deepEqual(
    visibleLayoutItems(items, ['radio', 'concerts', 'home'], ['concerts']).map((item) => item.id),
    ['radio', 'home']
  );
});

test('sidebar preference ids cover every configurable navigation link', () => {
  assert.deepEqual(SIDEBAR_NAV_ITEM_IDS, [
    'home', 'new', 'radio', 'concerts',
    'queue', 'history', 'replay', 'playlists', 'songs', 'albums', 'artists', 'podcasts',
    'pins'
  ]);
});

test('every sidebar item dispatches to a concrete destination action', () => {
  const calls = [];
  const action = (name) => (...args) => {
    calls.push([name, ...args]);
    return name;
  };
  const ctx = {
    initialUserPreferences: {
      homeSectionOrder: [],
      hiddenHomeSectionIds: [],
      sidebarItemOrder: [],
      hiddenSidebarItemIds: []
    },
    homeShelfSections: ref([]),
    activeView: ref('home'),
    browseDetail: ref(null),
    searchResult: ref({ sections: [] }),
    selectView: action('selectView'),
    showReleaseRadar: action('showReleaseRadar'),
    openPersonalizedRadio: action('openPersonalizedRadio'),
    openLiveShows: action('openLiveShows'),
    showRecentlyAdded: action('showRecentlyAdded'),
    showLibraryPlaylists: action('showLibraryPlaylists'),
    showLibrarySongs: action('showLibrarySongs'),
    showLibraryAlbums: action('showLibraryAlbums'),
    showSubscribedArtists: action('showSubscribedArtists'),
    loadPodcasts: action('loadPodcasts'),
    showPins: action('showPins')
  };
  installNavigationLayout(ctx);

  for (const group of ctx.visibleSidebarNavGroups.value) {
    for (const item of group.items) ctx.openSidebarItem(item);
  }

  assert.deepEqual(calls, [
    ['selectView', 'home'],
    ['showReleaseRadar'],
    ['openPersonalizedRadio'],
    ['openLiveShows'],
    ['selectView', 'queue'],
    ['showRecentlyAdded'],
    ['selectView', 'replay'],
    ['showLibraryPlaylists'],
    ['showLibrarySongs'],
    ['showLibraryAlbums'],
    ['showSubscribedArtists'],
    ['loadPodcasts'],
    ['showPins']
  ]);
});
